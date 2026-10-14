package com.linghit.gdmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 净资产记账（移植自独立项目「净资产快照」的 WebView 壳）。
 * 页面在 assets/index.html（与桌面版同源），数据全部本地存储、不联网上传。
 * 功能：净资产快照 / 记一笔 / 收支查询（农历日历）/ 日志 / 链接收藏 / 主题皮肤 / 隐私模式 / 月度历史。
 */
class NetAssetActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var browserView: WebView
    private lateinit var browserPanel: View
    private lateinit var btnBack: Button
    private lateinit var btnClose: Button
    private lateinit var tvUrl: TextView
    private lateinit var tvBackupPath: TextView
    private lateinit var btnBackupDir: Button
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    // 原生持久化存储：localStorage 在 file:// 下不可靠（退出进程即丢），
    // 这里用 SharedPreferences 落盘，作为 App 端唯一可信数据源。
    private val store by lazy { getSharedPreferences("networth_store", MODE_PRIVATE) }

    // 实时 json 备份文件（写在 App 专属外部目录，无需任何权限；卸载 App 会被系统清掉）
    private val backupFile: File?
        get() {
            val dir = getExternalFilesDir(null) ?: filesDir
            return File(dir, "净资产快照.json")
        }

    /** 快照在用户自选目录里的固定文件名 */
    private val backupFileName = "净资产快照.json"

    /** 用户自定义的备份目录（SAF 树 uri，字符串形式存 SharedPreferences） */
    private var backupDirUri: Uri?
        get() = store.getString("backup_dir", null)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        set(v) {
            store.edit().putString("backup_dir", v?.toString() ?: "").apply()
        }

    // 选备份目录：OpenDocumentTree 拿树 uri 并申请持久读写授权。
    // 授权本身在卸载 App 时会被系统撤销，所以重装后需要重新选一次同一个文件夹；
    // 但文件夹里的 json 文件是用户资产，系统不会动，选完即可恢复。
    private val dirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { e.printStackTrace() }
        backupDirUri = uri
        refreshBackupBar()
        // 立刻把当前数据写一份到新位置，并重载页面让账本读到该目录里的旧数据（重装恢复场景）
        val cur = store.getString("payload", "") ?: ""
        if (cur.isNotEmpty()) writeCustomBackup(cur)
        webView.reload()
    }

    // 写备份：优先写用户自选目录（卸载不丢）；没设置过才写 App 专属外部目录（卸载会被清）
    private fun writeBackup(json: String) {
        if (writeCustomBackup(json)) return
        try {
            val f = backupFile ?: return
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, StandardCharsets.UTF_8)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { e.printStackTrace() }
    }

    /** 写用户自选目录；成功返回 true（未设置/写失败返回 false，由调用方回落） */
    private fun writeCustomBackup(json: String): Boolean {
        val uri = backupDirUri ?: return false
        return try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return false
            var f = dir.findFile(backupFileName)
            if (f == null) f = dir.createFile("application/json", backupFileName)
            if (f == null) return false
            contentResolver.openOutputStream(f.uri, "wt")?.use { os ->
                os.write(json.toByteArray(StandardCharsets.UTF_8))
            }
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    /** 读用户自选目录里的快照；没有返回空串 */
    private fun readCustomBackup(): String {
        val uri = backupDirUri ?: return ""
        return try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return ""
            val f = dir.findFile(backupFileName) ?: return ""
            contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
        } catch (e: Exception) { e.printStackTrace(); "" }
    }

    // 读 App 专属外部目录的旧备份（兼容历史数据）
    private fun readBackup(): String {
        try {
            val f = backupFile ?: return ""
            if (f.exists()) return f.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) { e.printStackTrace() }
        return ""
    }

    /** 把 SAF 树 uri 转成给人看的路径，如 primary:Download/账本 → /sdcard/Download/账本 */
    private fun treeUriToReadable(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: uri.lastPathSegment ?: return uri.toString()
        val parts = docId.split(":", limit = 2)
        return if (parts.size == 2 && parts[0] == "primary") "/sdcard/${parts[1]}" else docId
    }

    /** 刷新底部工具条：显示当前备份位置与按钮文字 */
    private fun refreshBackupBar() {
        val uri = backupDirUri
        if (uri == null) {
            tvBackupPath.text = "备份位置：未设置（数据仅存 App 内，卸载会丢失）"
            btnBackupDir.text = "设置位置"
        } else {
            tvBackupPath.text = "备份位置：${treeUriToReadable(uri)}"
            btnBackupDir.text = "更换位置"
        }
    }

    // 导出：让页面把 JSON 交给原生，用系统选择器存成文件
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    pendingExport?.let { os.write(it.toByteArray(StandardCharsets.UTF_8)) }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        pendingExport = null
    }

    // 导入 / 账单 CSV：承接 WebView 的文件选择意图
    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_asset)
        webView = findViewById(R.id.webView)
        browserPanel = findViewById(R.id.browserPanel)
        browserView = findViewById(R.id.browserView)
        btnBack = findViewById(R.id.btnBack)
        btnClose = findViewById(R.id.btnClose)
        tvUrl = findViewById(R.id.tvUrl)
        tvBackupPath = findViewById(R.id.tvBackupPath)
        btnBackupDir = findViewById(R.id.btnBackupDir)
        refreshBackupBar()
        btnBackupDir.setOnClickListener { dirLauncher.launch(null) }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true   // localStorage 持久化（跨更新保留数据）
            databaseEnabled = true     // WebView 数据库落盘，配合 domStorage 一起持久化
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        // JS 桥：页面里 window.Android.exportData(json) 会走到这里
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun exportData(json: String) {
                pendingExport = json
                exportLauncher.launch("净资产快照备份.json")
            }
            @JavascriptInterface
            fun exportFlow(json: String) {
                pendingExport = json
                exportLauncher.launch("流水备份.json")
            }
            // 持久化：页面每次保存都把整份数据交给原生，落盘到
            // SharedPreferences + 用户自选目录的 json（双份，任一存活即可恢复）
            @JavascriptInterface
            fun saveData(json: String) {
                store.edit().putString("payload", json).apply()
                writeBackup(json)
            }
            // 读取：先问 SharedPreferences，没有再找用户自选目录，最后找 App 专属目录
            @JavascriptInterface
            fun loadData(): String {
                val sp = store.getString("payload", "") ?: ""
                if (sp.isNotEmpty()) return sp
                val c = readCustomBackup()
                if (c.isNotEmpty()) { store.edit().putString("payload", c).apply(); return c }
                val f = readBackup()
                if (f.isNotEmpty()) store.edit().putString("payload", f).apply()
                return f
            }
            // 告诉页面备份位置，页面上可以显示给用户
            @JavascriptInterface
            fun backupPath(): String =
                backupDirUri?.let { treeUriToReadable(it) } ?: (backupFile?.absolutePath ?: "")
            // 打开网页链接：在 app 内嵌浏览器（browserView 覆盖层）里打开，不跳出 App。
            // browserView 刻意不挂账本 JS 桥，外部站点无法接触 data。
            @JavascriptInterface
            fun openUrl(url: String) { showBrowser(url) }
        }, "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileLauncher.launch(params!!.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")

        // —— app 内嵌浏览器（链接页用）——
        // 独立 WebView，只开 JS/DOM，不挂任何 JS 桥；覆盖在记账 WebView 之上，
        // 关闭后记账页原样保留（不重载、不丢状态）。
        browserView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        browserView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) tvUrl.text = url
            }
        }
        btnBack.setOnClickListener { if (browserView.canGoBack()) browserView.goBack() else hideBrowser() }
        btnClose.setOnClickListener { hideBrowser() }
    }

    // 显示内嵌浏览器并加载 url（runOnUiThread 兼容来自 JS 桥的调用线程）
    private fun showBrowser(url: String) {
        runOnUiThread {
            browserPanel.visibility = View.VISIBLE
            tvUrl.text = url
            browserView.loadUrl(url)
        }
    }

    private fun hideBrowser() {
        runOnUiThread { browserPanel.visibility = View.GONE }
    }

    private fun browserVisible() = browserPanel.visibility == View.VISIBLE

    // 退到后台时让页面把内存里的最新数据再刷一次盘，
    // 避免 App 被系统直接杀掉时丢掉最后一次改动。
    override fun onPause() {
        super.onPause()
        try {
            webView.evaluateJavascript("window.__flushSave&&window.__flushSave()", null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    @Deprecated("用 OnBackPressedDispatcher 更规范，但这里逻辑简单，保持兼容写法")
    override fun onBackPressed() {
        // 先处理内嵌浏览器：能后退就后退，不能后退就关掉浏览器回到记账页
        if (browserVisible()) {
            if (browserView.canGoBack()) browserView.goBack() else hideBrowser()
            return
        }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
