package com.videosr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight AI super-resolution using a TFLite model (ESPCN x2).
 * NNAPI/GPU delegate is attached automatically by TFLite if available;
 * if the model asset is missing, [isAvailable] is false and callers
 * fall back to the OpenGL bicubic path.
 */
class AiSuperResolution(context: Context, preferNpu: Boolean) {
    private var interpreter: Interpreter? = null
    private val scale = 2

    init {
        try {
            val modelBytes = context.assets.open("espcn_x2.tflite").readBytes()
            val bb = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            bb.put(modelBytes); bb.rewind()

            val options = Interpreter.Options().apply {
                setNumThreads(2)
                // NNAPI delegate is attached only if the device supports it;
                // TFLite will fall back to CPU automatically.
                if (preferNpu) {
                    try {
                        val nnapiCls = Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate")
                        val delegate = nnapiCls.getConstructor().newInstance()
                        val addMethod = Interpreter.Options::class.java.getMethod("addDelegate",
                            Class.forName("org.tensorflow.lite.Delegate"))
                        addMethod.invoke(this, delegate)
                        Log.i(TAG, "NNAPI delegate attached via reflection")
                    } catch (t: Throwable) {
                        Log.w(TAG, "NNAPI not available, using CPU: ${t.message}")
                    }
                }
            }
            interpreter = Interpreter(bb, options)
            Log.i(TAG, "ESPCN interpreter ready")
        } catch (t: Throwable) {
            Log.w(TAG, "AI SR init failed (model missing?): ${t.message}")
            interpreter = null
        }
    }

    val isAvailable: Boolean get() = interpreter != null

    fun upscale(input: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        return try {
            val w = input.width
            val h = input.height
            val inputBuf = ByteBuffer.allocateDirect(1 * h * w * 3 * 4).order(ByteOrder.nativeOrder())
            val pixels = IntArray(w * h)
            input.getPixels(pixels, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    inputBuf.putFloat(((p shr 16) and 0xFF) / 255f)
                    inputBuf.putFloat(((p shr 8) and 0xFF) / 255f)
                    inputBuf.putFloat((p and 0xFF) / 255f)
                }
            }
            inputBuf.rewind()

            val outW = w * scale
            val outH = h * scale
            val outBuf = ByteBuffer.allocateDirect(1 * outH * outW * 3 * 4).order(ByteOrder.nativeOrder())
            interp.run(inputBuf, outBuf)
            outBuf.rewind()

            val outPixels = IntArray(outW * outH)
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    val r = (outBuf.float * 255).toInt().coerceIn(0, 255)
                    val g = (outBuf.float * 255).toInt().coerceIn(0, 255)
                    val b = (outBuf.float * 255).toInt().coerceIn(0, 255)
                    outPixels[y * outW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
                setPixels(outPixels, 0, outW, 0, 0, outW, outH)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AI SR run failed", t)
            null
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }

    companion object { private const val TAG = "AiSuperRes" }
}
