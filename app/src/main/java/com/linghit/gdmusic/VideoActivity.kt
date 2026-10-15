package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 影视仓（TVBox / CatVod 风格）视频模块。
 *
 * 实现思路（全部自研，不依赖 webhtv 等外部工程）：
 *  - UI 是一张内置在 assets/video.html 的网页（暗色 TVBox 风格），负责接口源管理、
 *    分类 / 搜索 / 详情 / 选集等全部交互；
 *  - 原生端只提供能力桥：跨域抓取（OkHttp，绕过 WebView 的 CORS 限制）、播放、
 *    外部播放器唤起、源配置持久化；
 *  - 点选集时用原生 ExoPlayer（已带 HLS/DASH 模块）硬解 m3u8/mp4，体验比 WebView 内嵌视频好。
 *
 * 标准影视仓接口：source URL 返回 {sites:[{key,name,type,api,...}],parses,lives}，
 * 每个 site 的 api 走 ac=cate / ac=videolist / ac=detail / ac=videolist&wd= 四类请求。
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var overlay: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var videoTitle: TextView
    private lateinit var videoClose: TextView
    private lateinit var videoBuffering: TextView

    private var player: ExoPlayer? = null
    private val client = OkHttpClient.Builder().build()
    private val prefs by lazy { getSharedPreferences("video_sources", MODE_PRIVATE) }

    companion object {
        private const val KEY = "sources"
        private const val VER_KEY = "sources_ver"
        /** 默认源版本号：旧版存的失效源（如路径不存在的「盒子迷.top/摸鱼」）需要迁移 */
        private const val SOURCES_VER = 2
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        // 首次进入给的默认接口，用户可在应用内增删（多个入口）。
        // 用中文原样保存，JS 的 new URL() 会自动把主机做 IDN/punycode、路径做百分号编码。
        // 盒子迷.top/摸鱼 这个路径不存在（404 HTML），正确路径是 /禁止贩卖（多仓配置）。
        private const val DEFAULT_SOURCES =
            "[{\"name\":\"盒子迷多仓\",\"url\":\"https://盒子迷.top/禁止贩卖\"}," +
            "{\"name\":\"量子资源\",\"url\":\"https://cj.lziapi.com/api.php/provide/vod/\"}]"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        webView = findViewById(R.id.videoWebView)
        overlay = findViewById(R.id.videoOverlay)
        playerView = findViewById(R.id.videoPlayerView)
        videoTitle = findViewById(R.id.videoTitle)
        videoClose = findViewById(R.id.videoClose)
        videoBuffering = findViewById(R.id.videoBuffering)

        videoClose.setOnClickListener { closeVideo() }
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.webViewClient = object : WebViewClient() {
            // 网页万一跳到 baiduboxapp:// 之类的私有协议，静默拦截避免错误页
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://") ||
                    url.startsWith("file://") || url.startsWith("about:")
                ) return false
                return true
            }
        }
        // JS console.log 打到 logcat（tag: VidJS），便于在设备上排查页面逻辑
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("VidJS", "${m.message()}  [${m.sourceId()}:${m.lineNumber()}]")
                return true
            }
        }
        webView.addJavascriptInterface(VideoBridge(), "Android")
        webView.loadUrl("file:///android_asset/video.html")
    }

    // ===================== 原生能力桥 =====================
    private inner class VideoBridge {
        /** 跨域 GET：结果用 base64 回传，避免转义问题。cbId 对应 JS 的 window.__videoCb */
        @JavascriptInterface
        fun httpGet(url: String, cbId: Int) {
            if (url.isBlank()) {
                runOnUiThread {
                    webView.evaluateJavascript("window.__videoCb($cbId,false,'',0)", null)
                }
                return
            }
            val req = Request.Builder().url(url).header("User-Agent", UA)
                .header("Accept", "*/*").build()
            Log.d("VidHttp", "GET $url")
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("VidHttp", "FAIL $url -> ${e.javaClass.simpleName}: ${e.message}")
                    runOnUiThread {
                        safeEval("window.__videoCb($cbId,false,'',0)")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    // 非 2xx 一律按失败处理：404/502 等返回的是 HTML 错误页，
                    // 若放过去会让 JS 的 JSON.parse 报「不是合法 JSON」，掩盖真实原因
                    val body = if (response.isSuccessful) {
                        runCatching { response.body?.string() ?: "" }.getOrDefault("")
                    } else ""
                    Log.d(
                        "VidHttp",
                        "OK $url -> code=$code type=${response.header("Content-Type")} " +
                            "len=${body.length} head=${body.take(100).replace("\n", " ")}"
                    )
                    val b64 = Base64.encodeToString(
                        body.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
                    )
                    runOnUiThread {
                        safeEval("window.__videoCb($cbId,${response.isSuccessful},'$b64',$code)")
                    }
                }
            })
        }

        /** 原生 ExoPlayer 播放（直接 m3u8/mp4 等可硬解地址） */
        @JavascriptInterface
        fun playVideo(url: String, title: String) {
            runOnUiThread { startVideo(url, title) }
        }

        /** 拉起系统/外部播放器（解析类、或本机播不了的地址） */
        @JavascriptInterface
        fun externalPlay(url: String, title: String) {
            runOnUiThread {
                try {
                    val type = when {
                        url.contains(".m3u8", true) -> "application/x-mpegURL"
                        url.contains(".mpd", true) -> "application/dash+xml"
                        url.contains(".mp4", true) -> "video/mp4"
                        else -> "video/*"
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), type)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@VideoActivity, "没有可播放该链接的App", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@VideoActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        /** 读取已保存的接口源列表（JSON 数组） */
        @JavascriptInterface
        fun getSources(): String = sourcesJson()

        /** 保存接口源列表（JSON 数组） */
        @JavascriptInterface
        fun saveSources(json: String) {
            prefs.edit().putString(KEY, json).apply()
        }
    }

    private fun sourcesJson(): String {
        val saved = prefs.getString(KEY, null)
        val ver = prefs.getInt(VER_KEY, 0)
        // 版本迁移：老安装里存着已失效的默认源（install -r 不清数据，
        // 不迁移的话用户会一直用旧源报错），升到当前版本时重置为默认源
        if (saved.isNullOrBlank() || ver < SOURCES_VER) {
            prefs.edit().putString(KEY, DEFAULT_SOURCES).putInt(VER_KEY, SOURCES_VER).apply()
            return DEFAULT_SOURCES
        }
        return saved
    }

    /** 安全回传：WebView 销毁后不再 evaluate */
    private var webViewAlive = true
    private fun safeEval(code: String) {
        if (webViewAlive) webView.evaluateJavascript(code, null)
    }

    // ===================== 视频播放 =====================
    private fun ensurePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(this).build().also {
                playerView.player = it
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        videoBuffering.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w("VidPlay", "onPlayerError: ${error.message}")
                        val msg = error.message?.take(80) ?: "未知错误"
                        Toast.makeText(this@VideoActivity, "播放失败：$msg", Toast.LENGTH_LONG).show()
                        videoBuffering.visibility = View.GONE
                    }
                })
            }
        }
    }

    private fun startVideo(url: String, title: String) {
        Log.d("VidPlay", "startVideo: $title | $url")
        ensurePlayer()
        overlay.visibility = View.VISIBLE
        videoTitle.text = title
        videoBuffering.visibility = View.VISIBLE
        val mime = when {
            url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> MimeTypes.VIDEO_MP4
        }
        val item = androidx.media3.common.MediaItem.Builder()
            .setUri(url).setMimeType(mime).build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun closeVideo() {
        player?.pause()
        overlay.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewAlive = false
        player?.release()
        player = null
        webView.destroy()
    }
}
