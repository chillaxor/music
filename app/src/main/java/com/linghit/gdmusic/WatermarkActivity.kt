package com.linghit.gdmusic

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 图片加水印页（独立工具卡入口）。
 *
 * 流程：选图(GetContent) → ImagePrep.decodeSampled 限长边 2000 防 OOM
 *      → WatermarkEngine.apply 斜铺水印 → ImageView 实时预览（节流重绘）
 *      → 保存到相册(MediaStore，免存储权限) / 分享(FileProvider)。
 *
 * 参数：文字、角度(-45~0)、透明度、字号、疏密 全部实时可调；颜色白/黑可切换。
 */
class WatermarkActivity : AppCompatActivity() {

    private var srcBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null

    private lateinit var preview: ImageView
    private lateinit var textInput: TextInputEditText
    private lateinit var angleBar: SeekBar
    private lateinit var alphaBar: SeekBar
    private lateinit var sizeBar: SeekBar
    private lateinit var densityBar: SeekBar
    private lateinit var angleVal: TextView
    private lateinit var alphaVal: TextView
    private lateinit var sizeVal: TextView
    private lateinit var densityVal: TextView
    private lateinit var blackSwitch: Switch
    private lateinit var saveBtn: Button
    private lateinit var shareBtn: Button

    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderRunnable = Runnable { doRender() }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = ImagePrep.decodeSampled(this, uri, 2000)
        if (bmp == null) {
            Toast.makeText(this, "读不到这张图", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        srcBitmap?.recycle()
        srcBitmap = bmp
        doRender()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watermark)

        preview = findViewById(R.id.preview)
        textInput = findViewById(R.id.textInput)
        angleBar = findViewById(R.id.angleBar)
        alphaBar = findViewById(R.id.alphaBar)
        sizeBar = findViewById(R.id.sizeBar)
        densityBar = findViewById(R.id.densityBar)
        angleVal = findViewById(R.id.angleVal)
        alphaVal = findViewById(R.id.alphaVal)
        sizeVal = findViewById(R.id.sizeVal)
        densityVal = findViewById(R.id.densityVal)
        blackSwitch = findViewById(R.id.blackSwitch)
        saveBtn = findViewById(R.id.saveBtn)
        shareBtn = findViewById(R.id.shareBtn)

        findViewById<Button>(R.id.pickBtn).setOnClickListener { pickLauncher.launch("image/*") }

        val onSlider = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { syncLabels(); scheduleRender() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { doRender() }
        }
        angleBar.setOnSeekBarChangeListener(onSlider)
        alphaBar.setOnSeekBarChangeListener(onSlider)
        sizeBar.setOnSeekBarChangeListener(onSlider)
        densityBar.setOnSeekBarChangeListener(onSlider)

        textInput.setOnEditorActionListener { _, _, _ -> syncLabels(); scheduleRender(); false }
        textInput.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) { syncLabels(); scheduleRender() }
        }
        blackSwitch.setOnCheckedChangeListener { _, _ -> scheduleRender() }

        saveBtn.setOnClickListener { saveToGallery() }
        shareBtn.setOnClickListener { share() }

        syncLabels()
        // 进来先弹选图；用户取消则停留空预览
        pickLauncher.launch("image/*")
    }

    private fun syncLabels() {
        val angle = angleBar.progress - 45
        angleVal.text = when {
            angle == 0 -> "角度：水平"
            angle < 0 -> "角度：向左 ${-angle}°"
            else -> "角度：向右 $angle°"
        }
        alphaVal.text = "透明度：${alphaBar.progress}%"
        sizeVal.text = when {
            sizeBar.progress < 33 -> "字号：小"
            sizeBar.progress < 66 -> "字号：中"
            else -> "字号：大"
        }
        densityVal.text = when {
            densityBar.progress < 33 -> "疏密：稀"
            densityBar.progress < 66 -> "疏密：中"
            else -> "疏密：密"
        }
    }

    private fun buildConfig() = WatermarkEngine.Config(
        text = textInput.text.toString(),
        angleDeg = (angleBar.progress - 45).toFloat(),
        alpha = (alphaBar.progress * 255 / 100),
        sizeRatio = 0.02f + sizeBar.progress / 100f * 0.08f,
        density = 0.5f + densityBar.progress / 100f * 1.5f,
        color = if (blackSwitch.isChecked) Color.BLACK else Color.WHITE,
        shadow = true
    )

    /** 节流重绘：拖动滑块时最多每 80ms 画一次，避免高频分配大位图 */
    private fun scheduleRender() {
        renderHandler.removeCallbacks(renderRunnable)
        renderHandler.postDelayed(renderRunnable, 80)
    }

    private fun doRender() {
        val src = srcBitmap ?: return
        val bmp = WatermarkEngine.apply(src, buildConfig())
        previewBitmap?.recycle()
        previewBitmap = bmp
        preview.setImageBitmap(bmp)
        saveBtn.isEnabled = true
        shareBtn.isEnabled = true
    }

    /** 取当前水印结果：优先用已渲染的预览图 */
    private fun currentOutput(): Bitmap? {
        if (previewBitmap != null) return previewBitmap
        val src = srcBitmap ?: return null
        previewBitmap = WatermarkEngine.apply(src, buildConfig())
        return previewBitmap
    }

    private fun saveToGallery() {
        val bmp = currentOutput() ?: return
        val name = "watermark_${System.currentTimeMillis()}.png"
        // 把加水印的参数写成 sidecar，供「去水印」页自动圈出本App水印（无需手涂）
        writeWatermarkMeta(name, buildConfig())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/从前慢")
            }
        }
        val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: run { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); return }
        try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 把加水印参数落 sidecar（filesDir/wm_meta/<图名>.json），供去水印页反推覆盖区 */
    private fun writeWatermarkMeta(fileName: String, cfg: WatermarkEngine.Config) {
        runCatching {
            val base = fileName.substringBeforeLast('.', fileName)
            val dir = File(filesDir, "wm_meta").apply { mkdirs() }
            val json = JSONObject().apply {
                put("text", cfg.text)
                put("angle", cfg.angleDeg)
                put("alpha", cfg.alpha)
                put("sizeRatio", cfg.sizeRatio)
                put("density", cfg.density)
                put("color", cfg.color)
                put("shadow", cfg.shadow)
            }
            File(dir, "$base.json").writeText(json.toString())
        }
    }

    private fun share() {
        val bmp = currentOutput() ?: return
        val dir = File(cacheDir, "watermark_share").apply { mkdirs() }
        val file = File(dir, "wm_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享图片"))
    }

    override fun onDestroy() {
        super.onDestroy()
        renderHandler.removeCallbacks(renderRunnable)
        srcBitmap?.recycle()
        previewBitmap?.recycle()
        srcBitmap = null
        previewBitmap = null
    }
}
