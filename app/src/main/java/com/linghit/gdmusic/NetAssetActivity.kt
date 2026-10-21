package com.linghit.gdmusic

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.util.Log
import android.webkit.ConsoleMessage
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
import android.widget.Toast
import org.json.JSONObject
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var fullscreenContainer: FrameLayout
    // 网页视频全屏（onShowCustomView）：当前全屏的视频 view 与回调
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
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
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_net_asset)
        webView = findViewById(R.id.webView)
        browserPanel = findViewById(R.id.browserPanel)
        browserContainer = findViewById(R.id.browserContainer)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 从首页自定义资源位进入时，落到指定子页（记一笔/收支查询/日志/链接收藏）
                val v = intent.getStringExtra("open_view")
                if (v != null && v in setOf("snapshot", "add", "flow", "calendar", "journal", "links")) {
                    intent.removeExtra("open_view") // 只生效一次，旋转/重载不回跳
                    view?.evaluateJavascript(
                        "if(typeof showView==='function')showView('$v')", null
                    )
                }
            }

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
            // 打开网页链接：深链（weixin://、alipays:// 等）直接唤起指定的 App；
            // 普通网址在 app 内嵌浏览器（browserPanel 覆盖层）里打开，不跳出 App。
            // 浏览器标签不挂账本 JS 桥，外部站点无法接触 data。
            @JavascriptInterface
            fun openUrl(url: String) {
                runOnUiThread { openLinkOrApp(url) }
            }

            // 顶栏「🎵 音乐」：从记账页切回音乐播放页（MainActivity）
            @JavascriptInterface
            fun openMusic() {
                runOnUiThread {
                    try { startActivity(Intent(this@NetAssetActivity, MainActivity::class.java)) }
                    catch (e: Exception) { e.printStackTrace() }
                }
            }

            // 链接卡片「📌 钉到首页」：把这条链接写成首页自定义资源位（不限量，可删）
            @JavascriptInterface
            fun pinHomeShortcut(json: String) {
                runOnUiThread {
                    try {
                        val o = JSONObject(json)
                        val ok = HomeShortcutStore.add(
                            this@NetAssetActivity,
                            HomeShortcutStore.Item(
                                type = "link",
                                target = o.optString("id"),
                                label = o.optString("title").ifBlank { "链接" },
                                emoji = o.optString("emoji").ifBlank { "🔗" },
                                url = o.optString("url")
                            )
                        )
                        Toast.makeText(
                            this@NetAssetActivity,
                            if (ok) "已钉到首页" else "首页已经有这个链接了",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@NetAssetActivity, "钉入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 链接页编辑了某条链接（标题/网址）：同步首页钉了这条的快捷位。
            // 只更新已存在的，不会因为编辑而往首页塞新 chip。
            @JavascriptInterface
            fun syncHomeShortcut(json: String) {
                runOnUiThread {
                    try {
                        val o = JSONObject(json)
                        val n = HomeShortcutStore.updateLink(
                            this@NetAssetActivity,
                            o.optString("id"),
                            o.optString("title").ifBlank { "链接" },
                            o.optString("url")
                        )
                        if (n > 0) {
                            Toast.makeText(this@NetAssetActivity, "首页快捷位已同步", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            // 链接页删了某条链接：首页对应的快捷位一并删除（留着也点不开）
            @JavascriptInterface
            fun removeHomeShortcut(id: String) {
                runOnUiThread {
                    try {
                        HomeShortcutStore.removeLink(this@NetAssetActivity, id)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }, "Android")
        webView.webChromeClient = object : WebChromeClient() {
            // JS console.log 打到 logcat（tag: NetJS），便于在设备上排查页面逻辑
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("NetJS", "${m.message()}  [${m.sourceId()}:${m.lineNumber()}]")
                return true
            }

            // 记账页内嵌视频的全屏（与内嵌浏览器同一套逻辑）
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

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
        // 首页钉的链接：深链唤起指定 App，普通网址进 App 内嵌浏览器（不跳出 App）
        intent.getStringExtra("open_url")?.takeIf { it.isNotBlank() }?.let { openLinkOrApp(it) }

        // —— app 内嵌浏览器（链接页用）——
        // 多标签：每个标签一个独立 WebView，只开 JS/DOM，不挂任何 JS 桥；
        // 覆盖在记账 WebView 之上，关闭后记账页原样保留（不重载、不丢状态）。
        btnBack.setOnClickListener {
            val t = currentTab
            if (t != null && t.webView.canGoBack()) t.webView.goBack() else closeCurrentTab()
        }
        btnClose.setOnClickListener { closeAllTabs() }
    }

    /** 从首页自定义资源位再次点同一个链接时，复用实例直接在内嵌浏览器打开 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra("open_url")?.takeIf { it.isNotBlank() }?.let { openLinkOrApp(it) }
    }

    // ==================== 内嵌浏览器：多标签 ====================

    /** 显示内嵌浏览器并加载 url。默认就在当前标签页加载（不新开）；
     *  还没有任何标签（首次打开）时才建第一个标签。 */
    private fun showBrowser(url: String) {
        runOnUiThread {
            browserPanel.visibility = View.VISIBLE
            applyBrowserChrome(true)
            if (tabs.isEmpty()) createTab(url)
            else currentTab?.webView?.loadUrl(url)
        }
    }

    /** 浏览器开合时同步系统栏：开着=深色 app bar + 浅色图标，
     *  关掉=还回记账页的浅色系统栏。不然状态栏那条白边会留在网页顶部 */
    private fun applyBrowserChrome(dark: Boolean) {
        val c = ContextCompat.getColor(this, if (dark) R.color.browser_bar else R.color.surface)
        window.statusBarColor = c
        window.navigationBarColor = c
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
    }

    /**
     * 打开一条链接（链接页点开 / 首页自定义链接）：
     *  - 深链（xhsdiscover://、weixin:// 等）或小红书分享链接 → 唤起指定的 App；
     *  - 普通网址 → 走 App 内嵌浏览器。
     */
    private fun openLinkOrApp(url: String) {
        val u = LinkRouter.normalize(url)
        // 内部伪协议（app://gallery / app://camera / app://<包名>）→ 本 App 接管
        if (InternalScheme.isInternal(u) && InternalScheme.open(this, u)) return
        val appLink = LinkRouter.resolveAppLink(u)
        if (appLink != null) {
            // 跳 App 前把内嵌浏览器收起来：否则从目标 App 回来时浏览器面板还挡在记账页上
            closeAllTabs()
            if (!LinkRouter.openApp(this, appLink)) {
                // 没装目标 App：分享链接(https)兜底走网页；纯深链没有网页可开，只能提示
                if (u.startsWith("http://") || u.startsWith("https://")) showBrowser(u)
                else Toast.makeText(this, "未安装对应的应用，无法跳转", Toast.LENGTH_SHORT).show()
            }
            return
        }
        showBrowser(u)
    }

    /** 新建标签并切过去；url 为空时建空白页（供网页 window.open 使用） */
    private fun createTab(url: String? = null): BrowserTab {
        // 防止个别站点疯狂 window.open 刷出一堆标签导致「乱跳」：超过上限复用当前标签
        if (tabs.size >= 8 && currentTab != null) {
            if (!url.isNullOrBlank()) currentTab!!.webView.loadUrl(url)
            return currentTab!!
        }
        val wv = WebView(this)
        // 深色底 + 关掉下拉回弹的白色拉弧：浅色主题下 WebView 默认白底，
        // 加载瞬间和 overscroll 都会在页面顶部糊出一条白边，很突兀
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.browser_bar))
        wv.overScrollMode = View.OVER_SCROLL_NEVER
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

        // 网页视频点「放大/全屏」：WebView 把视频 view 交出来，
        // 不实现这个回调的话默认是空实现——按钮点了什么都没发生
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            hideCustomView()
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
            applyBrowserChrome(false)
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
        applyBrowserChrome(false)
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
                        setStroke(1, ContextCompat.getColor(this@NetAssetActivity, R.color.browser_bar_line))
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
                        if (isCur) R.color.on_primary else R.color.browser_bar_sub
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
                        if (isCur) R.color.on_primary else R.color.browser_bar_sub
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

    // ==================== 网页视频全屏（横屏播放） ====================

    /**
     * 网页视频点「放大/全屏」时 WebView 把视频 view 交出来（onShowCustomView）。
     * 不实现这个回调默认是空实现——按钮点了什么都不发生。
     * 这里把视频挂到全屏容器、强制横屏、隐藏系统栏。
     */
    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        // 极少数页面会连续触发：先把上一个全屏收掉再挂新的
        if (customView != null) hideCustomView()
        customView = view
        customViewCallback = callback
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.VISIBLE
        // 视频全屏一律横屏（跟随传感器方向，倒拿也自动翻转）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        Log.d("NetJS", "onShowCustomView: 视频全屏，已切横屏")
    }

    /** 退出视频全屏：摘掉视频 view、恢复方向与系统栏 */
    private fun hideCustomView() {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        customView = null
        // 恢复「不指定」：手机（竖屏原生）回到竖屏，平板/横屏原生设备保持横屏，
        // 且跟随用户此前持握方向——硬编码 PORTRAIT 会把横屏设备扳竖，体验割裂
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars()
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        Log.d("NetJS", "onHideCustomView: 退出视频全屏，恢复原有方向")
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 内嵌浏览器里【网页自身】的跳转拦截。
     * 返回 true = 已接管（WebView 不要再加载，避免 net::ERR_UNKNOWN_URL_SCHEME 错误页）。
     *
     * 规则（产品需求）：网页里打开的网址，一律不允许跳转到外部 App。
     * 只放行 http/https/file/about（网页内导航/本页资源），其余自定义 scheme
     * （weixin://、alipays://、tmall://、intent://、tel:、mailto: 等）全部静默拦截，停在当前页。
     * 要跳指定 App，请在「链接页 / 首页自定义链接」里配对应深链（走 LinkRouter 唤起）。
     */
    private fun handleExternalUrl(view: WebView, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("about:")
        ) return false
        return true
    }

    // 退到后台时让页面把内存里的最新数据再刷一次盘，
    // 避免 App 被系统直接杀掉时丢掉最后一次改动。
    override fun onPause() {
        super.onPause()
        try {
            webView.evaluateJavascript("window.__flushSave&&window.__flushSave()", null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 回到前台时从原生存储重新拉一次数据：首页「自定义链接」可能在后台
    // 往 payload.links 加了链接，warm activity 里 WebView 的旧 data 不刷新的话，
    // 之后任何保存都会把新链接整份覆盖回去。
    override fun onResume() {
        super.onResume()
        try {
            webView.evaluateJavascript("window.__reloadFromNative&&window.__reloadFromNative()", null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    @Deprecated("用 OnBackPressedDispatcher 更规范，但这里逻辑简单，保持兼容写法")
    override fun onBackPressed() {
        // 视频全屏中：返回键先退出全屏（恢复竖屏），不直接退出页面
        if (customView != null) {
            hideCustomView()
            return
        }
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
