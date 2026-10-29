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
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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

    // 页签与玩法面板
    private lateinit var qnaPanel: LinearLayout
    private lateinit var playPanel: LinearLayout
    private lateinit var tabAsk: TextView
    private lateinit var tabPlay: TextView
    private lateinit var playContentInput: EditText
    private lateinit var playPromptInput: EditText
    private lateinit var tplSpinner: Spinner
    private lateinit var playStageText: TextView
    private lateinit var playOutputText: TextView

    /** 玩法专用 system：按指令处理材料，第二人称去 AI 味（不检索、不抓网，比问答更稳） */
    private val PLAY_SYSTEM = buildString {
        append("你是「从前慢」App 内置的文案/任务助手。用户会给你一段【材料】和一条【指令】。\n")
        append("规则：1. 严格按照指令处理材料，不要答非所问。2. 简体中文，第二人称口吻，长短句混排，口语化。")
        append("3. 不用破折号堆砌，不用「赋能/抓手/闭环/颗粒度」这类套话。4. 不编造材料里没有的信息。")
        append("5. 直接给结果，不要寒暄，不要重复指令。")
    }

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

        // 页签与玩法面板
        qnaPanel = findViewById(R.id.qnaPanel)
        playPanel = findViewById(R.id.playPanel)
        tabAsk = findViewById(R.id.tabAsk)
        tabPlay = findViewById(R.id.tabPlay)
        playContentInput = findViewById(R.id.playContentInput)
        playPromptInput = findViewById(R.id.playPromptInput)
        tplSpinner = findViewById(R.id.tplSpinner)
        playStageText = findViewById(R.id.playStageText)
        playOutputText = findViewById(R.id.playOutputText)
        tabAsk.setOnClickListener { switchTab(0) }
        tabPlay.setOnClickListener { switchTab(1) }
        setupPlayTemplates()
        findViewById<Button>(R.id.saveTplBtn).setOnClickListener { saveTemplate() }
        findViewById<Button>(R.id.playClearBtn).setOnClickListener {
            playContentInput.setText("")
            playOutputText.text = "（结果会显示在这里）"
            playStageText.text = ""
        }
        findViewById<Button>(R.id.playRunBtn).setOnClickListener { doPlay() }
        findViewById<Button>(R.id.playCopyBtn).setOnClickListener {
            val t = playOutputText.text.toString()
            if (t.isBlank() || t == "（结果会显示在这里）") { toast("还没有结果"); return@setOnClickListener }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aiplay", t))
            toast("已复制")
        }
        switchTab(0)

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

    // ===================== 页签 + 玩法 =====================

    private var playTemplates: List<PlayTemplate> = emptyList()

    private fun switchTab(which: Int) {
        val ask = which == 0
        qnaPanel.visibility = if (ask) View.VISIBLE else View.GONE
        playPanel.visibility = if (ask) View.GONE else View.VISIBLE
        // 选中态：实心圆角（colorPrimary）+ 白字；未选中：透明描边 + 主色字
        tabAsk.setBackgroundResource(if (ask) R.drawable.bg_pill_active else R.drawable.bg_pill_outline)
        tabPlay.setBackgroundResource(if (ask) R.drawable.bg_pill_outline else R.drawable.bg_pill_active)
        tabAsk.setTextColor(if (ask) getColor(android.R.color.white) else attrColor(android.R.attr.colorPrimary))
        tabPlay.setTextColor(if (ask) getColor(android.R.color.white) else attrColor(android.R.attr.colorPrimary))
    }

    private fun setupPlayTemplates() {
        playTemplates = AiPlayStore.list(this)
        val names = playTemplates.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        tplSpinner.adapter = adapter
        // 默认选中第一个内置模板（起标题），把 prompt 填进指令框（可改）
        tplSpinner.setSelection(0)
        playPromptInput.setText(playTemplates.firstOrNull()?.prompt ?: "")
        tplSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                playTemplates.getOrNull(pos)?.let { playPromptInput.setText(it.prompt) }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }
    }

    private fun saveTemplate() {
        val cur = playPromptInput.text.toString().trim()
        if (cur.isBlank()) { toast("指令为空，没法存"); return }
        val ctx = this
        val et = EditText(ctx).apply {
            hint = "给这个模板起个名字，如「小红书标题」"
            setText("")
        }
        val pad = (50 * resources.displayMetrics.density).toInt()
        et.setPadding(pad, pad / 2, pad, pad / 2)
        AlertDialog.Builder(ctx)
            .setTitle("存为模板")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val name = et.text.toString().trim().ifBlank { "自定义模板" }
                AiPlayStore.saveCustom(ctx, PlayTemplate(AiPlayStore.newId(), name, "自定义", cur, false))
                setupPlayTemplates()
                // 选中刚存的那个（它在内置之后，所以 offset = 内置数量）
                val idx = AiPlayStore.builtins.size + (AiPlayStore.loadCustom(ctx).size - 1)
                tplSpinner.setSelection(idx.coerceAtLeast(0))
                toast("已保存模板：$name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doPlay() {
        val content = playContentInput.text.toString().trim()
        val prompt = playPromptInput.text.toString().trim()
        if (prompt.isBlank()) { toast("请选择或输入指令"); return }

        genJob?.cancel()
        playOutputText.text = ""
        playStageText.text = "准备中…"
        findViewById<Button>(R.id.playRunBtn).isEnabled = false

        val userText = if (content.isBlank()) {
            prompt
        } else {
            "【指令】\n$prompt\n\n【材料】\n$content"
        }

        scope.launch {
            // 玩法只用端侧模型：未加载就自动加载默认模型
            if (!AiEngine.isReady) {
                playStageText.text = "加载端侧模型中…（首次约 5–15 秒）"
                runCatching { withContext(Dispatchers.IO) { LocalAnswerProvider.prepare(this@QnaActivity) } }
            }
            if (!AiEngine.isReady) {
                playStageText.text = "端侧模型未加载：点本页右上角「🧠 模型」下载并加载一个 .litertlm 模型（推荐 Qwen3-1.7B）后再来。"
                playStageText.setTextColor(getColor(android.R.color.holo_red_dark))
                findViewById<Button>(R.id.playRunBtn).isEnabled = true
                return@launch
            }
            try {
                playStageText.setTextColor(attrColor(android.R.attr.colorPrimary))
                playStageText.text = "端侧模型思考中…"
                AiEngine.generate(this@QnaActivity, PLAY_SYSTEM, userText).collect { playOutputText.append(it) }
            } catch (t: Throwable) {
                Log.e("QnaActivity", "play 失败", t)
                playStageText.text = "出错了：${t.message ?: t.javaClass.simpleName}"
                playStageText.setTextColor(getColor(android.R.color.holo_red_dark))
            } finally {
                findViewById<Button>(R.id.playRunBtn).isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        genJob?.cancel()
        scope.cancel()
    }
}
