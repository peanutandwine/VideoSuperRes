package com.videosr

import android.os.Build
import android.util.Log

/**
 * NPU detection — mirrors the approach used by localdream.
 *
 * True NPU acceleration requires vendor SDKs (Qualcomm QNN for Hexagon,
 * MediaTek NeuroPilot for APU). TFLite NNAPI is unreliable on many
 * devices even when an NPU physically exists.
 *
 * We currently detect Qualcomm Snapdragon SM-series chips that ship with
 * Hexagon NPU. MediaTek Kirin/Tensor detection is heuristic.
 */
object NpuChecker {
    private const val TAG = "NpuChecker"

    @Volatile private var cached: Boolean? = null
    @Volatile private var acceleratorName: String? = null
    @Volatile private var lastError: String? = null

    fun hasNpu(context: android.content.Context): Boolean {
        cached?.let { return it }
        val result = probe()
        cached = result
        return result
    }

    fun acceleratorInfo(): String = acceleratorName ?: "unknown"
    fun lastError(): String = lastError ?: ""

    private fun probe(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            lastError = "API < 31, cannot detect SoC"
            return false
        }

        val soc = Build.SOC_MODEL.uppercase()
        val hardware = Build.HARDWARE.uppercase()
        Log.i(TAG, "SoC model: '$soc', hardware: '$hardware'")

        return when {
            // Qualcomm Snapdragon — SMxxxx naming, Hexagon NPU
            soc.startsWith("SM") -> {
                val digits = soc.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
                val part = digits.toIntOrNull() ?: 0
                acceleratorName = "Snapdragon $soc (Hexagon NPU)"
                // Any SM-series with a known Hexagon NPU
                val hasHexagon = part >= 8150 || (part in 6000..8099 && part % 100 >= 50) ||
                                 soc.startsWith("SM8") || soc.startsWith("SM7") ||
                                 soc.startsWith("SM6") || soc.startsWith("SM4")
                if (hasHexagon) {
                    Log.i(TAG, "Detected Qualcomm Hexagon NPU: $soc")
                    true
                } else {
                    lastError = "Snapdragon $soc — Hexagon NPU unknown"
                    false
                }
            }
            // MediaTek Dimensity / Helio
            soc.contains("DIMENSITY") || soc.startsWith("MT6") || soc.startsWith("MT8") -> {
                acceleratorName = "MediaTek $soc (APU)"
                Log.i(TAG, "Detected MediaTek APU: $soc")
                true
            }
            // Google Tensor
            soc.contains("TENSOR") -> {
                acceleratorName = "Google Tensor"
                Log.i(TAG, "Detected Google Tensor")
                true
            }
            // Huawei Kirin
            soc.contains("KIRIN") -> {
                acceleratorName = "Huawei $soc (NPU)"
                Log.i(TAG, "Detected Huawei Kirin NPU: $soc")
                true
            }
            // Samsung Exynos
            soc.contains("EXYNOS") -> {
                acceleratorName = "Samsung $soc (NPU)"
                Log.i(TAG, "Detected Samsung Exynos NPU: $soc")
                true
            }
            else -> {
                lastError = "Unknown SoC: $soc"
                false
            }
        }
    }
}
