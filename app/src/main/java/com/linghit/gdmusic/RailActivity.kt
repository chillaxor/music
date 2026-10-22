package com.linghit.gdmusic

import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.nio.charset.StandardCharsets

/**
 * 高铁往返记录（独立 WebView 页面，思路同「净资产记账」NetAssetActivity）：
 * 页面在 assets/rail.html，数据全部本地存储、不联网上传。
 * 与记账页共用同一份存储（networth_store 的 payload.rail 键，见 RailStore）：
 * 记账页的导出/导入/自动备份自动覆盖高铁数据。读写均为读-改-写，不碰记账字段。
 * JS 桥：window.Android.saveData / loadData / exportData / themeColor / openMusic。
 */
class RailActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingExport: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    pendingExport?.let { os.write(it.toByteArray(StandardCharsets.UTF_8)) }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        pendingExport = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_rail)
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleExternalUrl(view ?: return false, url)
            }
        }
        webView.addJavascriptInterface(object {
            /** 页面保存时把高铁数据交给原生，合并进 networth_store 的 rail 键（不碰记账字段） */
            @JavascriptInterface
            fun saveData(json: String) {
                RailStore.writeRail(this@RailActivity, json)
            }

            /** 读取已保存的高铁数据（空对象=首次进入） */
            @JavascriptInterface
            fun loadData(): String = RailStore.readRail(this@RailActivity).toString()

            /**
             * 导出：全量备份（整份 networth payload，含记账数据+高铁数据），
             * 用系统选择器存成文件；恢复走记账页「导入备份」。
             * 页面传过来的 rail-only json 忽略——只导行程的备份没处可导入，是死结。
             */
            @JavascriptInterface
            fun exportData() {
                pendingExport = RailStore.readPayloadString(this@RailActivity)
                exportLauncher.launch("从前慢备份.json")
            }

            /** 主题强调色（用户当前选的 12 套之一），交给页面做 CSS 变量，保持 app 同色系 */
            @JavascriptInterface
            fun themeColor(): String {
                val p = ThemePrefs.currentPack(this@RailActivity)
                return String.format("#%06X", 0xFFFFFF and p.primary)
            }

            /** 顶栏「🎵 音乐」：从高铁页切回主页 */
            @JavascriptInterface
            fun openMusic() {
                runOnUiThread {
                    try { startActivity(Intent(this@RailActivity, MainActivity::class.java)) }
                    catch (e: Exception) { e.printStackTrace() }
                }
            }
        }, "Android")
        RailStore.migrateIfNeeded(this) // 老版本 rail_store 数据一次性搬进 networth_store
        webView.loadUrl("file:///android_asset/rail.html")
    }

    /**
     * 页内跳转拦截：只放行 http/https/file/about，其余自定义 scheme 静默拦截，
     * 避免 net::ERR_UNKNOWN_URL_SCHEME 错误页。
     */
    private fun handleExternalUrl(view: WebView, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("about:")
        ) return false
        return true
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
