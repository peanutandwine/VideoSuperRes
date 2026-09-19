package com.videosr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File

class TranscodeService : Service() {
    companion object {
        const val ACTION_PROGRESS = "com.videosr.PROGRESS"
        const val ACTION_STATUS = "com.videosr.STATUS"
        const val ACTION_DONE = "com.videosr.DONE"
        const val ACTION_ERROR = "com.videosr.ERROR"
        const val EXTRA_PCT = "pct"
        const val EXTRA_ELAPSED = "elapsed"
        const val EXTRA_ETA = "eta"
        const val EXTRA_MSG = "msg"
        const val EXTRA_PATH = "path"

        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_OUT_W = "out_w"
        const val EXTRA_OUT_H = "out_h"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MIME = "mime"
        const val EXTRA_CONTAINER = "container"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MODEL = "model"
        const val EXTRA_OUT_PATH = "out_path"

        const val CHANNEL_ID = "transcode"
        const val NOTI_ID = 1
    }

    private var transcoder: Transcoder? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification(0, "准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val uri = intent.data ?: run {
            notify(Intent(ACTION_ERROR).putExtra(EXTRA_MSG, "没有传入文件 URI"))
            stopSelf(); return START_NOT_STICKY
        }
        val outW = intent.getIntExtra(EXTRA_OUT_W, 1280)
        val outH = intent.getIntExtra(EXTRA_OUT_H, 720)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "video/avc"
        val containerName = intent.getStringExtra(EXTRA_CONTAINER) ?: "MP4"
        val mode = intent.getIntExtra(EXTRA_MODE, TextureRendererMode.BILINEAR)
        val modelFile = intent.getStringExtra(EXTRA_MODEL) ?: "realesr_general_x4v3.tflite"

        val container = try { Transcoder.Container.valueOf(containerName) }
                       catch (_: Throwable) { Transcoder.Container.MP4 }

        val outPath = intent.getStringExtra(EXTRA_OUT_PATH)
        val outFile = outPath?.let { File(it) } ?: File(getExternalFilesDir(null), "output_${System.currentTimeMillis()}.${container.ext}")

        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    handler.post {
                        notify(Intent(ACTION_ERROR).putExtra(EXTRA_MSG, "无法打开文件：$uri"))
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    }
                    return@Thread
                }
                transcoder = Transcoder(pfd, outFile, outW, outH, bitrate, fps, mime, container, mode) { progress ->
                    when (progress) {
                        is Transcoder.Progress.Pct -> {
                            handler.post {
                                notify(Intent(ACTION_PROGRESS)
                                    .putExtra(EXTRA_PCT, progress.pct)
                                    .putExtra(EXTRA_ELAPSED, progress.elapsedMs)
                                    .putExtra(EXTRA_ETA, progress.etaMs))
                                startForeground(NOTI_ID, buildNotification(progress.pct, "处理中 ${progress.pct}%"))
                            }
                        }
                        is Transcoder.Progress.Status -> {
                            handler.post {
                                notify(Intent(ACTION_STATUS).putExtra(EXTRA_MSG, progress.msg))
                                startForeground(NOTI_ID, buildNotification(-1, progress.msg))
                            }
                        }
                        is Transcoder.Progress.Done -> {
                            handler.post {
                                android.media.MediaScannerConnection.scanFile(
                                    this@TranscodeService,
                                    arrayOf(progress.outPath),
                                    arrayOf("video/*"),
                                    null
                                )
                                notify(Intent(ACTION_DONE).putExtra(EXTRA_PATH, progress.outPath))
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                        is Transcoder.Progress.Error -> {
                            handler.post {
                                notify(Intent(ACTION_ERROR).putExtra(EXTRA_MSG, progress.msg))
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                }
                transcoder!!.transcode()
            } catch (t: Throwable) {
                handler.post {
                    notify(Intent(ACTION_ERROR).putExtra(EXTRA_MSG, "启动失败: ${t.message}"))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        transcoder?.cancel()
        super.onDestroy()
    }

    private fun notify(i: Intent) = sendBroadcast(i)

    private fun buildNotification(pct: Int, text: String): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频处理中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
        if (pct >= 0) b.setProgress(100, pct, false)
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "转码进度", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}

/** Mirror of TextureRenderer modes to avoid tight coupling in service extras. */
object TextureRendererMode {
    const val BILINEAR = 0
    const val LANCZOS_DOWN = 1
    const val BICUBIC_UP = 2
    const val AI = 3
}
