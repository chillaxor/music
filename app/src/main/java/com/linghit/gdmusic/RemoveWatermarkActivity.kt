package com.linghit.gdmusic

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 图片去水印页（独立工具卡入口）。
 *
 * 流程：选图(GetContent) → ImagePrep.decodeSampled 限长边 2000 防 OOM
 *      → 若本App自己加的水印有 sidecar，则自动圈出覆盖区(免手涂)
 *      → 用户可手涂/橡皮微调 → 去除(后台跑 InpaintEngine) → 结果回灌画布可继续修
 *      → 保存到相册(MediaStore，免存储权限) / 分享(FileProvider)。
 */
class RemoveWatermarkActivity : AppCompatActivity() {

    private var currentSrc: Bitmap? = null
    private var working: Boolean = false

    private lateinit var brushView: com.linghit.gdmusic.BrushView
    private lateinit var sizeBar: SeekBar
    private lateinit var sizeVal: TextView
    private lateinit var eraserSwitch: Switch
    private lateinit var poissonSwitch: Switch
    private lateinit var removeBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var shareBtn: Button

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = ImagePrep.decodeSampled(this, uri, 2000)
        if (bmp == null) {
            Toast.makeText(this, "读不到这张图", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        currentSrc?.recycle()
        currentSrc = bmp
        brushView.setImage(bmp)
        applyBrush()
        removeBtn.isEnabled = true
        saveBtn.isEnabled = false
        shareBtn.isEnabled = false
        // 自加水印自动定位
        tryAutoLocate(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remove_watermark)

        brushView = findViewById(R.id.brushView)
        sizeBar = findViewById(R.id.sizeBar)
        sizeVal = findViewById(R.id.sizeVal)
        eraserSwitch = findViewById(R.id.eraserSwitch)
        poissonSwitch = findViewById(R.id.poissonSwitch)
        removeBtn = findViewById(R.id.removeBtn)
        saveBtn = findViewById(R.id.saveBtn)
        shareBtn = findViewById(R.id.shareBtn)

        findViewById<Button>(R.id.pickBtn).setOnClickListener { pickLauncher.launch("image/*") }
        findViewById<Button>(R.id.undoBtn).setOnClickListener { brushView.undo() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener { brushView.clearMask() }

        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyBrush()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        eraserSwitch.setOnCheckedChangeListener { _, on -> brushView.setErasing(on) }

        removeBtn.setOnClickListener { doRemove() }
        saveBtn.setOnClickListener { saveToGallery() }
        shareBtn.setOnClickListener { share() }

        applyBrush()
        pickLauncher.launch("image/*")
    }

    private fun applyBrush() {
        val src = currentSrc ?: return
        val minEdge = min(src.width, src.height)
        val px = (sizeBar.progress / 100f * minEdge * 0.08f + 6f).toInt().coerceAtLeast(6)
        brushView.setBrushRadius(px)
        sizeVal.text = when {
            sizeBar.progress < 33 -> "画笔大小：小"
            sizeBar.progress < 66 -> "画笔大小：中"
            else -> "画笔大小：大"
        }
    }

    /** 本App自己加的水印：按文件名查 sidecar Config → 反推覆盖区自动圈出 */
    private fun tryAutoLocate(uri: Uri) {
        val name = queryDisplayName(uri) ?: return
        val base = name.substringBeforeLast('.', name)
        val file = File(filesDir, "wm_meta/$base.json")
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val cfg = WatermarkEngine.Config(
                text = json.optString("text", "从前慢"),
                angleDeg = json.optDouble("angle", -28.0).toFloat(),
                alpha = json.optInt("alpha", 110),
                sizeRatio = json.optDouble("sizeRatio", 0.045).toFloat(),
                density = json.optDouble("density", 1.0).toFloat(),
                color = json.optInt("color", android.graphics.Color.WHITE),
                shadow = json.optBoolean("shadow", true)
            )
            val src = currentSrc ?: return
            val mask = WatermarkMask.rebuild(cfg, src.width, src.height)
            brushView.setInitialMask(mask)
            Toast.makeText(this, "已自动圈出本App水印，可微调后去除", Toast.LENGTH_SHORT).show()
        }.onFailure {
            // sidecar 损坏不影响手动涂抹
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private fun doRemove() {
        val src = currentSrc ?: return
        if (working) return
        val mask = brushView.getMask() ?: return
        if (!brushView.hasMask()) {
            Toast.makeText(this, "先涂抹要去掉的水印", Toast.LENGTH_SHORT).show()
            return
        }
        working = true
        removeBtn.isEnabled = false
        Toast.makeText(this, "处理中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.Default) {
            val usePoisson = poissonSwitch.isChecked
            val result = if (LaMaInpaint.isAvailable(this@RemoveWatermarkActivity)) {
                LaMaInpaint.run(this@RemoveWatermarkActivity, src, mask)
                    ?: InpaintEngine.remove(src, mask, featherPx = 5, usePoisson = usePoisson)
            } else {
                InpaintEngine.remove(src, mask, featherPx = 5, usePoisson = usePoisson)
            }
            withContext(Dispatchers.Main) {
                currentSrc?.recycle()
                currentSrc = result
                brushView.setImage(result)
                applyBrush()
                saveBtn.isEnabled = true
                shareBtn.isEnabled = true
                removeBtn.isEnabled = true
                working = false
                Toast.makeText(this@RemoveWatermarkActivity, "已去掉，可继续修或保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToGallery() {
        val bmp = currentSrc ?: return
        val name = "removewm_${System.currentTimeMillis()}.png"
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

    private fun share() {
        val bmp = currentSrc ?: return
        val dir = File(cacheDir, "removewm_share").apply { mkdirs() }
        val file = File(dir, "rw_${System.currentTimeMillis()}.png")
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
        currentSrc?.recycle()
        currentSrc = null
    }
}
