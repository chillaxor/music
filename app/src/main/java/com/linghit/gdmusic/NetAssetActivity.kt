package com.linghit.gdmusic

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var browserPanel: View
    private lateinit var browserContainer: FrameLayout
    private lateinit var tabStrip: View
    private lateinit var tabChips: LinearLayout
    private lateinit var btnBack: Button
    private lateinit var btnClose: Button
    private lateinit var tvUrl: TextView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    // —— 内嵌浏览器多标签 ——
    /** 一个标签页 = 一个独立 WebView（不挂账本 JS 桥） */
    private class BrowserTab(val webView: WebView) {
        var title: String = ""
        val displayTitle: String get() = if (title.isNotBlank()) title else "新标签页"
    }

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTab: BrowserTab? = null

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
        browserContainer = findViewById(R.id.browserContainer)
        tabStrip = findViewById(R.id.tabStrip)
        tabChips = findViewById(R.id.tabChips)
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
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleExternalUrl(view ?: return false, url)
            }
        }
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
            // 告诉页面备份位置；没设置过返回空串（页面会提示「未设置」）
            @JavascriptInterface
            fun backupPath(): String = backupDirUri?.let { treeUriToReadable(it) } ?: ""
            // 页面底部「备份位置」行的设置/更换按钮：弹出系统目录选择器
            @JavascriptInterface
            fun pickBackupDir() {
                runOnUiThread { dirLauncher.launch(null) }
            }
            // 打开网页链接：在 app 内嵌浏览器（browserPanel 覆盖层）里打开，不跳出 App。
            // 浏览器标签不挂账本 JS 桥，外部站点无法接触 data。
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
        // 多标签：每个标签一个独立 WebView，只开 JS/DOM，不挂任何 JS 桥；
        // 覆盖在记账 WebView 之上，关闭后记账页原样保留（不重载、不丢状态）。
        btnBack.setOnClickListener {
            val t = currentTab
            if (t != null && t.webView.canGoBack()) t.webView.goBack() else closeCurrentTab()
        }
        btnClose.setOnClickListener { closeAllTabs() }
    }

    // ==================== 内嵌浏览器：多标签 ====================

    /** 显示内嵌浏览器并加载 url。默认就在当前标签页加载（不新开）；
     *  还没有任何标签（首次打开）时才建第一个标签。 */
    private fun showBrowser(url: String) {
        runOnUiThread {
            browserPanel.visibility = View.VISIBLE
            if (tabs.isEmpty()) createTab(url)
            else currentTab?.webView?.loadUrl(url)
        }
    }

    /** 新建标签并切过去；url 为空时建空白页（供网页 window.open 使用） */
    private fun createTab(url: String? = null): BrowserTab {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // 允许网页再开新窗口（window.open / target=_blank）→ 走 onCreateWindow 另开标签
            setSupportMultipleWindows(true)
        }
        wv.webViewClient = browserWebViewClient()
        wv.webChromeClient = browserChromeClient()
        val tab = BrowserTab(wv)
        tabs.add(tab)
        browserContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        switchTab(tab)
        if (!url.isNullOrBlank()) wv.loadUrl(url)
        return tab
    }

    private fun browserWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            return handleExternalUrl(view ?: return false, url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val tab = tabs.find { it.webView === view } ?: return
            tab.title = runCatching { view?.title ?: "" }.getOrDefault("").orEmpty()
            if (currentTab === tab) {
                if (url != null) tvUrl.text = url
                updateTabStrip()
            }
        }
    }

    private fun browserChromeClient() = object : WebChromeClient() {
        // 网页 window.open / target=_blank（如播放按钮跳转到别的页面）：
        // 另开一个标签页装它，不影响当前页；默认跳转仍走当前标签
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val tab = createTab()
            val transport = resultMsg.obj as? WebView.WebViewTransport
            transport?.webView = tab.webView
            resultMsg.sendToTarget()
            return true
        }

        // 网页自己 window.close()：关掉对应标签
        override fun onCloseWindow(window: WebView) {
            tabs.find { it.webView === window }?.let { closeTab(it) }
        }
    }

    private fun switchTab(tab: BrowserTab) {
        currentTab = tab
        browserContainer.removeAllViews()
        browserContainer.addView(tab.webView)
        browserPanel.visibility = View.VISIBLE
        tvUrl.text = tab.webView.url ?: ""
        updateTabStrip()
    }

    private fun closeTab(tab: BrowserTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        browserContainer.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            currentTab = null
            browserPanel.visibility = View.GONE
            updateTabStrip()
            return
        }
        if (currentTab === tab) switchTab(tabs[minOf(idx, tabs.size - 1)])
        else updateTabStrip()
    }

    private fun closeCurrentTab() {
        currentTab?.let { closeTab(it) }
    }

    private fun closeAllTabs() {
        tabs.toList().forEach { t ->
            browserContainer.removeView(t.webView)
            t.webView.destroy()
        }
        tabs.clear()
        currentTab = null
        browserPanel.visibility = View.GONE
        updateTabStrip()
    }

    /** 标签条：标题 + ✕，当前标签高亮 */
    private fun updateTabStrip() {
        tabChips.removeAllViews()
        tabStrip.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        tabs.forEach { tab ->
            val isCur = tab === currentTab
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hp = (14 * density).toInt()
                val vp = (6 * density).toInt()
                setPadding(hp, vp, hp, vp)
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    if (isCur) {
                        setColor(ContextCompat.getColor(this@NetAssetActivity, R.color.primary))
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(1, ContextCompat.getColor(this@NetAssetActivity, R.color.outline))
                    }
                }
                setOnClickListener { switchTab(tab) }
            }
            val title = TextView(this).apply {
                text = tab.displayTitle.take(12)
                textSize = 12f
                maxLines = 1
                setTextColor(
                    ContextCompat.getColor(
                        this@NetAssetActivity,
                        if (isCur) R.color.on_primary else R.color.on_surface_variant
                    )
                )
            }
            val close = TextView(this).apply {
                text = "✕"
                textSize = 12f
                setPadding((8 * density).toInt(), 0, 0, 0)
                setTextColor(
                    ContextCompat.getColor(
                        this@NetAssetActivity,
                        if (isCur) R.color.on_primary else R.color.on_surface_variant
                    )
                )
                setOnClickListener { closeTab(tab) }
            }
            chip.addView(title)
            chip.addView(close)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
                topMargin = (2 * density).toInt()
                bottomMargin = (2 * density).toInt()
            }
            tabChips.addView(chip, lp)
        }
    }

    private fun browserVisible() = browserPanel.visibility == View.VISIBLE

    /**
     * 处理 WebView 里的非 http(s) 跳转。
     * 返回 true = 已接管（WebView 不要再加载，避免 net::ERR_UNKNOWN_URL_SCHEME 错误页）。
     *
     * 覆盖场景：
     *  - baiduboxapp://、weixin://、alipays://、tmall:// 等 App 深链 → 尝试唤起对应 App；
     *  - intent:// 协议（带 #Intent;end 的那种）→ Intent.parseUri 解析后唤起，
     *    目标 App 没装时回退到它自带的 BROWSER_FALLBACK_URL 网页；
     *  - tel: / mailto: / sms: 等 → 交给系统应用；
     *  - 全都失败（比如模拟器没装百度 App）→ 静默拦截，停在当前页不弹错误页。
     */
    private fun handleExternalUrl(view: WebView, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("about:")
        ) return false
        return try {
            val intent = if (url.startsWith("intent://")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // intent:// 深链没装目标 App：有兜底网址就加载兜底，否则静默拦截
            if (url.startsWith("intent://")) {
                val fallback = try {
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        .getStringExtra("android.intent.extra.BROWSER_FALLBACK_URL")
                } catch (_: Exception) { null }
                if (!fallback.isNullOrEmpty()) { view.loadUrl(fallback); true }
                else true
            } else true
        } catch (e: Exception) {
            true
        }
    }

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
        // 先处理内嵌浏览器：当前标签能后退就后退，不能后退就关掉当前标签；
        // 最后一个标签关掉即回到记账页
        val t = currentTab
        if (browserVisible() && t != null) {
            if (t.webView.canGoBack()) t.webView.goBack() else closeTab(t)
            return
        }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        tabs.toList().forEach { t ->
            browserContainer.removeView(t.webView)
            runCatching { t.webView.destroy() }
        }
        tabs.clear()
        currentTab = null
    }
}
