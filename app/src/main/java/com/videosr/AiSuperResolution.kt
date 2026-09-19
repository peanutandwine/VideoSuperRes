package com.videosr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AI super-resolution using TFLite with GPU delegate.
 * NPU (QNN/NeuroPilot) is a future iteration; for now GPU is the fastest
 * widely available acceleration path.
 */
class AiSuperResolution(context: Context, modelAsset: String, preferGpu: Boolean = true) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var inputW = 0
    private var inputH = 0
    private var outputW = 0
    private var outputH = 0

    init {
        try {
            Log.i(TAG, "Loading model: $modelAsset")
            val modelBytes = context.assets.open(modelAsset).readBytes()
            val bb = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            bb.put(modelBytes); bb.rewind()

            val options = Interpreter.Options().apply { setNumThreads(2) }

            if (preferGpu) {
                try {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                    Log.i(TAG, "GPU delegate attached")
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate init failed, falling back to CPU: ${t.message}")
                }
            }

            interpreter = Interpreter(bb, options)

            // Read model input/output shapes
            val inFmt = interpreter!!.getInputTensor(0).shape()
            val outFmt = interpreter!!.getOutputTensor(0).shape()
            // Shape is [batch, height, width, channels]
            inputH = inFmt[1]; inputW = inFmt[2]
            outputH = outFmt[1]; outputW = outFmt[2]
            Log.i(TAG, "Model I/O: ${inputW}x${inputH} -> ${outputW}x${outputH}")
        } catch (t: Throwable) {
            Log.e(TAG, "AI SR init failed", t)
            interpreter = null
        }
    }

    val isAvailable: Boolean get() = interpreter != null

    fun getOutputScale(): Float = if (inputW > 0) outputW.toFloat() / inputW else 2f

    fun upscale(input: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        return try {
            // Resize input to model's expected size
            val modelIn = Bitmap.createScaledBitmap(input, inputW, inputH, true)
            val inputBuf = ByteBuffer.allocateDirect(1 * inputH * inputW * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(inputW * inputH)
            modelIn.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            for (p in pixels) {
                inputBuf.putFloat(((p shr 16) and 0xFF) / 255f)
                inputBuf.putFloat(((p shr 8) and 0xFF) / 255f)
                inputBuf.putFloat((p and 0xFF) / 255f)
            }
            inputBuf.rewind()

            val outBuf = ByteBuffer.allocateDirect(1 * outputH * outputW * 3 * 4)
                .order(ByteOrder.nativeOrder())
            interp.run(inputBuf, outBuf)
            outBuf.rewind()

            val outPixels = IntArray(outputW * outputH)
            for (y in 0 until outputH) {
                for (x in 0 until outputW) {
                    val r = (outBuf.float * 255).toInt().coerceIn(0, 255)
                    val g = (outBuf.float * 255).toInt().coerceIn(0, 255)
                    val b = (outBuf.float * 255).toInt().coerceIn(0, 255)
                    outPixels[y * outputW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888).apply {
                setPixels(outPixels, 0, outputW, 0, 0, outputW, outputH)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AI SR run failed", t)
            null
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
    }

    companion object { private const val TAG = "AiSuperRes" }
}
