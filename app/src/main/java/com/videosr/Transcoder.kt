package com.videosr

import android.media.*
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.videosr.gles.EglCore
import com.videosr.gles.TextureRenderer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Hardware-accelerated transcoder with configurable:
 *  - output resolution, bitrate, frame rate
 *  - video codec (AVC/HEVC/VP9/AV1)
 *  - container (MP4/WebM via MediaMuxer; MOV reuses MP4/ISO-BMFF;
 *              MKV/AVI are remuxed by ffmpeg-kit afterwards)
 *  - scaling mode: bilinear / Lanczos downscale / bicubic upscale / AI (TFLite NNAPI)
 *
 * Audio is always decoded and re-encoded to AAC (MP4/MOV) or Opus (WebM).
 * MKV/AVI remux step re-uses the already-encoded audio/video streams (-c copy).
 */
class Transcoder(
    private val input: ParcelFileDescriptor,
    private val outputFile: File,
    private val outWidth: Int,
    private val outHeight: Int,
    private val bitrateBps: Int,
    private val frameRate: Int,
    private val videoMime: String,
    private val container: Container,
    private val scaleMode: Int,
    private val listener: (Progress) -> Unit
) {
    enum class Container(val ext: String, val muxerFormat: Int, val needsRemux: Boolean, val ffmpegFmt: String) {
        MP4("mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, false, "mp4"),
        MOV("mov", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, true, "mov"),
        WEBM("webm", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, false, "webm"),
        MKV("mkv", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, true, "matroska"),
        AVI("avi", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, true, "avi");
    }

    sealed class Progress {
        data class Pct(val pct: Int, val elapsedMs: Long = 0, val etaMs: Long = 0) : Progress()
        data class Status(val msg: String) : Progress()
        data class Done(val outPath: String) : Progress()
        data class Error(val msg: String) : Progress()
    }

    companion object {
        private const val TAG = "Transcoder"
        private const val TIMEOUT_US = 10000L
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    fun transcode() {
        val startTime = System.currentTimeMillis()
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var egl: EglCore? = null
        var renderer: TextureRenderer? = null
        var muxer: MediaMuxer? = null
        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var muxerStarted = false

        // Temporary MP4 file used when the requested container needs remux (MOV/MKV/AVI)
        val tmpMp4 = if (container.needsRemux)
            File(outputFile.parentFile, ".tmp_${outputFile.nameWithoutExtension}.mp4")
        else outputFile

        // Pending encoded audio packets waiting for muxer to start
        val audioQueue = LinkedBlockingQueue<Pair<ByteArray, MediaCodec.BufferInfo>>(1024)
        var audioFormat: MediaFormat? = null
        var audioDone = false

        try {
            listener(Progress.Status("[1/6] 打开视频文件..."))
            extractor = MediaExtractor()
            extractor.setDataSource(input.fileDescriptor)

            var videoTrack = -1
            var audioTrack = -1
            var inFmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i; inFmt = f
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i
                }
            }
            check(videoTrack >= 0) { "未找到视频轨道" }
            extractor.selectTrack(videoTrack)

            val inW = inFmt!!.getInteger(MediaFormat.KEY_WIDTH)
            val inH = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
                inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val durSec = durationUs / 1_000_000
            listener(Progress.Status("  输入: ${inW}x${inH}, 时长 ${durSec}s"))
            listener(Progress.Status("  输出: ${outWidth}x${outHeight} @ ${frameRate}fps, $bitrateBps bps"))
            listener(Progress.Status("  编码: $videoMime, 容器: $container"))
            Log.i(TAG, "in ${inW}x$inH -> out ${outWidth}x$outHeight @ ${frameRate}fps, $bitrateBps bps, mime=$videoMime, container=$container")

            // ---- encoder ----
            listener(Progress.Status("[2/6] 配置硬件编码器..."))
            val encFmt = MediaFormat.createVideoFormat(videoMime, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(videoMime)
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface: Surface = encoder.createInputSurface()
            encoder.start()

            // ---- EGL + renderer ----
            listener(Progress.Status("[3/6] 初始化 GPU 渲染..."))
            renderer = TextureRenderer()
            val texId = renderer.getTextureId()
            renderer.init()
            egl = EglCore()
            egl.init(encSurface, texId)
            egl.setSurfaceTextureSize(inW, inH)

            // ---- decoder ----
            listener(Progress.Status("[4/6] 启动硬件解码器..."))
            decoder = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFmt, egl.inputSurface, null, 0)
            decoder.start()

            // ---- audio thread ----
            var inAudioFmt: MediaFormat? = null
            if (audioTrack >= 0) {
                inAudioFmt = extractor.getTrackFormat(audioTrack)
                listener(Progress.Status("  音频轨道: ${inAudioFmt?.getString(MediaFormat.KEY_MIME)}, ${inAudioFmt?.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz"))
            } else {
                listener(Progress.Status("  无音频轨道"))
            }
            val audioThread = Thread {
                try {
                    if (inAudioFmt != null) {
                        pumpAudio(extractor!!, audioTrack, inAudioFmt, container) { bytes, info, fmt ->
                            if (audioFormat == null) audioFormat = fmt
                            audioQueue.put(bytes to info)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "audio pump failed", t)
                } finally {
                    audioDone = true
                }
            }.also { it.start() }

            // ---- muxer (created lazily once video format is known) ----
            // Video pump
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var videoEos = false
            var lastPct = -1

            // ---- muxer start ----
            listener(Progress.Status("[5/6] 开始写入视频..."))
            val muxerStartTime = System.currentTimeMillis()

            while (!cancelled) {
                // Feed decoder
                if (!sawInputEos) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder
                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outIndexChanged(outIdx) -> {
                        val fmt = decoder.outputFormat
                        Log.i(TAG, "decoder format: $fmt")
                        listener(Progress.Status("  解码器输出: ${fmt.getString(MediaFormat.KEY_MIME)}, ${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}"))
                    }
                    outIdx >= 0 -> {
                        val render = info.size > 0
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            egl.awaitNewFrame(1000)
                            val m = egl.getTransformMatrix()
                            renderer.draw(m, inW, inH, outWidth, outHeight, scaleMode)
                            egl.swapBuffers()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoder.signalEndOfInputStream()
                            videoEos = true
                        }
                        if (durationUs > 0 && info.presentationTimeUs in 1 until durationUs) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                val elapsed = System.currentTimeMillis() - startTime
                                val eta = if (pct > 2) elapsed * (100 - pct) / pct else 0
                                listener(Progress.Pct(pct.coerceIn(0, 100), elapsed, eta))
                            }
                        }
                    }
                }

                // Drain encoder
                while (true) {
                    val encIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        encIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.i(TAG, "encoder format: ${encoder.outputFormat}")
                            listener(Progress.Status("  编码器输出格式: ${encoder.outputFormat}"))
                            if (muxer == null) {
                                muxer = MediaMuxer(tmpMp4.absolutePath, container.muxerFormat)
                            }
                            if (videoTrackIdx < 0) videoTrackIdx = muxer!!.addTrack(encoder.outputFormat)
                            // try start if audio also known
                            maybeStartMuxer(muxer, videoTrackIdx, audioTrackIdx, audioFormat) { started ->
                                muxerStarted = started
                            }
                            if (audioFormat != null && audioTrackIdx < 0) {
                                audioTrackIdx = muxer!!.addTrack(audioFormat!!)
                                maybeStartMuxer(muxer, videoTrackIdx, audioTrackIdx, audioFormat) { started ->
                                    muxerStarted = started
                                }
                            }
                        }
                        encIdx >= 0 -> {
                            val ob = encoder.getOutputBuffer(encIdx)
                            if (info.size > 0 && ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                if (muxerStarted) muxer!!.writeSampleData(videoTrackIdx, ob, info)
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }

                // Flush audio packets
                if (muxerStarted && muxer != null) {
                    while (true) {
                        val pair = audioQueue.poll() ?: break
                        val (bytes, aInfo) = pair
                        muxer.writeSampleData(audioTrackIdx, ByteBuffer.wrap(bytes), aInfo)
                    }
                }

                if (videoEos && audioDone && audioQueue.isEmpty()) break
            }

            if (cancelled) {
                listener(Progress.Error("已取消"))
                tmpMp4.delete()
                return
            }

            listener(Progress.Status("结束封装..."))
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            muxer?.release()
            muxer = null

            // Remux if needed (MOV/MKV/AVI)
            if (container.needsRemux && tmpMp4.exists()) {
                listener(Progress.Status("[6/6] 转换容器为 .${container.ext}..."))
                val ok = Remuxer.remux(tmpMp4, outputFile, container)
                if (!ok) throw RuntimeException("容器转换失败（$container）")
                tmpMp4.delete()
            }

            val totalSec = (System.currentTimeMillis() - startTime) / 1000
            listener(Progress.Status("完成！总耗时 ${totalSec}s"))
            listener(Progress.Done(outputFile.absolutePath))
        } catch (t: Throwable) {
            Log.e(TAG, "transcode failed", t)
            listener(Progress.Error(t.message ?: t.toString()))
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { egl?.release() } catch (_: Exception) {}
            try { renderer?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
            if (tmpMp4.exists() && tmpMp4 != outputFile) tmpMp4.delete()
        }
    }

    private fun outIndexChanged(idx: Int) = idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED

    private fun maybeStartMuxer(
        muxer: MediaMuxer?,
        videoIdx: Int,
        audioIdx: Int,
        audioFmt: MediaFormat?,
        setStarted: (Boolean) -> Unit
    ) {
        if (muxer == null || videoIdx < 0) return
        if (audioFmt != null && audioIdx < 0) return  // wait for audio track
        // start (audio may be absent)
        try {
            muxer.start()
            setStarted(true)
            Log.i(TAG, "muxer started, videoIdx=$videoIdx audioIdx=$audioIdx")
        } catch (e: Exception) {
            Log.e(TAG, "muxer start failed", e)
        }
    }

    private fun pumpAudio(
        extractor: MediaExtractor,
        audioTrack: Int,
        inFmt: MediaFormat,
        container: Container,
        onPacket: (ByteArray, MediaCodec.BufferInfo, MediaFormat) -> Unit
    ) {
        extractor.selectTrack(audioTrack)
        val sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val outMime = if (container == Container.WEBM)
            MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC

        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, null, null, 0)
        val enc = MediaCodec.createEncoderByType(outMime)
        val encFmt = MediaFormat.createAudioFormat(outMime, sampleRate, channels).apply {
            if (outMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        dec.start(); enc.start()

        val info = MediaCodec.BufferInfo()
        var decEos = false
        var encEos = false
        var encFmtNotified = false

        try {
            while (!encEos) {
                if (!decEos) {
                    val inIdx = dec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val pcm = dec.getOutputBuffer(outIdx)!!
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        val copy = ByteArray(info.size)
                        pcm.get(copy)
                        var inEnc = enc.dequeueInputBuffer(10000)
                        while (inEnc < 0) inEnc = enc.dequeueInputBuffer(10000)
                        val eb = enc.getInputBuffer(inEnc)!!
                        eb.clear(); eb.put(copy)
                        val flags = if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        enc.queueInputBuffer(inEnc, 0, copy.size, info.presentationTimeUs, flags)
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                }

                while (true) {
                    val eIdx = enc.dequeueOutputBuffer(info, 10000)
                    when {
                        eIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!encFmtNotified) {
                                onPacket(ByteArray(0), MediaCodec.BufferInfo().apply { flags = -1 }, enc.outputFormat)
                                encFmtNotified = true
                            }
                        }
                        eIdx >= 0 -> {
                            if (info.size > 0) {
                                val ob = enc.getOutputBuffer(eIdx)!!
                                val bytes = ByteArray(info.size)
                                ob.position(info.offset)
                                ob.get(bytes)
                                onPacket(bytes, info, enc.outputFormat)
                            }
                            enc.releaseOutputBuffer(eIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encEos = true; break
                            }
                        }
                    }
                }
            }
        } finally {
            try { dec.stop() } catch (_: Exception) {}
            try { enc.stop() } catch (_: Exception) {}
            dec.release(); enc.release()
        }
    }
}
