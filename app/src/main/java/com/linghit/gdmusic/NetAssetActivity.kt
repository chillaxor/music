package com.linghit.gdmusic

import android.net.Uri
import android.os.Bundle
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
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    // 原生持久化存储：localStorage 在 file:// 下不可靠（退出进程即丢），
    // 这里用 SharedPreferences 落盘，作为 App 端唯一可信数据源。
    private val store by lazy { getSharedPreferences("networth_store", MODE_PRIVATE) }

    // 实时 json 备份文件（写在 App 专属外部目录，无需任何权限）
    private val backupFile: File?
        get() {
            val dir = getExternalFilesDir(null) ?: filesDir
            return File(dir, "净资产快照.json")
        }

    // 写文件（先写临时文件再改名，避免写一半掉电导致文件损坏）
    private fun writeBackup(json: String) {
        try {
            val f = backupFile ?: return
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, StandardCharsets.UTF_8)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 读文件
    private fun readBackup(): String {
        try {
            val f = backupFile ?: return ""
            if (f.exists()) return f.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) { e.printStackTrace() }
        return ""
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
            // SharedPreferences + 手机本地 json 文件（双份，任一存活即可恢复）
            @JavascriptInterface
            fun saveData(json: String) {
                store.edit().putString("payload", json).apply()
                writeBackup(json)
            }
            // 读取：先问 SharedPreferences，没有再从 json 文件找
            @JavascriptInterface
            fun loadData(): String {
                val sp = store.getString("payload", "") ?: ""
                if (sp.isNotEmpty()) return sp
                val f = readBackup()
                if (f.isNotEmpty()) store.edit().putString("payload", f).apply()
                return f
            }
            // 告诉页面备份文件路径，页面上可以显示给用户
            @JavascriptInterface
            fun backupPath(): String = backupFile?.absolutePath ?: ""
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
