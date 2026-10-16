package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.linghit.gdmusic.spider.SpiderEngine
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
 *    源配置持久化；
 *  - 点选集时用原生 ExoPlayer（已带 HLS/DASH 模块）硬解 m3u8/mp4——**一律在本 App
 *    内播放**，不跳外部播放器；播放时强制横屏 + 沉浸式全屏，关闭后恢复竖屏浏览。
 *
 * 标准影视仓接口：source URL 返回 {sites:[{key,name,type,api,...}],parses,lives}，
 * 每个 site 的 api 走 ac=cate / ac=videolist / ac=detail / ac=videolist&wd= 四类请求。
 *
 * 搜索分两种模式（页面顶栏「全网 / 本站」切换）：
 *  - 全网：并发请求所有订阅源里的 CMS 直连站点（type 0/1 且 api 为 http(s)），
 *    外加 csp_ 爬虫站点（type 3，由订阅自带 dex jar 经 SpiderEngine 加载执行），
 *    结果按来源分组展示——多仓配置下某个关键词在 A 站没有、B 站有的是常态，
 *    单站搜索很容易「啥都搜不到」；
 *  - 本站：只搜当前选中站点。
 * XBPQ/AppGet 等 JS 框架源与 .py 源不在引擎范围内，暂不参与搜索。
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var overlay: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var videoTitle: TextView
    private lateinit var videoClose: TextView
    private lateinit var videoBuffering: TextView

    private var player: ExoPlayer? = null
    // 显式跟随重定向：盒子迷.top/摸鱼 会 301 跳到带尾斜杠的地址，
    // 不跟随的话拿到的是 301 空响应，表现为「接口加载失败」
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val prefs by lazy { getSharedPreferences("video_sources", MODE_PRIVATE) }

    companion object {
        private const val KEY = "sources"
        private const val VER_KEY = "sources_ver"
        /**
         * 默认源版本号：
         *  - v1：单源（已失效路径）
         *  - v2：盒子迷多仓 /禁止贩卖 + 量子资源（当时误判 /摸鱼 为 404）
         *  - v3：两个修正——① /摸鱼 是有效订阅（301→带尾斜杠，跟随后 200 JSON，
         *    108 站动漫向仓）；② 请求 UA 必须是 okhttp 风格，盒子迷对浏览器 UA
         *    会 302 踢到网站首页（返回 HTML），表现为「接口不是合法 JSON」。
         */
        private const val SOURCES_VER = 3
        /** v2 及以前的默认源：只有用户没动过它时才整体替换，自己加过源的保留 */
        private val OLD_DEFAULT_SOURCES =
            "[{\"name\":\"盒子迷多仓\",\"url\":\"https://盒子迷.top/禁止贩卖\"}," +
            "{\"name\":\"量子资源\",\"url\":\"https://cj.lziapi.com/api.php/provide/vod/\"}]"
        /**
         * 请求 UA：必须用 okhttp 风格。盒子迷.top/摸鱼 对浏览器 UA（Mozilla/...）
         * 会 302 跳 /index.html 返回 HTML，只有 okhttp UA 才直接给 JSON。
         * TVBox/FongMi 等客户端默认也都是 okhttp UA。
         */
        private const val UA = "okhttp/3.15.0"
        // 首次进入给的默认接口，用户可在应用内增删（多个入口）。
        // 用中文原样保存，JS 的 new URL() 会自动把主机做 IDN/punycode、路径做百分号编码。
        // 盒子迷两个路径都有效：/禁止贩卖（91 站综合仓）/摸鱼（301→/摸鱼/，108 站动漫仓）。
        // 注意：只放「多仓订阅地址」，别放单站地址（如 cj.lziapi.com 的 provide/vod
        // 直连接口）——那不是 {sites:[...]} 格式，当订阅源加载会得到 0 站点。
        private const val DEFAULT_SOURCES =
            "[{\"name\":\"盒子迷多仓\",\"url\":\"https://盒子迷.top/禁止贩卖\"}," +
            "{\"name\":\"盒子迷动漫仓\",\"url\":\"https://盒子迷.top/摸鱼\"}]"
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
        // 返回键：播放中先关播放器（退回流媒体），否则直接退出页面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlay.visibility == View.VISIBLE) closeVideo() else finish()
            }
        })
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

        /** 原生 ExoPlayer 播放（直接 m3u8/mp4 等可硬解地址）——一律在本 App 内播放 */
        @JavascriptInterface
        fun playVideo(url: String, title: String, referer: String) {
            runOnUiThread { startVideo(url, title, referer) }
        }

        // ===================== Spider 爬虫源桥（DexClassLoader 引擎） =====================
        // jar：订阅配置的 spider 地址（盒子迷伪装成 .png，可带 ;md5; 校验后缀）
        // api：站点 api，形如 csp_Bili；ext：站点配置；key：站点唯一 key
        /** 蜘蛛源搜索（searchContent） */
        @JavascriptInterface
        fun spiderSearch(jar: String, api: String, ext: String, key: String, wd: String, cbId: Int) {
            Log.d("VidSpider", "spiderSearch api=$api key=$key wd=$wd")
            if (jar.isBlank() || api.isBlank()) {
                spiderCb(cbId, false, "")
                return
            }
            SpiderEngine.get(this@VideoActivity)
                .searchAsync(jar, api, ext, key, wd) { r -> spiderCb(cbId, r != null, r ?: "") }
        }

        /** 蜘蛛源详情（detailContent） */
        @JavascriptInterface
        fun spiderDetail(jar: String, api: String, ext: String, key: String, ids: String, cbId: Int) {
            Log.d("VidSpider", "spiderDetail api=$api key=$key ids=$ids")
            if (jar.isBlank() || api.isBlank()) {
                spiderCb(cbId, false, "")
                return
            }
            SpiderEngine.get(this@VideoActivity)
                .detailAsync(jar, api, ext, key, ids) { r -> spiderCb(cbId, r != null, r ?: "") }
        }

        /** 蜘蛛源播放解析（playerContent）：返回 {url,parse,...} JSON */
        @JavascriptInterface
        fun spiderPlay(jar: String, api: String, ext: String, key: String, flag: String, id: String, cbId: Int) {
            Log.d("VidSpider", "spiderPlay api=$api key=$key flag=$flag id=${id.take(60)}")
            if (jar.isBlank() || api.isBlank()) {
                spiderCb(cbId, false, "")
                return
            }
            SpiderEngine.get(this@VideoActivity)
                .playAsync(jar, api, ext, key, flag, id) { r -> spiderCb(cbId, r != null, r ?: "") }
        }

        /** 拉起系统/外部播放器（解析类、或本机播不了的地址） */
        @JavascriptInterface
        fun externalPlay(url: String, title: String) {            runOnUiThread {
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
        // 不迁移的话用户会一直用旧源报错）。
        // 只有「没动过默认源」的用户才整体替换；自己加过源的只升版本号、数据保留。
        if (saved.isNullOrBlank() || ver < SOURCES_VER) {
            val keepUserData = ver >= 2 && saved != null && saved != OLD_DEFAULT_SOURCES
            if (!keepUserData) {
                prefs.edit().putString(KEY, DEFAULT_SOURCES).putInt(VER_KEY, SOURCES_VER).apply()
                return DEFAULT_SOURCES
            }
            prefs.edit().putInt(VER_KEY, SOURCES_VER).apply()
        }
        return saved ?: DEFAULT_SOURCES
    }

    /** 安全回传：WebView 销毁后不再 evaluate */
    private var webViewAlive = true
    private fun safeEval(code: String) {
        if (webViewAlive) webView.evaluateJavascript(code, null)
    }

    /** Spider 桥回传：结果 base64 避免转义问题（引擎回调在 watchdog 线程，切回 UI 线程） */
    private fun spiderCb(cbId: Int, ok: Boolean, text: String) {
        val b64 = if (ok && text.isNotEmpty())
            Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        else ""
        runOnUiThread { safeEval("window.__spiderCb($cbId,$ok,'$b64')") }
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
                        val msg = error.message?.take(60) ?: "未知错误"
                        Toast.makeText(
                            this@VideoActivity,
                            "播放失败：$msg（该线路可能需要解析，可换其他线路/源）",
                            Toast.LENGTH_LONG
                        ).show()
                        videoBuffering.visibility = View.GONE
                    }
                })
            }
        }
    }

    private fun startVideo(url: String, title: String, referer: String = "") {
        Log.d("VidPlay", "startVideo: $title | $url | referer=$referer")
        ensurePlayer()
        overlay.visibility = View.VISIBLE
        videoTitle.text = title
        videoBuffering.visibility = View.VISIBLE
        // 一律本 App 内播放：强制横屏 + 沉浸式全屏（关掉播放器再恢复竖屏）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        val itemBuilder = MediaItem.Builder().setUri(url)
        // 只有明确扩展名时才指定 mime；否则交给 DefaultMediaSourceFactory
        // 按 Content-Type 嗅探（乱设 VIDEO_MP4 会把非 mp4 流直接判死）
        when {
            url.contains(".m3u8", true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            url.contains(".mp4", true) -> itemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
        }
        val item = itemBuilder.build()
        // 很多 CDN 校验 Referer（不带 403）：用详情所属站点的 origin
        val headers = buildMap {
            if (referer.isNotBlank()) put("Referer", referer)
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    private fun closeVideo() {
        player?.pause()
        player?.stop()
        overlay.visibility = View.GONE
        // 恢复竖屏浏览 + 显示系统栏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
    }

    /** 播放时进沉浸式全屏：隐藏状态栏/导航栏，下拉手势临时唤出 */
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
