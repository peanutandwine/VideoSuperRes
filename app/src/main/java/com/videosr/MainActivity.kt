package com.videosr

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import java.io.File
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.chip.Chip
import com.videosr.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var inputUri: Uri? = null
    private var inputW = 0
    private var inputH = 0
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("settings", MODE_PRIVATE)
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { onVideoPicked(it) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                TranscodeService.ACTION_PROGRESS -> {
                    val pct = i.getIntExtra(TranscodeService.EXTRA_PCT, 0)
                    val elapsed = i.getLongExtra(TranscodeService.EXTRA_ELAPSED, 0)
                    val eta = i.getLongExtra(TranscodeService.EXTRA_ETA, 0)
                    b.progress.visibility = android.view.View.VISIBLE
                    b.progress.progress = pct
                    b.tvStatus.text = "处理中 $pct%"
                    b.tvEta.text = formatEta(elapsed, eta)
                    appendLog("  [${pct}%] ${formatEta(elapsed, eta)}")
                }
                TranscodeService.ACTION_STATUS -> {
                    val msg = i.getStringExtra(TranscodeService.EXTRA_MSG) ?: ""
                    appendLog(msg)
                    b.tvStatus.text = msg
                }
                TranscodeService.ACTION_STATUS -> {
                    b.tvStatus.text = i.getStringExtra(TranscodeService.EXTRA_MSG)
                }
                TranscodeService.ACTION_DONE -> {
                    val path = i?.getStringExtra(TranscodeService.EXTRA_PATH)
                    b.progress.visibility = android.view.View.GONE
                    b.tvStatus.setTextColor(Color.parseColor("#2e7d32"))
                    b.tvStatus.text = "完成！输出文件：$path"
                    b.tvEta.text = ""
                    b.btnStart.isEnabled = inputUri != null
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("处理完成")
                        .setMessage("输出文件：$path\n\n是否在文件管理器中查看？")
                        .setPositiveButton("查看") { _, _ ->
                            val view = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse("file://$path"), "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { startActivity(view) } catch (_: Exception) {}
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
                TranscodeService.ACTION_ERROR -> {
                    b.progress.visibility = android.view.View.GONE
                    b.tvStatus.setTextColor(Color.RED)
                    val msg = "错误：${i?.getStringExtra(TranscodeService.EXTRA_MSG)}"
                    b.tvStatus.text = msg
                    appendLog("!! $msg")
                    b.btnStart.isEnabled = inputUri != null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(findViewById(R.id.toolbar))

        requestPermissions()

        b.btnPick.setOnClickListener {
            // ACTION_GET_CONTENT shows ALL apps that can open video/* (Gallery, Files, MT Manager, etc.)
            val base = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            val chooser = Intent.createChooser(base, "选择视频来源")
            pickVideo.launch(chooser)
        }

        b.sliderBitrate.addOnChangeListener { _, v, _ ->
            b.etBitrate.setText(String.format("%.1f", v))
        }
        b.etBitrate.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                b.etBitrate.text.toString().toFloatOrNull()?.let {
                    if (it in 0.5f..80f) b.sliderBitrate.value = it
                }
            }
        }

        b.sliderFps.addOnChangeListener { _, v, _ ->
            b.etFps.setText(v.toInt().toString())
        }
        b.etFps.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                b.etFps.text.toString().toIntOrNull()?.let {
                    if (it in 15..60) b.sliderFps.value = it.toFloat()
                }
            }
        }

        b.btnStart.setOnClickListener { startTranscode() }

        // NPU detection
        updateNpuUi()

        // AI model spinner
        val models = arrayOf(
            "realesr_general_x4v3 (轻量·通用4x, 3.4MB)",
            "realesr_animevideov3 (动漫视频4x, 1.2MB)",
            "realesr_x4plus_anime_6b (动漫图像4x, 8.6MB)",
            "realesr_x2plus (通用2x, 33MB)",
            "realesr_x4plus (通用4x, 33MB)"
        )
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        b.spinnerModel.adapter = adapter

        val filter = IntentFilter().apply {
            addAction(TranscodeService.ACTION_PROGRESS)
            addAction(TranscodeService.ACTION_STATUS)
            addAction(TranscodeService.ACTION_DONE)
            addAction(TranscodeService.ACTION_ERROR)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun updateNpuUi() {
        val hasNpu = NpuChecker.hasNpu(this)
        val force = prefs.getBoolean("force_ai", false)
        b.chipAi.isEnabled = hasNpu || force
        when {
            hasNpu -> {
                b.tvNpuStatus.text = "已检测到 NPU，AI 超分可用"
                b.tvNpuStatus.setTextColor(Color.parseColor("#2e7d32"))
            }
            force -> {
                b.tvNpuStatus.text = "警告：未检测到 NPU，AI 超分将回退到 CPU，速度极慢"
                b.tvNpuStatus.setTextColor(Color.RED)
            }
            else -> {
                b.tvNpuStatus.text = "未检测到 NPU，AI 超分已禁用（可在设置中强制开启）"
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_settings) {
            val hasNpu = NpuChecker.hasNpu(this)
            val current = prefs.getBoolean("force_ai", false)
            AlertDialog.Builder(this)
                .setTitle("设置")
                .setMessage(
                    if (hasNpu) "本设备支持 NPU，AI 超分默认可用。"
                    else "本设备未检测到 NPU。是否强制开启 AI 超分？\n\n警告：将使用 CPU 推理，速度极慢，仅适合短视频。"
                )
                .setPositiveButton(if (current) "关闭强制 AI" else "强制开启 AI") { _, _ ->
                    prefs.edit().putBoolean("force_ai", !current).apply()
                    updateNpuUi()
                }
                .setNegativeButton("取消", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onVideoPicked(uri: Uri) {
        // Take persistable permission so Service can open the file later
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* some apps don't support persistable, it's fine */ }
        inputUri = uri
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            inputW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            inputH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val durSec = dur / 1000
            b.tvInputInfo.text = "已选择：${inputW}x${inputH}, ${durSec / 60}m${durSec % 60}s"
            b.btnStart.isEnabled = true
        } catch (t: Throwable) {
            b.tvInputInfo.text = "读取视频信息失败：${t.message}"
        } finally {
            retriever.release()
        }
    }

    private fun selectedMime(): String = when {
        b.chipH264.isChecked -> MediaFormat.MIMETYPE_VIDEO_AVC
        b.chipH265.isChecked -> MediaFormat.MIMETYPE_VIDEO_HEVC
        b.chipVp9.isChecked -> MediaFormat.MIMETYPE_VIDEO_VP9
        b.chipAv1.isChecked -> MediaFormat.MIMETYPE_VIDEO_AV1
        else -> MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private fun selectedContainer(): Transcoder.Container = when {
        b.chipMp4.isChecked -> Transcoder.Container.MP4
        b.chipMov.isChecked -> Transcoder.Container.MOV
        b.chipWebm.isChecked -> Transcoder.Container.WEBM
        b.chipMkv.isChecked -> Transcoder.Container.MKV
        b.chipAvi.isChecked -> Transcoder.Container.AVI
        else -> Transcoder.Container.MP4
    }

    private fun selectedMode(): Int = when {
        b.chipDownscale.isChecked -> TextureRendererMode.LANCZOS_DOWN
        b.chipUpscale.isChecked -> TextureRendererMode.BICUBIC_UP
        b.chipAi.isChecked -> TextureRendererMode.AI
        else -> TextureRendererMode.BILINEAR
    }

    private fun selectedSize(): Pair<Int, Int> {
        val customW = b.etWidth.text.toString().toIntOrNull()
        if (customW != null && customW > 0) {
            val ratio = inputH.toFloat() / inputW.toFloat()
            val h = (customW * ratio).toInt() and 1.inv() // even
            return customW to h
        }
        val targetH = when {
            b.chipRes480.isChecked -> 480
            b.chipRes720.isChecked -> 720
            b.chipRes1080.isChecked -> 1080
            b.chipRes1440.isChecked -> 1440
            b.chipRes2160.isChecked -> 2160
            else -> 720
        }
        val ratio = inputW.toFloat() / inputH.toFloat()
        var w = (targetH * ratio).toInt()
        w = w and 1.inv()
        return w to targetH
    }

    private fun startTranscode() {
        val uri = inputUri ?: return
        val (w, h) = selectedSize()
        val bitrateMbps = b.etBitrate.text.toString().toFloatOrNull() ?: b.sliderBitrate.value
        val bitrate = (bitrateMbps * 1_000_000).toInt().coerceAtLeast(500_000)
        val fps = b.etFps.text.toString().toIntOrNull() ?: b.sliderFps.value.toInt()
        val mime = selectedMime()
        val container = selectedContainer()
        val mode = selectedMode()

        // Compatibility check
        val available = isCodecAvailable(mime)
        if (!available) {
            b.tvSupported.text = "警告：当前设备不支持 $mime 硬件编码，处理可能失败"
        } else {
            b.tvSupported.text = ""
        }

        // AVI only really supports H.264 video
        if (container == Transcoder.Container.AVI && mime != MediaFormat.MIMETYPE_VIDEO_AVC) {
            AlertDialog.Builder(this)
                .setTitle("容器不兼容")
                .setMessage("AVI 容器对 H.265/VP9/AV1 支持不佳，建议改用 MKV。是否仍要继续？")
                .setPositiveButton("继续") { _, _ -> doStart(uri, w, h, bitrate, fps, mime, container, mode) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doStart(uri, w, h, bitrate, fps, mime, container, mode)
    }

    private fun doStart(uri: Uri, w: Int, h: Int, bitrate: Int, fps: Int,
                        mime: String, container: Transcoder.Container, mode: Int) {
        val modelFiles = listOf(
            "realesr_general_x4v3.tflite",
            "realesr_animevideov3.tflite",
            "realesr_x4plus_anime_6b.tflite",
            "realesr_x2plus.tflite",
            "realesr_x4plus.tflite"
        )
        val selectedModel = modelFiles.getOrElse(b.spinnerModel.selectedItemPosition) { modelFiles[0] }

        val customName = b.etOutputName.text.toString().trim()
        val baseName = if (customName.isNotEmpty()) customName
                       else "output_${System.currentTimeMillis()}"
        val outFile = File(getExternalFilesDir(null), "$baseName.${container.ext}")

        b.progress.visibility = android.view.View.VISIBLE
        b.progress.progress = 0
        b.tvStatus.text = "准备中..."
        b.tvEta.text = "正在初始化..."
        b.tvLog.text = ""
        appendLog("=== 开始处理 ===")
        b.btnStart.isEnabled = false
        val svc = Intent(this, TranscodeService::class.java).apply {
            data = uri
            putExtra(TranscodeService.EXTRA_OUT_W, w)
            putExtra(TranscodeService.EXTRA_OUT_H, h)
            putExtra(TranscodeService.EXTRA_BITRATE, bitrate)
            putExtra(TranscodeService.EXTRA_FPS, fps)
            putExtra(TranscodeService.EXTRA_MIME, mime)
            putExtra(TranscodeService.EXTRA_CONTAINER, container.name)
            putExtra(TranscodeService.EXTRA_MODE, mode)
            putExtra(TranscodeService.EXTRA_MODEL, selectedModel)
            putExtra(TranscodeService.EXTRA_OUT_PATH, outFile.absolutePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun appendLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "[$timestamp] $msg\n"
        b.tvLog.append(line)
        b.logScroll.post { b.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun formatEta(elapsedMs: Long, etaMs: Long): String {
        val e = if (elapsedMs > 0) "已用 ${elapsedMs / 1000}s" else ""
        val r = if (etaMs > 1000) "预计剩余 ${etaMs / 1000}s" else ""
        return listOf(e, r).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun isCodecAvailable(mime: String): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                try {
                    info.isEncoder && info.getCapabilitiesForType(mime) != null
                } catch (_: Throwable) { false }
            }
        } catch (_: Throwable) { false }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
