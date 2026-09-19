package com.videosr.gles

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the decoder's external texture through a high-quality scaling shader
 * onto the encoder input surface.
 *
 * Modes:
 *  0 = bilinear (passthrough)
 *  1 = Lanczos downscale (super-sampled downscaling for crisp small output)
 *  2 = Bicubic (Catmull-Rom) upscale (super-resolution)
 */
class TextureRenderer {
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uInputSize = 0
    private var uOutputSize = 0
    private var uMode = 0

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var texBuf: FloatBuffer

    companion object {
        const val MODE_BILINEAR = 0
        const val MODE_LANCZOS_DOWN = 1
        const val MODE_BICUBIC_UP = 2

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            uniform vec2 uInputSize;
            uniform vec2 uOutputSize;
            uniform int uMode;

            float lanczos(float x) {
                if (abs(x) < 1e-6) return 1.0;
                float px = 3.14159265358979 * x;
                return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
            }

            vec4 lanczosDownsample() {
                vec2 scale = max(uInputSize / uOutputSize, vec2(1.0));
                vec2 texel = 1.0 / uInputSize;
                vec4 sum = vec4(0.0);
                float wsum = 0.0;
                for (int y = -4; y <= 4; y++) {
                    for (int x = -4; x <= 4; x++) {
                        vec2 off = vec2(float(x), float(y)) / scale;
                        float wx = lanczos(float(x) / scale.x);
                        float wy = lanczos(float(y) / scale.y);
                        float w = wx * wy;
                        sum += texture2D(sTexture, vTexCoord + off * texel) * w;
                        wsum += w;
                    }
                }
                return sum / max(wsum, 1e-5);
            }

            float cubic(float x) {
                x = abs(x);
                if (x < 1.0) return (1.5*x*x*x - 2.5*x*x + 1.0);
                if (x < 2.0) return (-0.5*x*x*x + 2.5*x*x - 4.0*x + 2.0);
                return 0.0;
            }

            vec4 bicubicUpscale() {
                vec2 texel = 1.0 / uInputSize;
                vec2 coord = vTexCoord * uInputSize - 0.5;
                vec2 f = fract(coord);
                vec2 base = (floor(coord) + 0.5) * texel;
                vec4 sum = vec4(0.0);
                float wsum = 0.0;
                for (int y = -1; y <= 2; y++) {
                    for (int x = -1; x <= 2; x++) {
                        float wx = cubic(float(x) - f.x);
                        float wy = cubic(float(y) - f.y);
                        float w = wx * wy;
                        vec2 off = vec2(float(x), float(y)) * texel;
                        sum += texture2D(sTexture, base + off) * w;
                        wsum += w;
                    }
                }
                return sum / max(wsum, 1e-5);
            }

            void main() {
                if (uMode == 1) {
                    gl_FragColor = lanczosDownsample();
                } else if (uMode == 2) {
                    gl_FragColor = bicubicUpscale();
                } else {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            }
        """
    }

    fun getTextureId(): Int {
        if (texId != 0) return texId
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texId
    }

    fun init() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uInputSize = GLES20.glGetUniformLocation(program, "uInputSize")
        uOutputSize = GLES20.glGetUniformLocation(program, "uOutputSize")
        uMode = GLES20.glGetUniformLocation(program, "uMode")

        // Full-screen quad
        val vtx = floatArrayOf(
            -1f, -1f, 0f,
             1f, -1f, 0f,
            -1f,  1f, 0f,
             1f,  1f, 0f
        )
        vertexBuf = ByteBuffer.allocateDirect(vtx.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vtx)
        vertexBuf.position(0)

        // UVs match the quad (SurfaceTexture transform matrix will flip as needed)
        val uv = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        texBuf = ByteBuffer.allocateDirect(uv.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(uv)
        texBuf.position(0)
    }

    fun draw(transform: FloatArray, inW: Int, inH: Int, outW: Int, outH: Int, mode: Int) {
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, transform, 0)
        GLES20.glUniform2f(uInputSize, inW.toFloat(), inH.toFloat())
        GLES20.glUniform2f(uOutputSize, outW.toFloat(), outH.toFloat())
        GLES20.glUniform1i(uMode, mode)

        vertexBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertexBuf)

        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            Log.e("TextureRenderer", "program link failed: $log")
            throw RuntimeException("GL program link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(s)
            Log.e("TextureRenderer", "shader compile failed: $log")
            throw RuntimeException("GL shader compile failed: $log")
        }
        return s
    }
}
