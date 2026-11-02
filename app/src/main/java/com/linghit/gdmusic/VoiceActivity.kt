package com.linghit.gdmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

/**
 * 语音助手页（全新独立入口，不耦合任何现有页面内部）。
 *
 * 流程：点麦克风 → 申请录音权限 → SttEngine 实时识别 → 定稿后 IntentRouter 路由
 * → 执行对应动作（播放 / 记账 / 提醒 / 闲聊）→ 结果回显。
 * 触发：首页头部 🎤 图标，或常驻悬浮球长按气泡「语音助手」。
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var micBtn: ImageButton
    private lateinit var transcript: TextView
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var downloadBtn: Button
    private lateinit var aiModelBtn: Button

    private var stt: SttEngine? = null
    private var listening = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)

        micBtn = findViewById(R.id.micBtn)
        transcript = findViewById(R.id.transcript)
        result = findViewById(R.id.result)
        status = findViewById(R.id.status)
        downloadBtn = findViewById(R.id.downloadBtn)
        aiModelBtn = findViewById(R.id.aiModelBtn)

        if (!VoskModel.isReady(this)) {
            status.text = "首次使用需下载离线语音模型（约 42MB，纯本地、不联网识别）"
            downloadBtn.visibility = Button.VISIBLE
        }

        micBtn.setOnClickListener { toggle() }
        downloadBtn.setOnClickListener { downloadModel() }
        aiModelBtn.setOnClickListener { openModelDialog() }
        refreshAiModelBtn()
    }

    /** 导入模型文件（SAF）：从本机任意文件管理器选 .litertlm，复制进内部存储后加载 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        status.text = "导入中…"
        scope.launch(Dispatchers.IO) {
            val r = ModelManager.importFromUri(this@VoiceActivity, uri)
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    status.text = "导入成功：${it.name}（${it.length() / 1024 / 1024}MB），正在加载引擎…"
                    doLoad(it.absolutePath)
                }.onFailure {
                    status.text = "导入失败：${it.message}"
                }
            }
        }
    }

    /** 根据大模型当前状态刷新按钮文案（已就绪/未下载/已下载待加载） */
    private fun refreshAiModelBtn() {
        aiModelBtn.visibility = Button.VISIBLE
        aiModelBtn.text = if (AiEngine.isReady) {
            val cur = AiEngine.currentModelPath?.let { java.io.File(it).name } ?: "模型"
            "智能模型已就绪：$cur（点此管理/卸载）"
        } else {
            val n = ModelManager.installed(this).size
            if (n == 0) "加载智能回答模型（下载约 584MB 或导入文件）"
            else "加载智能回答模型（已下载 $n 个，待加载）"
        }
    }

    /** 模型管理弹窗：加载已下载的 / 下载新的 / 导入文件 / 卸载 */
    private fun openModelDialog() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (AiEngine.isReady) {
            items.add("卸载当前模型（释放内存）")
            actions.add { unloadModel() }
        }
        ModelManager.installed(this).forEach { f ->
            val mark = if (f.absolutePath == AiEngine.currentModelPath) "（当前）" else ""
            items.add("加载：${f.name}$mark")
            val path = f.absolutePath
            actions.add { doLoad(path) }
        }
        items.add("下载模型…")
        actions.add { openDownloadDialog() }
        items.add("从本机导入 .litertlm 文件")
        actions.add { importLauncher.launch(arrayOf("*/*")) }

        AlertDialog.Builder(this)
            .setTitle("智能回答模型（端侧离线）")
            .setItems(items.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择要下载的模型档位（体积差别大，让用户选） */
    private fun openDownloadDialog() {
        val specs = ModelManager.CATALOG
        val labels = specs.map { "${it.displayName}　约 ${it.approxSizeMb}MB\n${it.note}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要下载的模型")
            .setItems(labels) { _, which -> doDownload(specs[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 下载指定模型，完成后自动加载引擎 */
    private fun doDownload(spec: ModelManager.ModelSpec) {
        status.text = "下载 ${spec.displayName} 中… 0%"
        scope.launch(Dispatchers.IO) {
            val r = ModelManager.download(this@VoiceActivity, spec) { sum, total, pct ->
                launch(Dispatchers.Main) {
                    status.text = if (total > 0) {
                        "下载中 $pct%　${sum / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                    } else "下载中 $pct%"
                }
            }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    status.text = "下载完成：${it.name}（${it.length() / 1024 / 1024}MB），正在加载引擎…"
                    doLoad(it.absolutePath)
                }.onFailure {
                    status.text = "下载失败：${it.message}"
                }
            }
        }
    }

    /** 把 .litertlm 文件加载进引擎（首次 5–15 秒，GPU 失败自动回落 CPU） */
    private fun doLoad(path: String) {
        status.text = "加载引擎中…（首次约 5–15 秒，请稍候）"
        scope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { AiEngine.load(this@VoiceActivity, path, useGpu = true) }
            val dt = System.currentTimeMillis() - t0
            withContext(Dispatchers.Main) {
                r.onSuccess { backend ->
                    status.text = "✅ 智能模型已就绪（后端 $backend，耗时 ${dt}ms）"
                    refreshAiModelBtn()
                }.onFailure {
                    status.text = "❌ 模型加载失败：${it.message}"
                    refreshAiModelBtn()
                }
            }
        }
    }

    /** 卸载引擎、释放 native 内存（模型文件保留，下次直接复用） */
    private fun unloadModel() {
        scope.launch(Dispatchers.IO) { AiEngine.release() }
        status.text = "已卸载模型（内存已释放）"
        refreshAiModelBtn()
    }

    private fun toggle() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (!VoskModel.isReady(this)) {
            Toast.makeText(this, "请先下载语音模型", Toast.LENGTH_SHORT).show()
            downloadBtn.visibility = Button.VISIBLE
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        listening = true
        transcript.text = ""
        result.text = ""
        status.text = "正在聆听…（说完自动识别）"
        stt = SttEngine(VoskModel.modelDir(this).absolutePath)
        stt?.start(
            scope,
            onPartial = { transcript.text = it },
            onFinal = { text ->
                transcript.text = text
                stopListening()
                handleIntent(text)
            },
            onError = { msg ->
                listening = false
                status.text = "识别出错：$msg"
            }
        )
    }

    private fun stopListening() {
        listening = false
        stt?.stop()
        status.text = "点击麦克风开始说话"
    }

    private fun handleIntent(text: String) {
        status.text = "理解中…"
        when (val intent = IntentRouter.route(text)) {
            is VoiceIntent.Play -> doPlay(intent.query)
            is VoiceIntent.Record -> doRecord(intent)
            is VoiceIntent.Remind -> doRemind(intent)
            is VoiceIntent.Chat -> doChat(intent.text)
        }
    }

    private fun doPlay(q: String) {
        scope.launch(Dispatchers.IO) {
            // ① 先在本机音乐库（DownloadStore 扫描导入的）找
            val local = LocalMusic.search(this@VoiceActivity, q)
            val localSong = local.firstOrNull()
            if (localSong != null) {
                withContext(Dispatchers.Main) {
                    PlayerManager.playTemp(localSong, Scene.MUSIC)
                    result.text = "已播放（本地）：《${localSong.name}》— ${localSong.artist}"
                    status.text = ""
                }
                return@launch
            }
            // ② 本地没有 → 在线源兜底（需设置里配过音乐 API）
            try {
                val list = MusicSearch.search(this@VoiceActivity, q)
                withContext(Dispatchers.Main) {
                    if (list.isEmpty()) {
                        result.text = "本地库和在线源都没找到《$q》"
                    } else {
                        val s = list.first()
                        PlayerManager.playTemp(s, Scene.MUSIC)
                        result.text = "已播放（在线）：《${s.name}》— ${s.artist}"
                    }
                    status.text = ""
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    result.text = "本地库无《$q》，在线搜索也失败：${e.message}\n（可在设置里配置音乐 API，或先导入本地音乐）"
                    status.text = ""
                }
            }
        }
    }

    private fun doRecord(r: VoiceIntent.Record) {
        val res = LedgerBridge.record(
            this,
            LedgerBridge.LedgerRequest(
                amount = r.amount,
                type = r.type,
                accountHints = r.accountHints,
                explicitAccount = r.explicitAccount,
                who = r.who.ifBlank { null },
                to = r.to.ifBlank { null },
                note = r.note.ifBlank { null }
            )
        )
        result.text = res.message
        status.text = ""
    }

    private fun doRemind(r: VoiceIntent.Remind) {
        val item = buildReminder(r)
        ReminderStore.upsert(this, item)
        ReminderScheduler.onChanged(this)
        result.text = "已设提醒：${r.content.ifBlank { "提醒" }}（${r.timeText}）"
        status.text = ""
    }

    private fun doChat(text: String) {
        val modelPath = AiEngine.currentModelPath
        if (modelPath != null && AiEngine.isReady) {
            scope.launch {
                val sb = StringBuilder()
                try {
                    AiEngine.generate(
                        this@VoiceActivity,
                        "你是「从前慢」App 的语音助手，用简洁中文回答，不超过三句话。",
                        text
                    ).collect {
                        sb.append(it)
                        withContext(Dispatchers.Main) { result.text = sb.toString() }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) { result.text = "智能回答失败：${t.message}" }
                }
                withContext(Dispatchers.Main) { status.text = "" }
            }
        } else {
            result.text = "（闲聊）「$text」\n\n未加载端侧模型，暂不能智能回答。可说：播放 xx / 记一笔 12 元 餐 / 提醒我明天 8 点吃药。"
            status.text = ""
        }
    }

    /** 把语音里的提醒词转成 ReminderStore.Item（尽力解析时间，默认当天/次日 9 点） */
    private fun buildReminder(r: VoiceIntent.Remind): ReminderStore.Item {
        val cal = Calendar.getInstance()
        when {
            r.timeText.contains("后天") -> cal.add(Calendar.DAY_OF_MONTH, 2)
            r.timeText.contains("明天") -> cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        var h = 9
        var m = 0
        val hm = Regex("(\\d{1,2})\\s*[点:：时]").find(r.timeText)
        if (hm != null) h = hm.groupValues[1].toInt().coerceIn(0, 23)
        if ((r.timeText.contains("下午") || r.timeText.contains("晚上")) && h < 12) h += 12
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        return ReminderStore.Item(
            id = UUID.randomUUID().toString(),
            type = ReminderStore.TYPE_CUSTOM,
            title = r.content.ifBlank { "提醒" },
            emoji = "⏰",
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            repeat = ReminderStore.REPEAT_NONE,
            advances = listOf(0),
            hour = h,
            minute = m,
            year = cal.get(Calendar.YEAR),
            enabled = true
        )
    }

    private fun downloadModel() {
        downloadBtn.isEnabled = false
        status.text = "下载中… 0%"
        scope.launch(Dispatchers.IO) {
            val r = VoskModel.download(this@VoiceActivity) { pct, _, _ ->
                launch(Dispatchers.Main) { status.text = "下载中… $pct%" }
            }
            withContext(Dispatchers.Main) {
                if (r.isSuccess) {
                    status.text = "模型就绪，点击麦克风开始"
                    downloadBtn.visibility = Button.GONE
                } else {
                    status.text = "下载失败：${r.exceptionOrNull()?.message}\n可手动把 vosk-model-small-cn 文件夹放到内部存储 models/ 下"
                    downloadBtn.isEnabled = true
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == REQ_AUDIO) {
            Toast.makeText(this, "需要录音权限才能语音识别", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        stt?.release()
        stt = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQ_AUDIO = 1001
    }
}
