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
 * 与记账页同一个 JS 桥模式：window.Android.saveData / loadData / exportData / themeColor / openMusic。
 * 存储 key = rail_store（与记账的 networth_store 隔离，互不干扰）。
 */
class RailActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingExport: String? = null
    private val store by lazy { getSharedPreferences("rail_store", MODE_PRIVATE) }

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
            /** 页面保存时把整份数据交给原生落盘（唯一可信数据源） */
            @JavascriptInterface
            fun saveData(json: String) {
                store.edit().putString("payload", json).apply()
            }

            /** 读取已保存数据（空串=首次进入） */
            @JavascriptInterface
            fun loadData(): String = store.getString("payload", "") ?: ""

            /** 导出：让页面把 JSON 交给原生，用系统选择器存成文件 */
            @JavascriptInterface
            fun exportData(json: String) {
                pendingExport = json
                exportLauncher.launch("高铁往返备份.json")
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
