package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Typeface
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import kotlin.math.abs
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
    private lateinit var videoMiniZoomIn: TextView
    private lateinit var videoMiniZoomOut: TextView
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
    /** 小窗手势缩放倍数（双指捏合调整），默认 1.0 */
    private var miniScale = 1.0f
    /** 是否正在双指捏合缩放 */
    private var pinching = false
    /** 捏合起始两指间距 */
    private var pinchStartDist = 0f
    /** 捏合起始缩放倍数 */
    private var pinchStartScale = 1.0f

    // ===================== 片头 / 片尾跳过（按影片详情维度） =====================
    /**
     * 当前影片详情的配置 key（"d:<站点api>|<vod_id>" / "s:<源key>|<vod_id>"，
     * 由起播 metaJson 推导，即历史 key 去掉集名）。同一影片下的所有剧集/线路
     * 共用一份跳过配置；其他影片 key 不同，互不影响。
     */
    private var curDetailKey: String = ""
    /** 开始播放时间（毫秒）：起播/切集时自动 seek 到这里 */
    private var skipStartMs: Long = 0
    /** 结束播放时间（毫秒）：到点自动下一集，已是最后一集则暂停 */
    private var skipEndMs: Long = 0
    private lateinit var skipStartBtn: TextView
    private lateinit var skipEndBtn: TextView
    /** 控制条上的投屏入口 */
    private lateinit var castBtn: TextView
    /** 用户手动把进度拖过结束时间：本次播放不再自动跳（换片/重新起播后重置） */
    private var skipEndManual = false
    /**
     * 片尾跳过「武装」标记：只有新 media 起播（STATE_READY）且进度还没到片尾时才为 true，
     * 触发一次立即卸武装。切集后 currentPosition 不会立刻重置（还是上一集的位置），
     * 没有这个标记会在缓冲期间用旧进度连续触发，一路空跳到最后一集。
     */
    private var skipEndArmed = false
    /** 片尾到点轮询（500ms）：没配置或没在播时自动停掉，不空转 */
    private val skipTick = object : Runnable {
        override fun run() {
            if (skipEndMs <= 0 || player == null) return
            checkSkipEnd()
            mainHandler.postDelayed(this, 500)
        }
    }

    private var backCallback: OnBackPressedCallback? = null
    private var player: ExoPlayer? = null

    // ===================== DLNA 投屏 =====================
    /** true 时本地播放器已停，播放位置来自电视端轮询，手机变遥控器 */
    private var casting = false
    private var castDevice: DlnaDevice? = null
    private var castCtrl: DlnaController? = null
    /** 当前直链 + 标题（投屏/切集重投都用；爬虫源的集不含直链，投屏中切集受限） */
    private var curCastUrl = ""
    private var curCastTitle = ""
    /** 电视端轮询出来的播放状态 */
    private var castPosMs = 0L
    private var castDurMs = 0L
    private var castPlaying = false
    /** 用户正在拖遥控进度条：暂停轮询回写，松手再 seek，避免边拖边回跳 */
    private var castTouchingSeek = false
    /** 轮询连续失败计数，达到阈值认为掉线，自动切回本地 */
    private var castFailCount = 0
    private lateinit var castLayer: LinearLayout
    private lateinit var castStatus: TextView
    private lateinit var castExit: TextView
    private lateinit var castPlayPause: TextView
    private lateinit var castHint: TextView
    private lateinit var castSeek: SeekBar
    private lateinit var castPosTv: TextView
    private lateinit var castDurTv: TextView
    private lateinit var castPrev: TextView
    private lateinit var castNext: TextView
    /** 投屏遥控层进度/状态轮询（1s）：同时驱动片头片尾跳过（checkSkipEnd 用 currentPosMs） */
    private val castTick = object : Runnable {
        override fun run() {
            if (!casting) return
            val c = castCtrl ?: return
            c.getPositionInfo { pos, dur, state ->
                if (!casting) return@getPositionInfo
                if (state == "ERR") {
                    castFailCount++
                    if (castFailCount >= 5) {
                        toast("投屏设备无响应，已切回本地播放")
                        stopCast(true)
                        return@getPositionInfo
                    }
                } else castFailCount = 0
                castPosMs = pos
                castDurMs = dur
                if (!castTouchingSeek && dur > 0) {
                    castSeek.max = dur.toInt()
                    castSeek.progress = pos.toInt()
                    castPosTv.text = fmtTime(pos)
                    castDurTv.text = fmtTime(dur)
                }
                // 片尾到点：有下一集接着投，最后一集暂停
                checkSkipEnd()
            }
            mainHandler.postDelayed(this, 1000)
        }
    }
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
        ThemePrefs.applyTheme(this)
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
        videoMiniZoomIn = findViewById(R.id.videoMiniZoomIn)
        videoMiniZoomOut = findViewById(R.id.videoMiniZoomOut)
        miniTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        // 片头/片尾跳过配置按钮（在 media3 控制条进度条下方，见 exo_player_control_view.xml）
        skipStartBtn = playerView.findViewById(R.id.skipStartBtn)
        skipEndBtn = playerView.findViewById(R.id.skipEndBtn)
        skipStartBtn.setOnClickListener { showSkipTimeDialog(true) }
        skipEndBtn.setOnClickListener { showSkipTimeDialog(false) }
        // 投屏按钮（控制条进度条下方第三个 chip）
        castBtn = playerView.findViewById(R.id.castBtn)
        castBtn.setOnClickListener { showCastDevices() }
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
        // 小窗缩放：＋/－ 改 miniScale（兜底；双指捏合在部分 ROM 收不到第二指时也能调尺寸）
        videoMiniZoomIn.setOnClickListener { zoomMini(1.18f) }
        videoMiniZoomOut.setOnClickListener { zoomMini(0.85f) }

        // ===================== DLNA 投屏遥控层 =====================
        castLayer = findViewById(R.id.castLayer)
        castStatus = findViewById(R.id.castStatus)
        castExit = findViewById(R.id.castExit)
        castPlayPause = findViewById(R.id.castPlayPause)
        castHint = findViewById(R.id.castHint)
        castSeek = findViewById(R.id.castSeek)
        castPosTv = findViewById(R.id.castPos)
        castDurTv = findViewById(R.id.castDur)
        castPrev = findViewById(R.id.castPrev)
        castNext = findViewById(R.id.castNext)
        // 退出投屏：停电视播放，从当前进度切回本地继续播
        castExit.setOnClickListener { stopCast(true) }
        castPlayPause.setOnClickListener {
            val c = castCtrl ?: return@setOnClickListener
            if (castPlaying) c.pause { ok, msg ->
                if (ok) { castPlaying = false; castPlayPause.text = "▶" }
                else toast(msg.ifBlank { "暂停失败" })
            } else c.play { ok, msg ->
                if (ok) { castPlaying = true; castPlayPause.text = "⏸" }
                else toast(msg.ifBlank { "播放失败" })
            }
        }
        castSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                // 拖动中只本地预览时间，不回写 castPosMs（防轮询把 UI 拽回去）
                if (fromUser) castPosTv.text = fmtTime(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { castTouchingSeek = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                castTouchingSeek = false
                castCtrl?.seekTo(sb.progress.toLong()) { ok, msg ->
                    if (!ok) toast(msg.ifBlank { "跳转失败" })
                }
            }
        })
        castPrev.setOnClickListener {
            if (curIdx <= 0) toast("已经是第一集") else playIndex(curIdx - 1)
        }
        castNext.setOnClickListener { playNext() }
        // 小窗拖动 + 点击展开 + 双指捏合缩放（手指放大/缩小播放窗口）
        videoMiniLayer.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 落在 ⤢/✕ 按钮上：交给按钮自己处理（父层若返回 true 会吞掉点击，
                    // 按钮的 OnClick 永远不触发——✕ 会变成"展开"而不是"关闭"）
                    if (hitMiniButton(ev)) { miniDragged = false; return@setOnTouchListener false }
                    // 捏合中第二指已抬起，第一指重新落下：继续吞掉，别误判成"点击展开"
                    if (pinching) { miniDownX = ev.rawX; miniDownY = ev.rawY; return@setOnTouchListener true }
                    miniDownX = ev.rawX; miniDownY = ev.rawY; miniDragged = false; true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 第二指落下：进入捏合缩放
                    if (ev.pointerCount >= 2) { startPinch(ev); true } else false
                }
                MotionEvent.ACTION_MOVE -> {
                    when {
                        pinching && ev.pointerCount >= 2 -> { doPinch(ev); true }
                        pinching -> true // 捏合中暂时不足两指：吞掉，避免误拖动
                        else -> {
                            val dx = ev.rawX - miniDownX
                            val dy = ev.rawY - miniDownY
                            if (!miniDragged && (abs(dx) > miniTouchSlop || abs(dy) > miniTouchSlop)) miniDragged = true
                            if (miniDragged) { moveMiniBy(dx, dy); miniDownX = ev.rawX; miniDownY = ev.rawY }
                            true
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 抬起一只手导致只剩一指：退出捏合，并标记为已拖动，
                    // 这样最后那只手抬起时不会被误判成"点击展开回全屏"
                    if (pinching && ev.pointerCount < 2) {
                        pinching = false
                        miniDragged = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (pinching) { pinching = false; true }       // 捏合收尾，不展开
                    else if (!miniDragged) { exitMini(); true }    // 单指轻点 → 展开回全屏
                    else true
                }
                MotionEvent.ACTION_CANCEL -> { pinching = false; miniDragged = false; true }
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
                        // 片头跳过：没带续播位置、当前还在片头区间 → 直接跳到开始时间
                        // （切集时每集都从头开始，所以这里对每一集都生效）
                        if (state == Player.STATE_READY && skipStartMs > 0 &&
                            seekAfterReady <= 0 && it.currentPosition < skipStartMs
                        ) {
                            runCatching { it.seekTo(skipStartMs) }
                            Log.d("VidPlay", "片头跳过 seekTo=$skipStartMs ms")
                        }
                        // 片尾跳过武装：新 media 起播后才允许触发一次
                        // （放在片头 seek 之后，让 currentPosition 反映跳过后的位置）
                        if (state == Player.STATE_READY) {
                            skipEndArmed = skipEndMs > 0 && it.currentPosition < skipEndMs
                        }
                        // 播完自动下一集（影视仓主流体验）
                        if (state == Player.STATE_ENDED) playNext()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        // 用户手动拖到结束时间之后：这次不自动跳下一集
                        // （尊重意愿；换片/重新起播时 reset）
                        if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                            skipEndMs > 0 && newPosition.positionMs >= skipEndMs
                        ) {
                            skipEndManual = true
                            Log.d("VidPlay", "手动拖动越过片尾时间：本次不再自动跳集")
                        }
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
        curCastTitle = title          // 投屏时作为 DIDL-Lite 标题展示在电视端
        if (epsJson.isNotBlank()) epsList = parseEps(epsJson)
        if (referer.isNotBlank()) curReferer = referer
        if (idx >= 0) curIdx = idx
        // 按影片详情取片头/片尾跳过配置（同一影片所有剧集/线路共用，其他影片不受影响）
        val meta = runCatching {
            if (metaJson.isBlank()) null else JSONObject(metaJson)
        }.getOrNull()
        curDetailKey = detailKeyOf(meta)
        loadSkipConfig()
        recordHistory(url, title, referer, epsJson, idx, spiderJson, meta)
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
        meta: JSONObject?
    ) {
        var key = ""; var pic = ""; var epName = ""
        var vodId = ""; var siteApi = ""; var from = ""
        if (meta != null) {
            key = meta.optString("key"); pic = meta.optString("pic"); epName = meta.optString("epName")
            vodId = meta.optString("vodId"); siteApi = meta.optString("siteApi"); from = meta.optString("from")
        }
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
        val pos = currentPosMs()
        if (pos <= 0) return
        val dur = if (casting) castDurMs
        else runCatching { player?.duration }.getOrNull()?.takeIf { it > 0 } ?: 0L
        VideoHistoryStore.updatePosition(this, curHisKey, pos, dur)
    }

    // ===================== 片头 / 片尾跳过 =====================
    /**
     * 影片详情标识：历史 key 去掉集名的部分（"d:<站点api>|<vod_id>" /
     * "s:<源key>|<vod_id>"）。同一影片下的所有剧集、所有线路共用一份配置；
     * 不同影片 key 不同，互不影响。推导不出来返回空串（该影片不启用跳过）。
     */
    private fun detailKeyOf(meta: JSONObject?): String {
        if (meta == null) return ""
        val vodId = meta.optString("vodId").trim()
        val siteApi = meta.optString("siteApi").trim()
        val hisKey = meta.optString("key").trim()
        val spider = hisKey.startsWith("s:")
        // 爬虫源详情没带 siteApi：站点身份取 hisKey 里 "s:" 与第一个 "|" 之间的源 key
        val site = if (siteApi.isNotBlank()) siteApi
        else if (spider) hisKey.removePrefix("s:").substringBefore('|') else ""
        if (site.isBlank() && vodId.isBlank()) return ""
        return (if (spider) "s:" else "d:") + site + "|" + vodId
    }

    /** 起播时按详情 key 读配置（切集不重新读：同一影片配置不变） */
    private fun loadSkipConfig() {
        val cfg = if (curDetailKey.isBlank()) null else VideoSkipStore.get(this, curDetailKey)
        skipStartMs = cfg?.startMs ?: 0
        skipEndMs = cfg?.endMs ?: 0
        skipEndManual = false
        skipEndArmed = false
        updateSkipButtons()
        Log.d("VidPlay", "跳过配置 key=$curDetailKey 片头=$skipStartMs 片尾=$skipEndMs")
    }

    /** 刷新进度条下方两个配置按钮的文案/高亮 */
    private fun updateSkipButtons() {
        fun bind(btn: TextView, ms: Long, label: String) {
            btn.text = if (ms > 0) "$label ${fmtTime(ms)}" else "$label 未设置"
            btn.setTextColor(if (ms > 0) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
            btn.setTypeface(null, if (ms > 0) Typeface.BOLD else Typeface.NORMAL)
        }
        bind(skipStartBtn, skipStartMs, "片头")
        bind(skipEndBtn, skipEndMs, "片尾")
    }

    /** 毫秒 → "m:ss" / "h:mm:ss" */
    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    /**
     * 解析用户输入的时间："90"→90 秒；"1:30"→90 秒；"01:01:30"→3690 秒；
     * 空串→0（清除）；解析不了返回 -1。
     */
    private fun parseTime(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        if (t.all { it.isDigit() }) return t.toLong() * 1000
        val parts = t.split(":")
        if (parts.size !in 2..3) return -1
        val nums = parts.map { it.trim().toLongOrNull() ?: return -1 }
        return when (nums.size) {
            2 -> (nums[0] * 60 + nums[1]) * 1000
            else -> (nums[0] * 3600 + nums[1] * 60 + nums[2]) * 1000
        }
    }

    private fun startSkipTick() {
        // 只 post 不立即 run：playMediaUrl→playIndex→playMediaUrl 的调用链里
        // 同步执行会把整个剧集列表瞬间空跳一遍
        mainHandler.removeCallbacks(skipTick)
        mainHandler.postDelayed(skipTick, 500)
    }

    private fun stopSkipTick() {
        mainHandler.removeCallbacks(skipTick)
    }

    /**
     * 当前播放位置（毫秒）：本地播时取 ExoPlayer，投屏时取电视端轮询位置。
     * 片头/片尾跳过、历史断点都走这个口，两条播放路径行为一致。
     */
    private fun currentPosMs(): Long =
        if (casting) castPosMs
        else runCatching { player?.currentPosition }.getOrNull() ?: 0L

    /** 到结束时间：有下一集就接着播，最后一集（或单集影片）则停在结束点 */
    private fun checkSkipEnd() {
        if (skipEndMs <= 0 || skipEndManual || !skipEndArmed) return
        val pos = currentPosMs()
        if (pos < skipEndMs) return
        // 触发一次就卸武装：杜绝「切集后位置没重置 → 连续跳集」和「暂停后反复弹提示」
        skipEndArmed = false
        val hasNext = curIdx in 0 until epsList.size - 1
        Log.d("VidPlay", "到结束播放时间 $skipEndMs → ${if (hasNext) "下一集" else "暂停"}")
        if (hasNext) {
            toast("已到结束播放时间，进入下一集")
            playIndex(curIdx + 1)
        } else {
            if (casting) castCtrl?.pause { _, _ -> } else player?.pause()
            toast("已到设置的结束播放时间")
        }
    }

    /**
     * 片头 / 片尾时间设置弹窗。配置按影片详情维度保存：
     * 对本影片所有剧集/线路生效，其他影片不受影响。
     */
    private fun showSkipTimeDialog(isStart: Boolean) {
        val label = if (isStart) "片头" else "片尾"
        val cur = if (isStart) skipStartMs else skipEndMs
        val input = EditText(this).apply {
            setText(if (cur > 0) fmtTime(cur) else "")
            hint = "mm:ss 或 hh:mm:ss，留空=不跳过"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        // 快捷档位：片头给固定值；片尾按当前时长给「距结尾 X 分钟」
        val dur = runCatching { player?.duration }.getOrNull() ?: 0L
        val quicks: List<Pair<String, Long>> =
            if (isStart) listOf("30秒" to 30_000L, "1分" to 60_000L, "1分半" to 90_000L, "2分" to 120_000L)
            else buildList {
                if (dur > 0) {
                    add("剩3分" to (dur - 180_000L))
                    add("剩5分" to (dur - 300_000L))
                    add("剩10分" to (dur - 600_000L))
                }
            }
        if (quicks.isNotEmpty()) {
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            (quicks + ("清除" to 0L)).forEach { (text, ms) ->
                val chip = TextView(this).apply {
                    this.text = text
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    // 弹窗是浅色卡片：用深字 + 淡灰底（白字白底会完全看不见）
                    setTextColor(0xDE000000.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(14).toFloat()
                        setColor(0x14000000)
                    }
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener { input.setText(if (ms > 0) fmtTime(ms) else "") }
                }
                chipRow.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                )
            }
            box.addView(chipRow)
        }
        AlertDialog.Builder(this)
            .setTitle("设置$label 播放时间")
            .setMessage("对本影片的所有剧集/线路生效，其他影片不受影响")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val ms = parseTime(input.text.toString())
                if (ms < 0) toast("时间格式不对，示例：1:30")
                else applySkipTime(isStart, ms)
            }
            .show()
    }

    /** 保存并应用片头/片尾配置 */
    private fun applySkipTime(isStart: Boolean, ms: Long) {
        var s = skipStartMs
        var e = skipEndMs
        if (isStart) s = ms else e = ms
        val dur = runCatching { player?.duration }.getOrNull() ?: 0L
        if (s > 0 && e > 0 && e <= s) { toast("结束时间要晚于开始时间"); return }
        if (dur > 0 && s > 0 && s >= dur) { toast("开始时间超过了影片时长"); return }
        if (dur > 0 && e > 0 && e > dur) { toast("结束时间超过了影片时长"); return }
        if (curDetailKey.isBlank()) { toast("当前影片无法记录配置"); return }
        VideoSkipStore.save(this, curDetailKey, s, e)
        skipStartMs = s
        skipEndMs = e
        skipEndManual = false
        updateSkipButtons()
        val pos = runCatching { player?.currentPosition }.getOrNull() ?: 0L
        if (skipEndMs > 0) {
            startSkipTick()
            // 播放中补设片尾：只要当前进度还没到，这一集也立刻生效
            skipEndArmed = pos < skipEndMs
        } else {
            stopSkipTick()
            skipEndArmed = false
        }
        // 片头设在当前进度之前：立刻跳过去（不用等下一集）
        if (isStart && s > 0 && pos < s) runCatching { player?.seekTo(s) }
        // 片尾设在当前进度之前：这次不自动跳（用户明显是想往前看），换片后恢复
        if (!isStart && e > 0 && pos > e) skipEndManual = true
        toast("已设置：片头 ${if (s > 0) fmtTime(s) else "无"} / 片尾 ${if (e > 0) fmtTime(e) else "无"}")
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
        curCastUrl = url              // 投屏用：记住当前直链，随时可投/切回本地续投
        skipEndManual = false          // 换片：手动拖动越过片尾的豁免重置
        skipEndArmed = false           // 换片：等新 media STATE_READY 再武装，防旧进度误触发
        if (skipEndMs > 0) startSkipTick()
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

    // ===================== DLNA 投屏 =====================

    /** 投屏按钮：先组播搜索局域网渲染器，再选设备 */
    private fun showCastDevices() {
        if (casting) { toast("正在投屏中，先退出再换设备"); return }
        if (curCastUrl.isBlank()) { toast("请先播放视频再投屏"); return }
        val pd = AlertDialog.Builder(this)
            .setTitle("投屏")
            .setMessage(
                "正在搜索同一 Wi-Fi 下的电视 / 盒子…\n\n" +
                    "搜不到时请检查：手机与电视在同一网络、路由器没开 AP 隔离"
            )
            .setNegativeButton("取消", null)
            .create()
        pd.show()
        DlnaScanner(this).scan { devices ->
            if (isFinishing || isDestroyed) return@scan
            runCatching { pd.dismiss() }
            if (devices.isEmpty()) {
                toast("未发现可投屏设备")
                return@scan
            }
            AlertDialog.Builder(this)
                .setTitle("选择投屏设备")
                .setItems(
                    devices.map { "${it.name}  (${it.ip})" }.toTypedArray()
                ) { _, i -> startCast(devices[i]) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 开始投屏：本地停播，手机切遥控层，位置轮询接管进度 */
    private fun startCast(d: DlnaDevice) {
        Log.d("VidCast", "投屏到 ${d.name} (${d.ip}) url=$curCastUrl")
        castDevice = d
        castCtrl = DlnaController(d)
        casting = true
        castPosMs = 0
        castDurMs = 0
        castPlaying = false
        castFailCount = 0
        castTouchingSeek = false
        castSeek.progress = 0
        castSeek.max = 100
        castPosTv.text = "00:00"
        castDurTv.text = "00:00"
        castStatus.text = "📺 正在投屏：${d.name}"
        castPlayPause.text = "⏸"
        castHint.text = if (curCastUrl.contains(".m3u8", true)) {
            "部分电视不支持 m3u8 直链，放不出来请退出用本地播放"
        } else {
            "画面在电视上播放，手机可作遥控"
        }
        castLayer.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        videoPip.visibility = View.GONE
        stopSkipTick()                       // 本地轮询停，位置改由 castTick 提供
        player?.pause()
        player?.stop()
        castCtrl?.cast(curCastUrl, curCastTitle) { ok, msg ->
            if (!casting) return@cast
            if (!ok) {
                toast("投屏失败：${msg.ifBlank { "设备拒绝播放" }}")
                stopCast(true)
                return@cast
            }
            castPlaying = true
            skipEndArmed = skipEndMs > 0     // 和本地 STATE_READY 武装一致
            mainHandler.removeCallbacks(castTick)
            castTick.run()
        }
    }

    /**
     * 停止投屏。backLocal=true 时从电视端当前进度切回本地继续播（退出/掉线都用它）；
     * backLocal=false 只停电视（关播放器场景）。
     */
    private fun stopCast(backLocal: Boolean) {
        if (!casting) return
        val resume = castPosMs
        casting = false
        mainHandler.removeCallbacks(castTick)
        castCtrl?.stop { _, _ -> }
        castCtrl = null
        castDevice = null
        castLayer.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        updatePipButton()
        if (backLocal && curCastUrl.isNotBlank()) {
            seekAfterReady = resume             // STATE_READY 时 seek 到电视端进度
            playMediaUrl(curCastUrl, curReferer)
            toast("已切回本地播放")
        }
    }

    /** 切到第 i 集：直链直接播；爬虫源回传 JS 经 playerContent 解析后再起播 */
    private fun playIndex(i: Int) {        if (i < 0 || i >= epsList.size) return
        val ep = epsList[i]
        curIdx = i
        // 投屏中切集：把新集重新推给电视（旧 URI 先 Stop 再 SetAVTransportURI）
        if (casting) {
            if (ep.spider) {
                toast("投屏中不能切换爬虫源的剧集，请先退出投屏")
                return
            }
            Log.d("VidPlay", "投屏切集 -> ${ep.name}")
            curCastUrl = ep.url
            curCastTitle = ep.name
            castPosMs = 0
            castDurMs = 0
            castSeek.progress = 0
            castPosTv.text = "00:00"
            castDurTv.text = "00:00"
            castPlayPause.text = "⏸"
            castCtrl?.cast(ep.url, ep.name) { ok, msg ->
                if (ok) {
                    castPlaying = true
                    castFailCount = 0
                    skipEndArmed = skipEndMs > 0
                } else {
                    toast("切换失败：${msg.ifBlank { "设备拒绝" }}")
                    stopCast(true)
                }
            }
            VideoHistoryStore.updateEpisode(this, curHisKey, ep.name, i, ep.url)
            curHisRec?.put("epName", ep.name)?.put("idx", i)?.put("url", ep.url)?.put("positionMs", 0)
            return
        }
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
        if (casting) stopCast(false)   // 关播放器：电视端也要停，但不切回本地
        saveHisPosition()
        hisHandler.removeCallbacks(hisTick)
        stopSkipTick()
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
        miniScale = 1.0f        // 每次进小窗回到默认大小，缩放从 1.0 重新开始
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

    /** 计算并应用小窗尺寸/位置（按视频宽高比，默认右下角；竖屏视频则收窄一点）。
     *  再叠加 miniScale（双指捏合缩放）：让用户把"太小了"的小窗放大到想要的大小。 */
    private fun applyMiniLayout() {
        val dm = resources.displayMetrics
        val vs = player?.videoSize
        val ar = if (vs != null && vs.width > 0 && vs.height > 0) vs.width.toFloat() / vs.height else 16f / 9f
        // 默认基准大小稍微放大一点（原 0.46/0.38 在手机上确实偏小），再用捏合倍数进一步调整
        var w = (dm.widthPixels * if (ar >= 1f) 0.5f else 0.42f).toInt()
        var h = (w / ar).toInt()
        val maxH = (dm.heightPixels * 0.5f).toInt()
        if (h > maxH) { h = maxH; w = (h * ar).toInt() }
        // 应用用户手势缩放（双指放大/缩小）：0.5x ~ 3x
        w = (w * miniScale).toInt()
        h = (h * miniScale).toInt()
        // 夹回屏幕范围内，最小 90px 防止缩到看不见
        w = w.coerceIn(90, dm.widthPixels - 24)
        h = h.coerceIn(90, dm.heightPixels - 24)
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

    /** 双指捏合：记录起始间距与起始缩放倍数 */
    private fun startPinch(ev: MotionEvent) {
        val d = pinchDist(ev)
        if (d > 0) {
            pinchStartDist = d
            pinchStartScale = miniScale
            pinching = true
            miniDragged = true    // 捏合期间不算"轻点"，避免松手误判成展开回全屏
            miniUserMoved = true  // 捏合时保持当前位置，别被 applyMiniLayout 弹回右下角
        }
    }

    /** 双指捏合：按两指间距变化实时缩放小窗 */
    private fun doPinch(ev: MotionEvent) {
        val d = pinchDist(ev)
        if (pinchStartDist > 0 && d > 0) {
            val s = (pinchStartScale * (d / pinchStartDist)).coerceIn(0.5f, 3.0f)
            miniScale = s
            applyMiniLayout()
        }
    }

    /** 两指间距（用相对坐标即可，比例与原点无关） */
    private fun pinchDist(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 触摸点是否落在小窗上的按钮（⤢ 展开 / ✕ 关闭 / ＋ － 缩放）上 */
    private fun hitMiniButton(ev: MotionEvent): Boolean {
        fun hit(v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
                ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
        }
        return hit(videoMiniBtn) || hit(videoMiniClose) ||
            hit(videoMiniZoomIn) || hit(videoMiniZoomOut)
    }

    /** 小窗缩放：按倍数调整 miniScale（保持 0.5x~3x），并保留当前位置 */
    private fun zoomMini(factor: Float) {
        miniScale = (miniScale * factor).coerceIn(0.5f, 3.0f)
        miniUserMoved = true
        applyMiniLayout()
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
        // DLNA 投屏收尾：标记先置 false，轮询与 SOAP 回调里的 `if (!casting) return` 全部短路，
        // 防止 Activity 销毁后协程回调再来碰 UI
        casting = false
        mainHandler.removeCallbacks(castTick)
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
