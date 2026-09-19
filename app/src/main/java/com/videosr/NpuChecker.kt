package com.videosr

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File

/**
 * Probes NNAPI availability by trying to construct an NnApiDelegate.
 * On modern Android, NnApiDelegate() constructor throws if no NNAPI
 * driver/service is available. We also try listing the available
 * accelerators via NNAPI's device enumeration.
 */
object NpuChecker {
    private const val TAG = "NpuChecker"

    @Volatile private var cached: Boolean? = null
    @Volatile private var acceleratorName: String? = null
    @Volatile private var lastError: String? = null

    fun hasNpu(context: Context): Boolean {
        cached?.let { return it }
        val result = probe()
        cached = result
        return result
    }

    fun acceleratorInfo(): String = acceleratorName ?: "unknown"
    fun lastError(): String = lastError ?: ""

    private fun probe(): Boolean {
        lastError = null

        // NNAPI requires Android 8.1 (API 27) minimum
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            lastError = "API ${Build.VERSION.SDK_INT} < 27, NNAPI 不可用"
            return false
        }

        try {
            // Just constructing the delegate is enough — NNAPI throws
            // if the NNAPI service / drivers are unavailable.
            val delegate = NnApiDelegate()
            acceleratorName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Build.SOC_MODEL else "NNAPI"
            Log.i(TAG, "NNAPI probe SUCCESS: accelerator=$acceleratorName")
            delegate.close()
            return true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "NNAPI probe FAILED", t)
            return false
        }
    }
}
