package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** 本地 MD 查看器：SAF 目录授权 → 列文件名 → WebView 渲染正文与图片，逻辑在 assets/mdviewer.html */
class MdViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.IO)
    /** 最近一次扫描结果，图片相对路径要靠里面的 dirDoc 解析 */
    private var lastScan: List<MdViewerStore.MdFile> = emptyList()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        MdViewerStore.setTreeUri(this, uri)
        // 页面已加载时直接重扫；首次进入时页面自己会在启动流程里 list
        webView.post {
            webView.evaluateJavascript("window.__mdFolderPicked&&window.__mdFolderPicked()", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        title = "MD 文档"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView = WebView(this)
        setContentView(webView)
        webView.setBackgroundColor(themeBg())
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        // 正文里的远端 http 图片：file:// 页面默认会被 WebView 的混合内容策略拦掉，必须放开
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.setSupportZoom(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (!MdViewerStore.isProxy(url)) return super.shouldInterceptRequest(view, request)
                val seg = Uri.parse(url).pathSegments
                if (seg.size < 2) return super.shouldInterceptRequest(view, request)
                val payload = runCatching { MdViewerStore.unproxy(seg[1]) }.getOrNull()
                    ?: return svgResponse(MdViewerStore.missingSvg("路径解析失败"))
                return when (seg[0]) {
                    "img" -> {
                        val (bytes, name) = MdViewerStore.readImage(this@MdViewerActivity, payload)
                        if (bytes == null) svgResponse(MdViewerStore.missingSvg(payload))
                        else WebResourceResponse(MdViewerStore.mimeOf(name), null, ByteArrayInputStream(bytes))
                    }
                    "missing" -> svgResponse(MdViewerStore.missingSvg(payload))
                    else -> super.shouldInterceptRequest(view, request)
                }
            }
        }
        webView.addJavascriptInterface(Bridge(), "MdAndroid")
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.loadUrl("file:///android_asset/mdviewer.html")
    }

    private fun svgResponse(svg: String) =
        WebResourceResponse("image/svg+xml", "UTF-8", ByteArrayInputStream(svg.toByteArray()))

    private fun themeBg(): Int {
        val p = ThemePrefs.currentPack(this)
        val rgb = Color.rgb(Color.red(p.textPrimary), Color.green(p.textPrimary), Color.blue(p.textPrimary))
        return if (rgb > 0x7F7F7F) p.fallbackA else p.background
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.__mdBack&&window.__mdBack()") { r ->
            if (r != "true") runOnUiThread { finish() }
        }
    }

    private fun async(cbId: String, block: suspend () -> String) {
        scope.launch {
            val result = runCatching { block() }.getOrDefault(err("执行失败"))
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("window.__mdCb($cbId,${JSONObject.quote(result)})", null)
            }
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
    private fun ok(b: JSONObject.() -> Unit) = JSONObject().apply { put("ok", true); b() }.toString()

    private inner class Bridge {

        @JavascriptInterface
        fun theme(): String {
            val p = ThemePrefs.currentPack(this@MdViewerActivity)
            val dark = Color.red(p.textPrimary) > 0x7F
            return JSONObject().apply {
                put("fg", hex(p.textPrimary))
                put("muted", hex(p.textSecondary))
                put("accent", hex(if (dark) 0xFF2C8C7C.toInt() else 0xFF7FD9C6.toInt()))
                put("dark", dark)
            }.toString()
        }

        private fun hex(c: Int) = "#%06X".format(0xFFFFFF and c)

        @JavascriptInterface fun configured(): Boolean =
            MdViewerStore.treeUri(this@MdViewerActivity) != null

        @JavascriptInterface fun folderName(): String = MdViewerStore.folderName(this@MdViewerActivity)

        @JavascriptInterface fun pickFolder() {
            runOnUiThread { folderPicker.launch(null) }
        }

        @JavascriptInterface fun clearFolder() {
            MdViewerStore.setTreeUri(this@MdViewerActivity, null)
            lastScan = emptyList()
        }

        @JavascriptInterface
        fun list(cbId: String) = async(cbId) {
            val ctx = this@MdViewerActivity
            lastScan = MdViewerStore.scan(ctx)
            val arr = JSONArray()
            lastScan.forEach {
                arr.put(JSONObject().apply {
                    put("uri", it.uri)
                    put("name", it.name)
                    put("relPath", it.relPath)
                    put("size", it.size)
                    put("modified", it.modified)
                })
            }
            ok {
                put("files", arr)
                put("folder", MdViewerStore.folderName(ctx))
            }
        }

        @JavascriptInterface
        fun read(uri: String, cbId: String) = async(cbId) {
            val ctx = this@MdViewerActivity
            val f = lastScan.firstOrNull { it.uri == uri }
            val raw = MdViewerStore.read(ctx, uri)
                ?: return@async err("读不到这个文件，可能已被移动或授权失效，试试「刷新」")
            ok {
                put("name", f?.name ?: uri.substringAfterLast('/'))
                put("relPath", f?.relPath.orEmpty())
                // 远端 http(s) 图片保持原样由 WebView 直连；本地相对路径改写成 SAF 代理 URL
                put("text", MdViewerStore.rewriteImages(ctx, f?.dirDoc, raw))
            }
        }

        @JavascriptInterface
        fun settings(): String {
            val sp = getSharedPreferences("md_viewer", MODE_PRIVATE)
            return JSONObject().apply { put("font", sp.getInt("font", 16)) }.toString()
        }

        @JavascriptInterface
        fun setSetting(font: Int) {
            getSharedPreferences("md_viewer", MODE_PRIVATE).edit().putInt("font", font.coerceIn(12, 26)).apply()
        }
    }
}
