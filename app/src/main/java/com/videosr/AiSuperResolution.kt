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
        val scale = getOutputScale()
        return try {
            val inW = input.width
            val inH = input.height
            val outW = (inW * scale).toInt()
            val outH = (inH * scale).toInt()
            val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

            // If input matches model size, single-pass; otherwise tile
            if (inW <= inputW && inH <= inputH) {
                val tile = Bitmap.createScaledBitmap(input, inputW, inputH, true)
                val tileOut = runModel(tile)
                if (tileOut != null) {
                    // Scale tile output to final size
                    val scaled = Bitmap.createScaledBitmap(tileOut, outW, outH, true)
                    result.setPixels(IntArray(outW*outH).also { scaled.getPixels(it,0,outW,0,0,outW,outH) },
                        0, outW, 0, 0, outW, outH)
                    tileOut.recycle()
                }
                tile.recycle()
            } else {
                // Tile-based inference with overlap
                val overlap = 8
                val tileW = inputW
                val tileH = inputH
                val stepX = tileW - overlap
                val stepY = tileH - overlap
                val outTileW = tileW * scale.toInt()
                val outTileH = tileH * scale.toInt()

                for (ty in 0 until inH step stepY) {
                    for (tx in 0 until inW step stepX) {
                        val sx = tx.coerceAtMost(inW - tileW)
                        val sy = ty.coerceAtMost(inH - tileH)
                        val tile = Bitmap.createBitmap(input, sx, sy, tileW, tileH)
                        val tileOut = runModel(tile)
                        if (tileOut != null) {
                            // Copy tile output to result
                            val srcX = 0; val srcY = 0
                            val dstX = (sx * scale).toInt()
                            val dstY = (sy * scale).toInt()
                            val copyW = outTileW; val copyH = outTileH
                            val outPixels = IntArray(copyW * copyH)
                            tileOut.getPixels(outPixels, 0, copyW, 0, 0, copyW, copyH)
                            result.setPixels(outPixels, 0, copyW, dstX, dstY, copyW, copyH)
                            tileOut.recycle()
                        }
                        tile.recycle()
                    }
                }
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "AI SR run failed", t)
            null
        }
    }

    private fun runModel(tile: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        val inputBuf = ByteBuffer.allocateDirect(1 * inputH * inputW * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        tile.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
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
                val r = (outBuf.getFloat() * 255).toInt().coerceIn(0, 255)
                val g = (outBuf.getFloat() * 255).toInt().coerceIn(0, 255)
                val b = (outBuf.getFloat() * 255).toInt().coerceIn(0, 255)
                outPixels[y * outputW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, outputW, 0, 0, outputW, outputH)
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
