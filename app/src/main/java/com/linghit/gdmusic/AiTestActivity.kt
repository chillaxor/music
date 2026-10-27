package com.linghit.gdmusic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 端侧 AI 验证页（一期开发用，非正式功能）。
 *
 * 目的：实测本机（荣耀 16GB）跑 Qwen3-0.6B / 1.7B / 4B 的中文输出质量与速度，
 * 决定正式版 AI 工坊用哪一档模型。
 *
 * 流程：选模型 → 下载/导入 .litertlm → 加载引擎 → 流式生成。
 * 全部走 AiEngine（LiteRT-LM），纯本地推理，不联网、不要 Key。
 */
class AiTestActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var modelRow: LinearLayout
    private lateinit var modelHint: TextView
    private lateinit var statusText: TextView
    private lateinit var timingText: TextView
    private lateinit var outputText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var systemInput: EditText
    private lateinit var userInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var loadBtn: Button
    private lateinit var generateBtn: Button

    private val chips = mutableListOf<TextView>()
    private var selected: ModelManager.ModelSpec = ModelManager.CATALOG[1]
    private var genJob: Job? = null

    /** 导入模型文件（SAF） */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        setStatus("导入中…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { ModelManager.importFromUri(this@AiTestActivity, uri) }
            r.onSuccess {
                setStatus("导入成功：${it.name}（${it.length() / 1024 / 1024}MB）")
                refreshHint()
            }.onFailure {
                setStatus("导入失败：${it.message}")
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_ai_test)

        modelRow = findViewById(R.id.modelRow)
        modelHint = findViewById(R.id.modelHint)
        statusText = findViewById(R.id.statusText)
        timingText = findViewById(R.id.timingText)
        outputText = findViewById(R.id.outputText)
        progress = findViewById(R.id.progress)
        systemInput = findViewById(R.id.systemInput)
        userInput = findViewById(R.id.userInput)
        downloadBtn = findViewById(R.id.downloadBtn)
        loadBtn = findViewById(R.id.loadBtn)
        generateBtn = findViewById(R.id.generateBtn)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        // 默认提示词：按公众号「第二人称编辑口吻 + 去 AI 味」套路写
        systemInput.setText(
            "你是「吃出24块鸡头米」公众号的编辑。第二人称口吻，面向读者讲清工具怎么用。\n" +
                "要求：不用破折号；不用冒号；不用「赋能/抓手/闭环/颗粒度」这类词；" +
                "长短句混排；口语化，不要书面腔。"
        )
        userInput.setText(
            "帮我写一段介绍「一个可以离线用 AI 帮你改写文案的小工具」的公众号开头，200 字左右。"
        )

        buildChips()
        refreshHint()

        findViewById<Button>(R.id.importBtn).setOnClickListener {
            // .litertlm 在部分文件管理器里识别不出后缀，放宽 MIME 过滤
            importLauncher.launch(arrayOf("*/*"))
        }

        downloadBtn.setOnClickListener { doDownload() }
        loadBtn.setOnClickListener { doLoad() }
        generateBtn.setOnClickListener { doGenerate() }

        findViewById<Button>(R.id.copyBtn).setOnClickListener {
            val txt = outputText.text.toString()
            if (txt.isBlank()) { toast("还没有输出"); return@setOnClickListener }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai", txt))
            toast("已复制")
        }
    }

    // ---------- 模型 chip ----------

    private fun buildChips() {
        modelRow.removeAllViews()
        chips.clear()
        val d = resources.displayMetrics.density
        ModelManager.CATALOG.forEach { spec ->
            val tv = TextView(this).apply {
                text = "${spec.displayName}\n${spec.approxSizeMb}MB"
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                setBackgroundResource(R.drawable.bg_model_chip)
                setOnClickListener {
                    selected = spec
                    syncChipStates()
                    refreshHint()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * d).toInt()
            modelRow.addView(tv, lp)
            chips.add(tv)
        }
        syncChipStates()
    }

    private fun syncChipStates() {
        ModelManager.CATALOG.forEachIndexed { i, spec ->
            val on = spec.id == selected.id
            chips[i].isSelected = on
            chips[i].setTextColor(
                if (on) resolveOnPrimary() else resolveTextSecondary()
            )
        }
    }

    private fun resolveOnPrimary(): Int =
        androidx.core.content.ContextCompat.getColor(this, R.color.on_primary)

    private fun resolveTextSecondary(): Int =
        androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)

    private fun refreshHint() {
        val installed = ModelManager.isInstalled(this, selected)
        val used = ModelManager.usedBytes(this) / 1024 / 1024
        modelHint.text = buildString {
            append(selected.note)
            append("\n")
            if (installed) {
                append("✅ 已安装")
            } else {
                append("⬇ 未安装（点「下载模型」自动拉取，或「导入文件」选本地 .litertlm）")
            }
            append("　已占用 ${used}MB")
        }
        loadBtn.isEnabled = installed
        downloadBtn.isEnabled = !installed
        generateBtn.isEnabled = AiEngine.isReady
    }

    // ---------- 下载 ----------

    private fun doDownload() {
        progress.visibility = View.VISIBLE
        progress.progress = 0
        downloadBtn.isEnabled = false
        setStatus("下载 ${selected.displayName} 中…")

        scope.launch(Dispatchers.IO) {
            val r = ModelManager.download(this@AiTestActivity, selected) { sum, total, pct ->
                main.post {
                    progress.progress = pct
                    if (total > 0) {
                        setStatus("下载中 $pct%　${sum / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                    }
                }
            }
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                downloadBtn.isEnabled = true
                r.onSuccess {
                    setStatus("下载完成：${it.name}（${it.length() / 1024 / 1024}MB）")
                    refreshHint()
                    toast("模型已下载")
                }.onFailure {
                    // 多源都失败时把每个源的失败原因都列出来，方便判断是网络还是文件名问题
                    setStatus("下载失败：${it.message}")
                }
            }
        }
    }

    // ---------- 加载引擎 ----------

    private fun doLoad() {
        val f = ModelManager.fileOf(this, selected)
        if (!f.exists()) { toast("模型文件不存在"); return }
        loadBtn.isEnabled = false
        setStatus("加载引擎中…（首次约 5–15 秒）")
        progress.visibility = View.VISIBLE
        progress.progress = 0

        scope.launch {
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    AiEngine.load(this@AiTestActivity, f.absolutePath, useGpu = true)
                }
            }
            val dt = System.currentTimeMillis() - t0
            progress.visibility = View.GONE
            r.onSuccess { backend ->
                setStatus("✅ 引擎就绪（后端 $backend，耗时 ${dt}ms）")
                generateBtn.isEnabled = true
            }.onFailure {
                setStatus("❌ 加载失败：${it.message}")
                loadBtn.isEnabled = true
            }
            refreshHint()
        }
    }

    // ---------- 生成 ----------

    private fun doGenerate() {
        val sys = systemInput.text.toString().trim()
        val usr = userInput.text.toString().trim()
        if (usr.isBlank()) { toast("请输入内容"); return }
        if (!AiEngine.isReady) { toast("请先加载引擎"); return }

        genJob?.cancel()
        outputText.text = ""
        timingText.text = "生成中…"
        generateBtn.isEnabled = false

        val t0 = System.currentTimeMillis()
        var firstTokenAt = 0L
        var chars = 0

        genJob = scope.launch {
            try {
                AiEngine.generate(this@AiTestActivity, sys, usr).collect { delta ->
                    if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                    chars += delta.length
                    outputText.append(delta)
                }
                val total = System.currentTimeMillis() - t0
                val ttft = if (firstTokenAt > 0) firstTokenAt - t0 else 0
                val sec = (total / 1000.0).coerceAtLeast(0.001)
                val tps = chars / sec
                timingText.text = "耗时 ${total}ms　首字 ${ttft}ms　输出 $chars 字　约 ${"%.1f".format(tps)} 字/秒"
            } catch (t: Throwable) {
                timingText.text = "生成失败：${t.message}"
            } finally {
                generateBtn.isEnabled = true
            }
        }
    }

    // ---------- 杂项 ----------

    private fun setStatus(s: String) { statusText.text = s }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        genJob?.cancel()
        scope.cancel()
        // 释放引擎，回收 native 内存（模型文件保留，下次直接复用）
        AiEngine.releaseBlocking()
    }
}
