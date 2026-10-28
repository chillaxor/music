package com.linghit.gdmusic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
 * 问一句：端侧三段式检索 + 可切换的回答引擎。
 *
 * 交互：选引擎（端侧 / 云端）→ 输入问题或链接 → 「问一句」→
 *   搜索/抓取/整理（由 QnaRetrieval 完成）→ 端侧 Qwen3 或云端大模型读材料作答。
 * 引擎选择持久化在 QnaEngine；云端配置在 CloudConfigStore，未配置时给清晰引导。
 */
class QnaActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var questionInput: EditText
    private lateinit var stageText: TextView
    private lateinit var outputText: TextView
    private lateinit var providerState: TextView
    private lateinit var sourcesRow: LinearLayout
    private lateinit var sourcesLabel: TextView
    private lateinit var btnLocal: Button
    private lateinit var btnCloud: Button
    private lateinit var askBtn: Button

    private var genJob: Job? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_qna)

        questionInput = findViewById(R.id.questionInput)
        stageText = findViewById(R.id.stageText)
        outputText = findViewById(R.id.outputText)
        providerState = findViewById(R.id.providerState)
        sourcesRow = findViewById(R.id.sourcesRow)
        sourcesLabel = findViewById(R.id.sourcesLabel)
        btnLocal = findViewById(R.id.btnLocal)
        btnCloud = findViewById(R.id.btnCloud)
        askBtn = findViewById(R.id.askBtn)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { openCloudSettings() }
        // 顶栏「🧠 模型」：跳到端侧 AI 验证页（下载/加载/导入 .litertlm 模型）
        findViewById<TextView>(R.id.modelBtn).setOnClickListener { openModelPage() }
        // 引擎状态行也可点：显示"未加载"时这是最顺手的入口
        providerState.setOnClickListener { openModelPage() }

        btnLocal.setOnClickListener { selectProvider("local") }
        btnCloud.setOnClickListener { selectProvider("cloud") }

        askBtn.setOnClickListener { doAsk() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            questionInput.setText("")
            outputText.text = "（回答会显示在这里）"
            sourcesRow.removeAllViews()
            sourcesLabel.visibility = View.GONE
            stageText.text = ""
        }
        findViewById<Button>(R.id.copyBtn).setOnClickListener {
            val t = outputText.text.toString()
            if (t.isBlank() || t == "（回答会显示在这里）") { toast("还没有回答"); return@setOnClickListener }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qna", t))
            toast("已复制")
        }

        syncProviderUi()
    }

    private fun selectProvider(id: String) {
        QnaEngine.setActive(this, id)
        syncProviderUi()
    }

    private fun syncProviderUi() {
        val p = QnaEngine.active(this)
        val local = p.id == "local"
        btnLocal.isSelected = local
        btnCloud.isSelected = !local
        providerState.text = if (local) {
            if (AiEngine.isReady) "端侧：模型已就绪" else "端侧：模型未加载（点右上角「🧠 模型」下载并加载，问答时也会自动尝试）"
        } else {
            val cfg = CloudConfigStore.load(this)
            if (cfg.isValid()) "云端：已配置（${cfg.model}）" else "云端：未配置（点右上角「云端设置」填写 endpoint / Key / 模型）"
        }
    }

    private fun doAsk() {
        val q = questionInput.text.toString().trim()
        if (q.isBlank()) { toast("请输入问题或链接"); return }
        val provider = QnaEngine.active(this)

        genJob?.cancel()
        outputText.text = ""
        sourcesRow.removeAllViews()
        sourcesLabel.visibility = View.GONE
        stageText.text = "准备中…"
        askBtn.isEnabled = false

        scope.launch {
            // 本地模型需先加载（已装则自动加载，未装则由 ask 提示去下载）
            if (provider.id == "local" && !AiEngine.isReady) {
                stageText.text = "加载端侧模型中…（首次约 5–15 秒）"
                runCatching { withContext(Dispatchers.IO) { provider.prepare(this@QnaActivity) } }
            }
            try {
                provider.ask(this@QnaActivity, q).collect { ev ->
                    when (ev) {
                        is QnaEvent.Stage -> {
                            stageText.text = ev.text
                            stageText.setTextColor(attrColor(android.R.attr.colorPrimary))
                        }
                        is QnaEvent.Delta -> outputText.append(ev.text)
                        is QnaEvent.Sources -> renderSources(ev.items)
                        is QnaEvent.Error -> {
                            stageText.text = ev.message
                            stageText.setTextColor(getColor(android.R.color.holo_red_dark))
                        }
                        is QnaEvent.Done -> { /* 完成 */ }
                    }
                }
            } catch (t: Throwable) {
                // 显示完整异常链（message + 至多 5 层 cause），便于真机截图直接定位来源行；
                // 同时打到 Logcat，方便 adb logcat -s QnaActivity 取完整堆栈。
                val chain = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    var c = t.cause
                    var depth = 0
                    while (c != null && depth < 5) {
                        append("\n↳ ${c.message ?: c.javaClass.simpleName}")
                        c = c.cause
                        depth++
                    }
                }
                Log.e("QnaActivity", "ask 失败", t)
                stageText.text = "出错了：$chain"
                stageText.setTextColor(getColor(android.R.color.holo_red_dark))
            } finally {
                askBtn.isEnabled = true
                syncProviderUi()
            }
        }
    }

    private fun renderSources(items: List<SourceItem>) {
        sourcesRow.removeAllViews()
        if (items.isEmpty()) { sourcesLabel.visibility = View.GONE; return }
        sourcesLabel.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        val linkColor = attrColor(android.R.attr.colorPrimary)
        items.forEachIndexed { i, src ->
            val tv = TextView(this).apply {
                text = "${i + 1}. ${src.title}"
                textSize = 13f
                setTextColor(linkColor)
                gravity = Gravity.START
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                isClickable = true
                isFocusable = true
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    if (src.url.isNotBlank()) {
                        startActivity(Intent(this@QnaActivity, NetAssetActivity::class.java).putExtra("open_url", src.url))
                    }
                }
            }
            sourcesRow.addView(tv)
        }
    }

    private fun attrColor(attr: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }

    /** 跳到端侧 AI 验证页（模型下载 / 加载 / 导入） */
    private fun openModelPage() {
        startActivity(Intent(this, AiTestActivity::class.java))
    }

    private fun openCloudSettings() {
        val cfg = CloudConfigStore.load(this)
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((50 * resources.displayMetrics.density).toInt(), (30 * resources.displayMetrics.density).toInt(),
                (50 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt())
        }
        val etEndpoint = EditText(ctx).apply { setText(cfg.endpoint); hint = "endpoint，如 https://api.siliconflow.cn/v1" }
        val etKey = EditText(ctx).apply { setText(cfg.apiKey); hint = "API Key" }
        val etModel = EditText(ctx).apply { setText(cfg.model); hint = "模型名，如 deepseek-chat" }
        listOf(etEndpoint, etKey, etModel).forEach {
            it.inputType = InputType.TYPE_CLASS_TEXT
            layout.addView(it)
        }
        AlertDialog.Builder(ctx)
            .setTitle("云端设置（OpenAI 兼容）")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                CloudConfigStore.save(ctx, CloudConfig(
                    endpoint = etEndpoint.text.toString().trim(),
                    apiKey = etKey.text.toString().trim(),
                    model = etModel.text.toString().trim()
                ))
                toast("已保存")
                syncProviderUi()
                selectProvider("cloud")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        genJob?.cancel()
        scope.cancel()
    }
}
