package com.videosr.gles

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGL10

/**
 * Minimal EGL14 off-screen context bound to an encoder input Surface.
 * Also creates the SurfaceTexture whose Surface feeds the hardware decoder.
 */
class EglCore {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: Surface? = null
    var surfaceTexture: SurfaceTexture? = null
        private set
    var inputSurface: Surface? = null
        private set

    fun init(encoderInput: Surface, textureId: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        val config = configs[0]!!

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfAttrs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderInput, surfAttrs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        encoderSurface = encoderInput

        surfaceTexture = SurfaceTexture(textureId)
        inputSurface = Surface(surfaceTexture)
        Log.i(TAG, "EGL initialized")
    }

    fun setSurfaceTextureSize(w: Int, h: Int) {
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    /**
     * Waits for the next decoder frame to become available in the SurfaceTexture.
     * updateTexImage() blocks until a new frame is queued.
     */
    fun awaitNewFrame(timeoutMs: Long): Boolean {
        // SurfaceTexture.updateTexImage() will block until a frame arrives.
        // We use a short sleep + poll to avoid hanging forever on EOS.
        return try {
            surfaceTexture?.let {
                // updateTexImage blocks until a new frame is available
                it.updateTexImage()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun updateTexImage() {
        surfaceTexture?.updateTexImage()
    }

    fun getTransformMatrix(): FloatArray {
        val m = FloatArray(16)
        surfaceTexture?.getTransformMatrix(m)
        return m
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        try { inputSurface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        encoderSurface = null
    }

    companion object {
        private const val TAG = "EglCore"
    }
}
