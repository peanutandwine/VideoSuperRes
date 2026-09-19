package com.videosr

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

/**
 * Remuxes a temporary MP4 into MOV/MKV/AVI using the bundled FFmpeg.
 * Uses -c copy so no re-encoding happens (fast, lossless).
 */
object Remuxer {
    private const val TAG = "Remuxer"

    fun remux(srcMp4: File, dst: File, container: Transcoder.Container): Boolean {
        return try {
            val cmd = buildString {
                append("-y -i ").append(shellQuote(srcMp4.absolutePath))
                append(" -c copy -map 0")
                append(" -f ").append(container.ffmpegFmt)
                append(" ").append(shellQuote(dst.absolutePath))
            }
            Log.i(TAG, "ffmpeg $cmd")
            val rc = FFmpegKit.execute(cmd)
            val ok = rc == FFmpegKit.RETURN_CODE_SUCCESS
            if (!ok) Log.e(TAG, "ffmpeg rc=$rc")
            ok && dst.exists() && dst.length() > 0
        } catch (t: Throwable) {
            Log.e(TAG, "remux failed", t)
            false
        }
    }

    // FFmpegKit.execute does not invoke a shell, but we quote paths anyway
    // to handle spaces and special chars in the argument parser.
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
