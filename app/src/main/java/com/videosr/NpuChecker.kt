package com.videosr

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileOutputStream

/**
 * Actually probes whether NNAPI can run on this device by trying to
 * create an NNAPI delegate and allocate tensors on a tiny real model.
 */
object NpuChecker {
    private const val TAG = "NpuChecker"

    @Volatile private var cached: Boolean? = null
    @Volatile private var acceleratorName: String? = null

    fun hasNpu(context: Context): Boolean {
        cached?.let { return it }
        val result = probe(context)
        cached = result
        return result
    }

    fun acceleratorInfo(): String = acceleratorName ?: "unknown"

    private fun probe(context: Context): Boolean {
        // NNAPI requires Android 8.1 (API 27) minimum
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            Log.i(TAG, "NNAPI not available: API < 27")
            return false
        }

        var modelFile: File? = null
        var delegate: NnApiDelegate? = null
        var interpreter: Interpreter? = null

        try {
            // Copy the smallest bundled model to a temp file for probing
            modelFile = File(context.cacheDir, "probe_model.tflite")
            if (!modelFile.exists() || modelFile.length() < 1000) {
                context.assets.open("realesr_animevideov3.tflite").use { input ->
                    FileOutputStream(modelFile).use { out -> input.copyTo(out) }
                }
            }

            val opts = Interpreter.Options()
            delegate = NnApiDelegate()
            opts.addDelegate(delegate)
            opts.setNumThreads(1)

            interpreter = Interpreter(modelFile, opts)
            // allocateTensors forces NNAPI to actually initialize the accelerator
            interpreter.allocateTensors()
            acceleratorName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Build.SOC_MODEL else "NNAPI"
            Log.i(TAG, "NNAPI probe SUCCESS: accelerator=$acceleratorName")
            return true
        } catch (t: Throwable) {
            Log.i(TAG, "NNAPI probe FAILED: ${t.message}")
            return false
        } finally {
            try { interpreter?.close() } catch (_: Exception) {}
            try { delegate?.close() } catch (_: Exception) {}
            // keep modelFile for reuse next time
        }
    }
}
