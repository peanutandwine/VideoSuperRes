package com.videosr

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Detects whether the device likely has an NPU accessible via NNAPI.
 * Uses SoC family heuristics; AI super-resolution is disabled by default
 * on devices without a detected NPU (user can force-enable in Settings).
 */
object NpuChecker {
    private const val TAG = "NpuChecker"

    @Volatile private var cached: Boolean? = null

    fun hasNpu(context: Context): Boolean {
        cached?.let { return it }
        val result = looksLikeNpuSoC()
        cached = result
        Log.i(TAG, "NPU detection: $result (SoC: ${Build.SOC_MODEL} / ${Build.HARDWARE})")
        return result
    }

    private fun looksLikeNpuSoC(): Boolean {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val soc = (Build.HARDWARE + " " + socModel + " " + Build.DEVICE + " " + Build.MODEL).lowercase()
        return when {
            listOf("qcom", "sdm8", "sm8", "sm7", "sm6", "kalama", "pineapple", "sun", "lahaina").any { soc.contains(it) } -> true
            listOf("mt6", "mt8", "dimensity", "helio", "mt67", "mt68", "mt69", "mt81", "mt86", "mt87").any { soc.contains(it) } -> true
            soc.contains("kirin") -> true
            soc.contains("tensor") -> true
            listOf("exynos 2100", "exynos 2200", "exynos 2400", "exynos 1380", "exynos 1480").any { soc.contains(it) } -> true
            else -> false
        }
    }
}
