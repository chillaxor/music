package com.linghit.gdmusic

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 新闻详情页：App 内嵌 WebView 打开热榜原文，不再用系统浏览器跳出 App。
 *
 * 与「点链接跳浏览器」看到的页面是同一份（默认移动端 UA，头条等站点返回移动版），
 * 区别只是渲染在 App 内的 WebView 里。所有子链接默认也在本 WebView 内打开，
 * 不会外跳。返回键：WebView 内能后退则先退网页历史，否则关闭本页。
 */
class NewsDetailActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var titleTv: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_news_detail)

        webView = findViewById(R.id.detailWeb)
        progress = findViewById(R.id.detailProgress)
        titleTv = findViewById(R.id.detailTitle)
        findViewById<View>(R.id.detailBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.detailRefresh).setOnClickListener { webView.reload() }

        val url = intent.getStringExtra("url") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        titleTv.text = title.ifBlank { url }

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        // manifest 已 usesCleartextTraffic=true，这里再把混合内容放开，避免 https 页里的 http 资源被拦
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 仅主框架加载失败才提示（子资源失败不挡页面）
                if (request?.isForMainFrame == true) {
                    progress.visibility = View.GONE
                    Toast.makeText(this@NewsDetailActivity, "加载失败，请检查网络后点刷新", Toast.LENGTH_SHORT).show()
                }
            }
            // 返回 false = 在 WebView 内继续加载该 url，不交给系统处理（不跳出 App）
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        if (url.isNotBlank()) {
            webView.loadUrl(url)
        } else {
            Toast.makeText(this, "这条没有原文链接", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onBackPressed() {
        // WebView 内能后退则先退网页历史，否则退出本页（deprecated API，但项目内一致沿用）
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
