package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
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
import android.view.Gravity
import kotlin.math.abs
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.linghit.gdmusic.spider.SpiderEngine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    private lateinit var videoPip: TextView
    private var controlsVisible = true
    private lateinit var videoClock: TextView
    private lateinit var videoPrev: TextView
    private lateinit var videoNext: TextView
    private lateinit var videoBuffering: TextView
    private lateinit var videoMiniLayer: FrameLayout
    private lateinit var videoMiniBtn: TextView
    private lateinit var videoMiniClose: TextView
    /** 小窗（App 内悬浮小窗）模式：播放器缩到角落，页面可继续浏览 */
    private var miniMode = false
    /** 用户是否手动拖过小窗：拖动后不再被 applyMiniLayout 复位回右下角 */
    private var miniUserMoved = false
    /** 上一次在前台时系统悬浮窗是否还在（用于回来时判断是被 ✕ 关掉了） */
    private var floatWasRunning = false
    /** 进系统小窗（PiP）前是否是 App 内小窗，退出 PiP 时按原样恢复 */
    private var wasMiniBeforePip = false
    private var miniTouchSlop = 0
    private var miniDownX = 0f
    private var miniDownY = 0f
    private var miniDragged = false

    private var backCallback: OnBackPressedCallback? = null
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Activity 当前是否处于 resumed（小窗里是 paused，点小窗画面展开才会 resumed） */
    private var activityResumed = false
    /** 刚退出小窗、正准备把 Activity 拉回前台全屏播放（防止 onStop 误停播） */
    private var pipRestorePending = false
    // 显式跟随重定向：盒子迷.top/摸鱼 会 301 跳到带尾斜杠的地址，
    // 不跟随的话拿到的是 301 空响应，表现为「接口加载失败」
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        // 盒子迷.top 的 nginx 对 OkHttp 的 HTTP/2 会直接断流（unexpected end of stream），
        // 强制走 HTTP/1.1 即可正常拉到订阅 JSON。
        .protocols(listOf(Protocol.HTTP_1_1))
        // 关键：第三方影视源经常响应慢/半截断流/不返回，若不加超时，httpGet 的回调永远
        // 等不到 → 详情一直「加载详情…」转圈、列表 STATE.loading 永久为 true 导致整页卡死。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences("video_sources", MODE_PRIVATE) }

    // ===================== 播放列表（连播/切集） =====================
    /** 一条剧集：spider=true 时 url 不是直链，需回传 JS 经 playerContent 解析 */
    private data class EpItem(val name: String, val url: String, val spider: Boolean)

    private var epsList: List<EpItem> = emptyList()
    private var curIdx: Int = -1
    private var curReferer: String = ""

    // ===================== 播放历史（断点续播） =====================
    /** 当前播放记录 key（直连 "d:api|vod_id" / 爬虫 "s:key|vod_id"，由 JS 生成随 meta 带回） */
    private var curHisKey: String = ""
    /** 续播重放用的完整上下文（起播时解析 metaJson 得到，切集时增量更新） */
    private var curHisRec: JSONObject? = null
    /** 续播位置：等 prepare 完成 STATE_READY 再 seek（prepare 前 seek 不保险） */
    private var seekAfterReady: Long = 0
    /** 历史页点续播带回的记录 JSON：等 video.html 加载完交给 JS 重新起播 */
    private var pendingResumeJson: String? = null
    private var pageReady = false
    private val hisHandler = Handler(Looper.getMainLooper())
    private val hisTick = object : Runnable {
        override fun run() {
            saveHisPosition()
            hisHandler.postDelayed(this, 5000)
        }
    }
    /** 右上角时钟：每秒刷新 */
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            videoClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

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
        /** 嗅探播放页用的浏览器 UA：很多播放页对 okhttp UA 直接返回空/跳登录 */
        private const val SNIFF_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
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
        Log.d("VidPlay", "onCreate")
        setContentView(R.layout.activity_video)

        webView = findViewById(R.id.videoWebView)
        overlay = findViewById(R.id.videoOverlay)
        playerView = findViewById(R.id.videoPlayerView)
        videoPip = findViewById(R.id.videoPip)
        videoClock = findViewById(R.id.videoClock)
        videoPrev = findViewById(R.id.videoPrev)
        videoNext = findViewById(R.id.videoNext)
        videoBuffering = findViewById(R.id.videoBuffering)
        videoMiniLayer = findViewById(R.id.videoMiniLayer)
        videoMiniBtn = findViewById(R.id.videoMiniBtn)
        videoMiniClose = findViewById(R.id.videoMiniClose)
        miniTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        // 控制条显隐同步小窗按钮：只有播放中且控制条可见时才显示小窗按钮
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controlsVisible = visibility == View.VISIBLE
            Log.d("VidPlay", "控制条可见=$controlsVisible 播放中=${player?.isPlaying}")
            updatePipButton()
        })

        // 小窗按钮：把播放器缩成 App 内悬浮小窗（页面可继续浏览），
        // 小窗上的「⤢」或点画面 → 展开回全屏继续播（用户要的"关闭小屏回全屏"）
        // 小窗：优先走「系统悬浮窗」（能浮在音乐页/记账页/其他 App 之上）；
        // 没授权悬浮窗权限时退回页内小窗（仍可在本页继续浏览）
        videoPip.setOnClickListener { enterMiniOrFloat() }
        videoMiniBtn.setOnClickListener { exitMini() }
        // 小窗上的 ✕：直接停播并关掉播放器（不用先展开再返回）
        videoMiniClose.setOnClickListener { closeVideo() }
        // 小窗拖动 + 点击展开
        videoMiniLayer.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 落在 ⤢/✕ 按钮上：交给按钮自己处理（父层若返回 true 会吞掉点击，
                    // 按钮的 OnClick 永远不触发——✕ 会变成"展开"而不是"关闭"）
                    if (hitMiniButton(ev)) { miniDragged = false; return@setOnTouchListener false }
                    miniDownX = ev.rawX; miniDownY = ev.rawY; miniDragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - miniDownX
                    val dy = ev.rawY - miniDownY
                    if (!miniDragged && (abs(dx) > miniTouchSlop || abs(dy) > miniTouchSlop)) miniDragged = true
                    if (miniDragged) { moveMiniBy(dx, dy); miniDownX = ev.rawX; miniDownY = ev.rawY }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!miniDragged) exitMini(); true }
                MotionEvent.ACTION_CANCEL -> { miniDragged = false; true }
                else -> false
            }
        }
        videoPrev.setOnClickListener {
            if (curIdx <= 0) toast("已经是第一集") else playIndex(curIdx - 1)
        }
        videoNext.setOnClickListener { playNext() }
        // 返回键逐层回退：小窗→展开回全屏；播放中→关播放器(回到该视频详情页)；详情/搜索等浮层开着→交给页面关掉(回到搜索位置)；都没有才退出
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (miniMode) { exitMini(); return }
                if (overlay.visibility == View.VISIBLE) { closeVideo(); return }
                webView.evaluateJavascript("window.__videoBack?window.__videoBack():false") { r ->
                    if (r != "true") finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)
        setupWebView()
        // 悬浮窗上点「⤢/画面/通知」且 Activity 已被回收时，重建后收回全屏继续播
        if (intent?.getStringExtra(VideoFloatService.EXTRA_ACTION) == VideoFloatService.ACTION_EXPAND) {
            mainHandler.postDelayed({ takeBackFromFloat() }, 400)
        }
        // 播放历史页点「继续看」会带 resume_json 拉起本页：先收下，等页面加载完再续播
        pendingResumeJson = intent.getStringExtra("resume_json")
        if (pendingResumeJson != null) Log.d("VidPlay", "收到续播记录，等页面就绪")
    }

    /** 复用已存在的实例续播（历史页用 CLEAR_TOP|SINGLE_TOP 拉起） */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 悬浮窗上点「⤢」/点画面/点通知：收回全屏继续播
        if (intent?.getStringExtra(VideoFloatService.EXTRA_ACTION) == VideoFloatService.ACTION_EXPAND) {
            mainHandler.post { takeBackFromFloat() }
            return
        }
        val j = intent?.getStringExtra("resume_json")
        if (j != null) fireResume(j)
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 续播要交给页面 JS（重新拉详情/重新解析），必须等页面就绪
                pageReady = true
                pendingResumeJson?.let { fireResume(it); pendingResumeJson = null }
            }

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

        /**
         * 原生 ExoPlayer 播放（直接 m3u8/mp4 等可硬解地址）——一律在本 App 内播放。
         * epsJson：当前线路全部剧集 [{name,url,spider}]，用于上一集/下一集/播完连播；
         * idx：起始集下标；spiderJson：爬虫源上下文（JS 解析下一集时回调用）；
         * metaJson：历史记录字段 {key,pic,epName,vodId,siteApi,from}（JS 侧生成）。
         */
        @JavascriptInterface
        fun playVideo(
            url: String,
            title: String,
            referer: String,
            epsJson: String,
            idx: Int,
            spiderJson: String,
            metaJson: String
        ) {
            runOnUiThread { startVideo(url, title, referer, epsJson, idx, spiderJson, metaJson) }
        }

        /** 爬虫源剧集解析完成后的起播桥：playSpiderEp 拿到直链后回传 */
        @JavascriptInterface
        fun playResolved(
            url: String,
            title: String,
            referer: String,
            idx: Int,
            epsJson: String,
            spiderJson: String,
            metaJson: String
        ) {
            runOnUiThread { startVideo(url, title, referer, epsJson, idx, spiderJson, metaJson) }
        }

        /** 播放历史页入口（影视仓顶栏 🕘） */
        @JavascriptInterface
        fun openHistory() {
            runOnUiThread {
                startActivity(Intent(this@VideoActivity, VideoHistoryActivity::class.java))
            }
        }

        /** 切换到音乐播放页（顶栏 🎵 音乐）：回到主 App 的音乐播放器。
         *  切走前先彻底停掉视频（全屏或小窗都停），避免后台还在响、
         *  以及回到影视仓时残留一个关不掉的小窗。 */
        @JavascriptInterface
        fun openMusic() {
            runOnUiThread {
                if (miniMode || overlay.visibility == View.VISIBLE) {
                    Log.d("VidPlay", "切换到音乐：先停掉视频（miniMode=$miniMode）")
                    closeVideo()
                }
                startActivity(Intent(this@VideoActivity, MainActivity::class.java))
            }
        }

        /** 续播位置（毫秒）：JS 重新起播前调，prepare 完成后 STATE_READY 时 seek */
        @JavascriptInterface
        fun setResumePos(ms: Int) {
            runOnUiThread { seekAfterReady = ms.toLong().coerceAtLeast(0) }
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

        /**
         * 轻量“解析/嗅探”：抓取播放页 HTML，用正则抠出真实直链（m3u8/mp4）。
         * 覆盖两类放不了的源：
         *  1) 直连 CMS 的 /share/<hash> 页面（如量子资源：页面里 var main="/xxx/index.m3u8?sign=..."）
         *  2) 爬虫 parse=1 的播放页（如旺旺：页面里 mac_url='集$直链#...'）
         * 结果 JSON 走 base64 回传 window.__sniffCb，避免转义问题。
         */
        @JavascriptInterface
        fun sniff(url: String, referer: String, cbId: Int) {
            Log.d("VidPlay", "sniff: $url referer=$referer")
            val result = try {
                val rb = Request.Builder().url(url)
                    .header("User-Agent", SNIFF_UA)
                if (referer.isNotBlank()) rb.header("Referer", referer)
                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        """{"ok":false,"msg":"HTTP ${resp.code}"}"""
                    } else {
                        val html = resp.body?.string() ?: ""
                        val found = extractStream(html, url)
                        if (found != null) """{"ok":true,"url":${JSONObject.quote(found)}}"""
                        else """{"ok":false,"msg":"页面未找到直链"}"""
                    }
                }
            } catch (e: Exception) {
                Log.w("VidPlay", "sniff 失败: ${e.javaClass.simpleName}: ${e.message}")
                """{"ok":false,"msg":"${e.javaClass.simpleName}"}"""
            }
            val b64 = Base64.encodeToString(result.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            runOnUiThread { safeEval("window.__sniffCb($cbId,true,'$b64')") }
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

    /** 从播放页 HTML 里抠真实直链；抠不到返回 null。pageUrl 用于把相对路径补全。 */
    private fun extractStream(html: String, pageUrl: String): String? {
        // 1) 量子资源类：var main = "/20240923/xxx/index.m3u8?sign=..."
        Regex("""var\s+main\s*=\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.let {
            return absUrl(it.groupValues[1], pageUrl)
        }
        // 2) 旺旺/MacCMS 播放页：mac_url = '第01集$https://.../index.m3u8#第02集$...'
        Regex("""mac_url\s*=\s*['"]([^'"]+)['"]""").find(html)?.let { m ->
            val first = m.groupValues[1].split('#').firstOrNull()
                ?.split('$')?.lastOrNull()?.trim()
            if (!first.isNullOrBlank()) return absUrl(first, pageUrl)
        }
        // 3) 任意绝对直链 m3u8/mp4/flv/mpd
        Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4|flv|mpd)""").find(html)?.let { return it.value }
        // 4) 其它 var *url* = "...m3u8|mp4..."
        Regex("""var\s+\w*[Uu]rl\w*\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.let {
            return absUrl(it.groupValues[1], pageUrl)
        }
        return null
    }

    /** 相对路径补全为绝对地址 */
    private fun absUrl(u: String, pageUrl: String): String {
        val s = u.trim()
        return when {
            s.startsWith("http", true) -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> {
                val parts = pageUrl.split("/")
                if (parts.size >= 3) "${parts[0]}//${parts[2]}$s" else s
            }
            else -> {
                val i = pageUrl.lastIndexOf('/')
                (if (i > 0) pageUrl.substring(0, i) else pageUrl) + "/" + s
            }
        }
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
                        // 诊断日志：真的渲染出画面才算"起播成功"（排查"点了没反应/后台响"必看）
                        if (state == Player.STATE_READY) Log.d("VidPlay", "STATE_READY（已起播）")
                        // 续播：等 READY 再 seek（prepare 前 seek 不保险）
                        if (state == Player.STATE_READY && seekAfterReady > 0) {
                            val to = seekAfterReady
                            seekAfterReady = 0
                            runCatching { it.seekTo(to) }
                            Log.d("VidPlay", "续播 seekTo=$to ms")
                        }
                        // 播完自动下一集（影视仓主流体验）
                        if (state == Player.STATE_ENDED) playNext()
                    }

                    // 播放/暂停切换：播放时保持屏幕常亮，暂停后允许熄屏
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isInPictureInPictureMode) return
                        setKeepScreenOn(isPlaying)
                        updatePipButton()
                    }

                    // 视频尺寸变化（换源/清晰度）时，小窗按新比例重算，别留黑边
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (miniMode && videoSize.width > 0 && videoSize.height > 0) applyMiniLayout()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 扒开 cause 链拿真实 HTTP 状态码（InvalidResponseCodeException），
                        // 否则对外只显示笼统的 "Source error"，用户/排查都看不出所以然。
                        var detail = error.message ?: "未知错误"
                        var c: Throwable? = error.cause
                        while (c != null) {
                            if (c is HttpDataSource.InvalidResponseCodeException) {
                                detail = "HTTP ${c.responseCode}"
                                break
                            }
                            c = c.cause
                        }
                        Log.w("VidPlay", "onPlayerError: $detail (${error.errorCodeName})")
                        val friendly = when {
                            detail.startsWith("HTTP 403") -> "该线路拒绝访问(403)，可能需登录/防盗链，请换其他线路/源"
                            detail.startsWith("HTTP 404") -> "该线路地址不存在(404)，请换其他线路/源"
                            detail.startsWith("HTTP 502") || detail.startsWith("HTTP 503") -> "该线路源站故障($detail)，请换其他线路/源"
                            else -> "播放失败：$detail（可换其他线路/源）"
                        }
                        Toast.makeText(
                            this@VideoActivity,
                            friendly,
                            Toast.LENGTH_LONG
                        ).show()
                        videoBuffering.visibility = View.GONE
                    }
                })
            }
        }
    }

    /**
     * 起播入口。epsJson/idx/spiderJson 由 JS 在点选集时一次性带上（当前线路全部剧集），
     * 空串表示沿用已有列表（爬虫源解析完成回调、或直接切集时走 playIndex）。
     * metaJson：历史记录字段（key/pic/epName/vodId/siteApi/from）。
     */
    private fun startVideo(
        url: String,
        title: String,
        referer: String,
        epsJson: String = "",
        idx: Int = -1,
        spiderJson: String = "",
        metaJson: String = ""
    ) {
        Log.d("VidPlay", "startVideo: $title | $url | referer=$referer | idx=$idx")
        if (epsJson.isNotBlank()) epsList = parseEps(epsJson)
        if (referer.isNotBlank()) curReferer = referer
        if (idx >= 0) curIdx = idx
        recordHistory(url, title, referer, epsJson, idx, spiderJson, metaJson)
        overlay.visibility = View.VISIBLE
        videoBuffering.visibility = View.VISIBLE
        // 小窗状态下又点了新视频 → 先展开回全屏再播
        if (miniMode) exitMini()
        // 一律本 App 内播放：强制横屏 + 沉浸式全屏（关掉播放器再恢复竖屏）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        clockHandler.removeCallbacks(clockTick)
        clockTick.run()
        playMediaUrl(url, curReferer)
    }

    /** 起播即落一条播放历史（断点续播的完整上下文一并存） */
    private fun recordHistory(
        url: String,
        title: String,
        referer: String,
        epsJson: String,
        idx: Int,
        spiderJson: String,
        metaJson: String
    ) {
        var key = ""; var pic = ""; var epName = ""
        var vodId = ""; var siteApi = ""; var from = ""
        try {
            val m = JSONObject(metaJson)
            key = m.optString("key"); pic = m.optString("pic"); epName = m.optString("epName")
            vodId = m.optString("vodId"); siteApi = m.optString("siteApi"); from = m.optString("from")
        } catch (e: Exception) { }
        curHisKey = key
        if (key.isBlank()) { curHisRec = null; return }
        val old = VideoHistoryStore.find(this, key)
        val rec = VideoHistoryStore.Rec(
            key = key,
            title = title,
            pic = pic,
            epName = epName.ifBlank { epsList.getOrNull(idx)?.name ?: "" },
            url = url,
            referer = referer,
            epsJson = epsJson,
            idx = if (idx >= 0) idx else 0,
            spiderJson = spiderJson,
            vodId = vodId,
            siteApi = siteApi,
            from = from,
            positionMs = 0,
            durationMs = old?.durationMs ?: 0,
            updatedAt = System.currentTimeMillis()
        )
        VideoHistoryStore.upsert(this, rec)
        curHisRec = rec.toJson()
        // 起播后每 5 秒存一次播放位置
        hisHandler.removeCallbacks(hisTick)
        hisTick.run()
    }

    /** 存当前播放位置（每 5 秒 + 退出/关播放器时调） */
    private fun saveHisPosition() {
        if (curHisKey.isBlank()) return
        val p = player ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(0L)
        if (pos <= 0) return
        val dur = runCatching { p.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0
        VideoHistoryStore.updatePosition(this, curHisKey, pos, dur)
    }

    /** 把续播记录交给页面 JS：重新拉详情/重新解析，再从记录位置起播 */
    private fun fireResume(json: String) {
        if (!pageReady) { pendingResumeJson = json; return }
        Log.d("VidPlay", "触发续播: ${json.take(80)}")
        // JSON 直接拼进 JS 单引号字符串会被 \" 转义破坏结构，走 base64（同 spiderCb 方案）
        val b64 = Base64.encodeToString(json.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        safeEval("if(window.__resumeHistory)window.__resumeHistory('$b64')")
    }

    /** 播放单条直链（Referer 很多 CDN 校验，不带 403） */
    private fun playMediaUrl(url: String, referer: String) {
        ensurePlayer()
        val itemBuilder = MediaItem.Builder().setUri(url)
        // 只有明确扩展名时才指定 mime；否则交给 DefaultMediaSourceFactory
        // 按 Content-Type 嗅探（乱设 VIDEO_MP4 会把非 mp4 流直接判死）
        when {
            url.contains(".m3u8", true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            url.contains(".mp4", true) -> itemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
        }
        val item = itemBuilder.build()
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 切到第 i 集：直链直接播；爬虫源回传 JS 经 playerContent 解析后再起播 */
    private fun playIndex(i: Int) {        if (i < 0 || i >= epsList.size) return
        val ep = epsList[i]
        curIdx = i
        if (ep.spider) {
            Log.d("VidPlay", "切集(爬虫源) -> __playSpiderAt($i)")
            safeEval("if(window.__playSpiderAt)window.__playSpiderAt($i)")
            return
        }
        Log.d("VidPlay", "切集 -> ${ep.name}")
        playMediaUrl(ep.url, curReferer)
        // 切集同步历史：集名/下标/直链变，播放位置从头开始
        VideoHistoryStore.updateEpisode(this, curHisKey, ep.name, i, ep.url)
        curHisRec?.put("epName", ep.name)?.put("idx", i)?.put("url", ep.url)?.put("positionMs", 0)
    }

    private fun playNext() {
        if (epsList.isEmpty()) return
        if (curIdx + 1 < epsList.size) playIndex(curIdx + 1)
        else toast("播放完毕，已是最后一集")
    }

    private fun parseEps(json: String): List<EpItem> {
        val out = ArrayList<EpItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    EpItem(
                        o.optString("name"),
                        o.optString("url"),
                        o.optBoolean("spider")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("VidPlay", "剧集列表解析失败: ${e.message}")
        }
        return out
    }

    private fun closeVideo() {
        saveHisPosition()
        hisHandler.removeCallbacks(hisTick)
        player?.pause()
        player?.stop()
        overlay.visibility = View.GONE
        clockHandler.removeCallbacks(clockTick)
        // 小窗状态下关播放器：先把播放层尺寸恢复整屏，否则下次起播会带着小窗尺寸
        if (miniMode) {
            miniMode = false
            videoMiniLayer.visibility = View.GONE
            val lp = overlay.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, 0)
            overlay.layoutParams = lp
            playerView.useController = true
            webView.post { safeEval("if(window.__onMiniChange)window.__onMiniChange(false)") }
        }
        // 恢复竖屏浏览 + 显示系统栏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
        setKeepScreenOn(false)
        videoPip.visibility = View.GONE
        // 返回后把"播放前那一页"找回来：若刚才是全网搜索，恢复搜索页+该视频详情弹窗，
        // 避免退回 App 首页推荐流（页面 DOM 仍在，只是要让对应浮层重新显示）。
        webView.post { safeEval("if(window.__restoreAfterPlay)window.__restoreAfterPlay()") }
    }

    /** 保持屏幕常亮（仅播放时） */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 小窗按钮：仅在「未进小窗 + 正在播放 + 控制条可见」时显示。
     *  控制条自动隐藏（持续播放几秒后）时小窗按钮一并隐藏，点屏幕唤出控制条即重新出现，
     *  与暂停/快进按钮保持一致。 */
    private fun updatePipButton() {
        val show = !miniMode && !isInPictureInPictureMode && player?.isPlaying == true && controlsVisible
        videoPip.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ===================== App 内小窗（悬浮小播放器） =====================
    /** 只把播放层恢复整屏（不动 miniMode 标志）：进系统小窗前用，避免系统小窗里只露一小块 */
    private fun restoreOverlayFullSize() {
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        if (lp.width != FrameLayout.LayoutParams.MATCH_PARENT || lp.height != FrameLayout.LayoutParams.MATCH_PARENT) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, 0)
            overlay.layoutParams = lp
        }
        videoMiniLayer.visibility = View.GONE
    }

    // ===================== 系统悬浮窗（浮在所有页面之上） =====================
    /** 小窗入口：有悬浮窗权限就系统悬浮（跨页面/跨 App），否则退回页内小窗 */
    private fun enterMiniOrFloat() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            startFloat()
        } else {
            toast("想在音乐/记账等页面也悬浮，请在设置里打开「显示在其他应用上层」")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            enterMini()   // 授权前先给一个页内小窗，不至于没得用
        }
    }

    /** 交给系统悬浮窗播放：同一个 player 实例挂到浮层上，不重新缓冲 */
    private fun startFloat() {
        val p = player ?: return
        VideoFloatState.player = p
        playerView.player = null            // 先摘下来，交给浮层
        videoPip.visibility = View.GONE
        setKeepScreenOn(false)
        hideVideoLayer()                    // 收起全屏播放层，露出页面
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
        clockHandler.removeCallbacks(clockTick)
        Log.d("VidPlay", "进入系统悬浮小窗")
        runCatching { startForegroundService(Intent(this, VideoFloatService::class.java)) }
            .onFailure {
                Log.w("VidPlay", "启动悬浮服务失败，退回页内小窗: ${it.message}")
                playerView.player = p
                showVideoLayerFullscreen()
                enterMini()
            }
        webView.post { safeEval("if(window.__onMiniChange)window.__onMiniChange(true)") }
    }

    /** ⤢ / 点浮层 / 点通知：把播放器收回 Activity 全屏继续播 */
    private fun takeBackFromFloat() {
        // 顺序很重要：先把播放器接回 Activity 的 PlayerView，再收浮层，
        // 否则中间会出现"两端都没画面"的瞬间（且旧实现还会顺带把播放停掉）
        val p = VideoFloatState.player ?: player ?: return
        player = p
        playerView.player = p
        showVideoLayerFullscreen()
        VideoFloatService.stop(this)        // 收掉浮层（保留播放）
        playerView.useController = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        setKeepScreenOn(p.playWhenReady || p.isPlaying)
        clockTick.run()
        updatePipButton()
        Log.d("VidPlay", "从悬浮窗回到全屏播放")
        webView.post { safeEval("if(window.__onMiniChange)window.__onMiniChange(false)") }
    }

    /** 收起全屏播放层（进悬浮时用） */
    private fun hideVideoLayer() {
        miniMode = false
        videoMiniLayer.visibility = View.GONE
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        overlay.layoutParams = lp
        overlay.visibility = View.GONE
    }

    /** 恢复全屏播放层尺寸并显示 */
    private fun showVideoLayerFullscreen() {
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        overlay.layoutParams = lp
        overlay.visibility = View.VISIBLE
    }

    /** 进 App 内小窗：播放器缩到屏幕角落继续播，页面回到竖屏可继续浏览/搜索 */
    private fun enterMini() {
        if (miniMode) return
        miniMode = true
        miniUserMoved = false   // 每次进小窗回到默认右下角；拖动后不再被转屏/换清晰度弹回
        // 先缩窗口再转竖屏，避免全屏播放器在竖屏下闪一下
        applyMiniLayout()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
        setKeepScreenOn(false)
        videoPip.visibility = View.GONE
        videoClock.visibility = View.GONE
        videoPrev.visibility = View.GONE
        videoNext.visibility = View.GONE
        videoMiniLayer.visibility = View.VISIBLE
        playerView.useController = false
        Log.d("VidPlay", "进入 App 内小窗")
        webView.post { safeEval("if(window.__onMiniChange)window.__onMiniChange(true)") }
    }

    /** 退出小窗：展开回全屏播放（小窗上的⤢ / 点画面 / 返回键 都走这里） */
    private fun exitMini() {
        if (!miniMode) return
        miniMode = false
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        overlay.layoutParams = lp
        videoMiniLayer.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        setKeepScreenOn(player?.isPlaying == true)
        playerView.useController = true
        clockTick.run()
        updatePipButton()
        Log.d("VidPlay", "退出 App 内小窗：回全屏播放")
        webView.post { safeEval("if(window.__onMiniChange)window.__onMiniChange(false)") }
    }

    /** 计算并应用小窗尺寸/位置（按视频宽高比，默认右下角；竖屏视频则收窄一点） */
    private fun applyMiniLayout() {
        val dm = resources.displayMetrics
        val vs = player?.videoSize
        val ar = if (vs != null && vs.width > 0 && vs.height > 0) vs.width.toFloat() / vs.height else 16f / 9f
        var w = (dm.widthPixels * if (ar >= 1f) 0.46f else 0.38f).toInt()
        var h = (w / ar).toInt()
        val maxH = (dm.heightPixels * 0.45f).toInt()
        if (h > maxH) { h = maxH; w = (h * ar).toInt() }
        val m = (12 * dm.density).toInt()
        val bottom = (56 * dm.density).toInt()
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        // 关键：必须用 TOP|START + leftMargin/topMargin 定位。
        // 用 BOTTOM|END 时 FrameLayout 完全忽略 leftMargin/topMargin，
        // 拖动改的就是这两个值 → 表现为「小窗固定在右下角，怎么拖都不动」。
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = w
        lp.height = h
        if (!miniUserMoved) {
            // 默认右下角（用户还没拖过）
            lp.leftMargin = (dm.widthPixels - w - m).coerceAtLeast(0)
            lp.topMargin = (dm.heightPixels - h - bottom).coerceAtLeast(0)
        } else {
            // 用户拖过：保留位置，只按新尺寸夹回屏幕内（转屏/换清晰度时不再被弹回右下角）
            lp.leftMargin = lp.leftMargin.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
            lp.topMargin = lp.topMargin.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        }
        overlay.layoutParams = lp
    }

    /** 触摸点是否落在小窗上的按钮（⤢ 展开 / ✕ 关闭）上 */
    private fun hitMiniButton(ev: MotionEvent): Boolean {
        fun hit(v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
                ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
        }
        return hit(videoMiniBtn) || hit(videoMiniClose)
    }

    /** 拖动小窗（在父容器范围内移动） */
    private fun moveMiniBy(dx: Float, dy: Float) {
        val parent = overlay.parent as? View ?: return
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        val maxL = (parent.width - lp.width).coerceAtLeast(0)
        val maxT = (parent.height - lp.height).coerceAtLeast(0)
        val nl = (lp.leftMargin + dx).toInt().coerceIn(0, maxL)
        val nt = (lp.topMargin + dy).toInt().coerceIn(0, maxT)
        if (nl != lp.leftMargin || nt != lp.topMargin) {
            lp.leftMargin = nl
            lp.topMargin = nt
            miniUserMoved = true
            overlay.layoutParams = lp
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏后重算小窗尺寸，避免小窗超出屏幕
        if (miniMode) applyMiniLayout()
    }

    // ===================== 系统小窗（PiP） =====================
    /** 进系统小窗：按 Home 等离开 App 时视频继续悬浮播放 */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("系统版本不支持小窗")
            return
        }
        // 从 App 内小窗接续到系统小窗：先把播放层恢复整屏，否则系统小窗里只看到一小块画面
        if (miniMode) restoreOverlayFullSize()
        val ratio = player?.videoSize?.let {
            if (it.width > 0 && it.height > 0) Rational(it.width, it.height) else null
        } ?: Rational(16, 9)
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
            Log.d("VidPlay", "进入小窗 ratio=$ratio")
        } catch (e: Exception) {
            Log.w("VidPlay", "进入小窗失败: ${e.message}")
            toast("进入小窗失败")
        }
    }

    /** 按 Home 键回桌面时：不再自动进系统小窗(PiP)。
     *  原因：MEmu 上系统小窗被"×"/返回关掉后，Activity 进后台、播放器却不释放，
     *  出现"画面没了、后台还在响"。悬浮播放体验已由「App 内小窗」(enterMini) 承载；
     *  离开 App 时统一在 onPause → pauseIfHidden 里延迟停播，杜绝后台声音。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 历史逻辑（已停用）：if (overlay.visibility == View.VISIBLE && player?.isPlaying == true) enterPip()
    }

    /** 小窗被"×"/返回关掉后的处理：把 Activity 拉回前台，全屏继续播（用户要的不是黑屏+后台声音） */
    private val pipDismissRestore = object : Runnable {
        override fun run() {
            if (!pipRestorePending) return
            pipRestorePending = false
            // 已经自己回前台了（用户点小窗画面展开的）→ 不用管
            if (activityResumed) return
            // 没有在播了 → 也没必要拉回来
            if (player?.isPlaying != true) return
            Log.d("VidPlay", "小窗已被关闭：拉回前台全屏播放")
            try {
                startActivity(Intent(this@VideoActivity, VideoActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Log.w("VidPlay", "拉回前台失败: ${e.message}")
            }
            // 兜底：系统若禁止后台启动 Activity（拉不回来），2 秒后停掉播放，
            // 绝不留"画面没了、后台还在响"。
            mainHandler.postDelayed({
                if (!activityResumed && player?.isPlaying == true) {
                    Log.d("VidPlay", "未能拉回前台：停掉后台播放")
                    player?.pause()
                }
            }, 2000)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        @Suppress("DEPRECATION")
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        // 关键排查日志：进/出小窗时若伴随 onCreate/onDestroy，说明 Activity 被重建
        // （manifest configChanges 没写全的症状），播放器会被释放、小窗里就没画面
        Log.d("VidPlay", "onPictureInPictureModeChanged: inPip=$isInPictureInPictureMode")
        // 小窗中禁用 App 的返回键拦截：让系统默认处理（小窗里按返回/点关闭 → 展开回全屏播放），
        // 否则会被 handleOnBackPressed 的 closeVideo() 把播放层关掉，出现"画面没了、后台还在响"。
        backCallback?.isEnabled = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            wasMiniBeforePip = miniMode
            videoPip.visibility = View.GONE
            pipRestorePending = false
        } else {
            // 退出系统小窗：按"进小窗前的形态"恢复（App 内小窗 or 全屏），别让画面消失
            if (overlay.visibility != View.VISIBLE) overlay.visibility = View.VISIBLE
            if (wasMiniBeforePip && miniMode) {
                // 之前在 App 内小窗 → 继续小窗形态
                wasMiniBeforePip = false
                applyMiniLayout()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                showSystemBars()
                setKeepScreenOn(false)
                videoMiniLayer.visibility = View.VISIBLE
                videoPip.visibility = View.GONE
                videoClock.visibility = View.GONE
                videoPrev.visibility = View.GONE
                videoNext.visibility = View.GONE
                playerView.useController = false
            } else {
                // 之前是全屏播放 → 恢复全屏
                wasMiniBeforePip = false
                setKeepScreenOn(player?.isPlaying == true)
                updatePipButton()
                if (miniMode) exitMini() else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            // 退出小窗有两种情况：
            //  a) 用户点了小窗画面想展开 → Activity 会自然 onResume 回来；
            //  b) 用户点了小窗的"×"或按返回把小窗关掉 → Activity 留在后台，画面全无、只剩声音。
            // 这里延后 ~600ms 判定：若仍没回前台，就把 Activity 拉回前台，
            // 实现"关闭小窗 → 回到播放界面继续播"，而不是黑屏 + 后台响。
            if (player?.isPlaying == true) {
                pipRestorePending = true
                mainHandler.postDelayed(pipDismissRestore, 600)
            }
        }
        val v = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        videoClock.visibility = if (miniMode) View.GONE else v
        videoPrev.visibility = if (miniMode) View.GONE else v
        videoNext.visibility = if (miniMode) View.GONE else v
        // PlayerView 自带的播放控制条也隐藏：系统小窗只几寸大，控制条会糊满画面；
        // 退出小窗恢复（useController 切换会重建 SubtitleView 等子视图，必须主线程）
        playerView.useController = !isInPictureInPictureMode && !miniMode
        if (!isInPictureInPictureMode) clockTick.run()
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
        activityResumed = false
        setKeepScreenOn(false)
        Log.d("VidPlay", "onPause inPip=$isInPictureInPictureMode float=${VideoFloatState.running} playing=${player?.isPlaying}")
        // 进小窗/悬浮窗时 activity 也会 onPause，此时不能停播
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
        if (VideoFloatState.running) return   // 悬浮窗在别的页面继续播，别停
        saveHisPosition()
        hisHandler.removeCallbacks(hisTick)
        // 延后停播：关小窗时的回调顺序是 onPause → onPictureInPictureModeChanged(false) → onStop，
        // 若在 onPause 里立刻停，用户就看不到"关小窗→回全屏继续播"了。
        // 300ms 后若仍未回前台、也不在小窗里，才真正停（杜绝后台声音）。
        mainHandler.postDelayed(pauseIfHidden, 300)
    }

    /** 延后停播：确认真的回到后台了才停，避免误停小窗/正在拉回前台的播放 */
    private val pauseIfHidden = object : Runnable {
        override fun run() {
            if (activityResumed) return
            if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
            if (VideoFloatState.running) return   // 悬浮窗正在别的页面播，不算"回后台"
            if (pipRestorePending) return
            if (player?.isPlaying == true) {
                Log.d("VidPlay", "已回后台且未在小窗：停掉播放")
                player?.pause()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("VidPlay", "onStop inPip=$isInPictureInPictureMode float=${VideoFloatState.running} playing=${player?.isPlaying} pipRestorePending=$pipRestorePending")
        // 被切到后台又不在小窗里（小窗被"×"关掉、或小窗里按返回退到后台）：
        // 停掉播放，避免出现"画面没了、后台还在响"。（按 Home 进的小窗会 isInPictureInPictureMode=true，不受影响，继续悬浮播）
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
        // 系统悬浮窗正在别的页面/别的 App 上播，这是用户要的效果，绝不能停
        if (VideoFloatState.running) {
            Log.d("VidPlay", "onStop：悬浮窗播放中，不停播")
            return
        }
        // 小窗刚被关掉、正在拉回前台全屏播放 → 不能停播（否则"回到全屏"会变成暂停/黑屏）
        if (pipRestorePending) {
            Log.d("VidPlay", "onStop：小窗关闭中，等待拉回前台，不停播")
            return
        }
        if (player?.isPlaying == true) {
            Log.d("VidPlay", "onStop 且非小窗：停掉后台播放")
            player?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        Log.d("VidPlay", "onResume playing=${player?.isPlaying} float=${VideoFloatState.running}")
        // 悬浮窗还在（用户在别的页面看过又回来）：保持悬浮，不抢回全屏
        if (VideoFloatState.running) {
            floatWasRunning = true
            return
        }
        // 悬浮窗已被 ✕ 关掉（之前在悬浮）：把页面/播放器状态收干净，别留后台声音
        if (floatWasRunning) {
            floatWasRunning = false
            Log.d("VidPlay", "悬浮窗已关闭：收起播放器并恢复页面")
            if (overlay.visibility == View.VISIBLE || player?.isPlaying == true) closeVideo()
        }
        // 回到前台：播放层还开着就继续 5 秒存点
        if (curHisKey.isNotBlank() && overlay.visibility == View.VISIBLE) hisTick.run()
    }

    override fun onDestroy() {
        Log.d("VidPlay", "onDestroy")
        super.onDestroy()
        setKeepScreenOn(false)
        webViewAlive = false
        saveHisPosition()
        clockHandler.removeCallbacks(clockTick)
        hisHandler.removeCallbacks(hisTick)
        mainHandler.removeCallbacksAndMessages(null)
        if (VideoFloatState.running) {
            // 悬浮窗还在播：播放器已归服务所有，这里绝不能 release（否则浮层立刻黑掉）
            player = null
            Log.d("VidPlay", "onDestroy：悬浮窗播放中，播放器交给服务")
        } else {
            player?.release()
            player = null
        }
        webView.destroy()
    }
}
