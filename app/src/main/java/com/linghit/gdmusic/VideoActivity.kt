package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.pm.ActivityInfo
import android.content.ContentUris
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
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

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
    /** 顶部居中的视频标题（片名 · 线路 · 集名），显隐跟 videoPip 同一套条件 */
    private lateinit var videoTitle: TextView
    private var controlsVisible = true
    private lateinit var videoClock: TextView
    private lateinit var videoPrev: TextView
    private lateinit var videoNext: TextView
    private lateinit var videoBuffering: TextView
    /** 横向拖动快进/后退时的居中提示（目标时间 + 快进/后退秒数） */
    private lateinit var videoSeekHint: TextView
    private lateinit var videoMiniLayer: FrameLayout
    private lateinit var videoMiniBtn: TextView
    private lateinit var videoMiniClose: TextView
    private lateinit var videoMiniZoomIn: TextView
    private lateinit var videoMiniZoomOut: TextView
    /** 左侧上下滑动调亮度时显示的百分比指示器 */
    private lateinit var brightnessInd: TextView
    /** 内嵌态 ↔ 全屏切换按钮（图标：展开 / 收起） */
    private lateinit var fullBtn: ImageButton
    /** 右侧上一集/下一集按钮容器：仅紧凑内嵌短条保留；全屏态上下集已搬到中间播放键左右 */
    private lateinit var sideBtns: LinearLayout
    /** 中间控制条里紧挨「播放 / 暂停」左右的上一集 / 下一集（全屏态主入口） */
    private lateinit var centerPrev: ImageButton
    private lateinit var centerNext: ImageButton
    /** 全屏左侧锁按钮：未锁跟控制条显隐，锁定后常驻用于解锁 */
    private lateinit var videoLock: TextView
    /** 全屏锁定态：隐藏全部 HUD + 吞掉屏幕手势，播放继续，只留左侧锁按钮可点 */
    private var locked = false
    /** 锁定前全屏键 / 时钟的可见性，解锁时原样恢复（不用 applyPlayerLayout 硬重置，
     *  那会连带改屏幕方向、系统栏、详情页占位，副作用太大） */
    private var fullBtnVisBeforeLock = View.GONE
    private var clockVisBeforeLock = View.GONE
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
    /** 内嵌播放模式：详情页起播默认把播放器 docking 在详情页顶部（竖屏、系统栏可见），
     *  下方 WebView 详情/选集仍可滚动浏览。false = 经典全屏（历史续播等）。 */
    private var inlineMode = false
    /** 内嵌态下是否已展开成横屏全屏：true 时铺满屏幕、隐藏系统栏；false 时只占顶部。 */
    private var inlineExpanded = false
    /** 内嵌播放器高度（dp），同时作为详情页顶部占位高度（CSS px ≈ dp）。 */
    private val INLINE_HEIGHT_DP = 230
    /**
     * 当前是否处于「紧凑内嵌短条」形态：播放器缩在详情页顶部（未展开全屏）。
     *
     * ⚠️ 别再用 inlineMode 当「隐藏小窗按钮」的条件 —— inlineMode 只表示「本次播放是从
     * 详情页内嵌起的」，展开成横屏全屏后它仍是 true，但那时布局已经和经典全屏一模一样，
     * 把按钮藏掉就出现了「影视播放时小窗按钮不见了」的回归。
     * 真正要区分的是「紧凑短条」还是「已展开全屏」，所以用这个派生属性。
     */
    private val isCompactInline: Boolean
        get() = inlineMode && !inlineExpanded
    /** 是否正在双指捏合缩放 */
    private var pinching = false
    /** 捏合起始两指间距 */
    private var pinchStartDist = 0f
    /** 捏合起始缩放倍数 */
    private var pinchStartScale = 1.0f

    // ===================== 播放手势：左侧上下滑动调亮度 =====================
    private var brightStartX = 0f
    private var brightStartY = 0f
    private var brightStartVal = -1f
    private var brightDecided = false
    private var brightActive = false
    private val BRIGHT_MIN = 0.02f
    private val BRIGHT_MAX = 1f
    private val BRIGHT_HIDE_DELAY = 800L
    private val brightHide = Runnable { if (::brightnessInd.isInitialized) brightnessInd.visibility = View.GONE }

    // ===================== 播放手势：右侧上下滑动调音量 =====================
    private lateinit var audioManager: AudioManager
    /** 右侧上下滑动调音量时显示的百分比指示器 */
    private lateinit var volumeInd: TextView
    private var volDecided = false
    private var volActive = false
    private var volStartVal = 0f   // 按下时的系统音量（0..max）
    private var volStartY = 0f
    private val VOL_HIDE_DELAY = 800L
    private val volHide = Runnable { if (::volumeInd.isInitialized) volumeInd.visibility = View.GONE }

    // ===================== 播放手势：长按屏幕 = 3 倍速播放 =====================
    /** 长按屏幕（按住不动）期间显示的倍速指示器 */
    private lateinit var speedInd: TextView
    /** 是否已处于长按 3 倍速状态 */
    private var longPressActive = false
    /** 长按前记录的原始倍速，松手后恢复 */
    private var origSpeed = 1f
    private var longPressTimer: android.os.CountDownTimer? = null
    /** 按下后多久（毫秒）判定为「长按」——短于它视为轻点 */
    private val LP_TRIGGER_MS = 450L

    // ===================== 耳机断开 = 暂停本地视频 =====================
    /** 监听 ACTION_AUDIO_BECOMING_NOISY：拔出/关掉有线耳机或断开蓝牙音频设备时，
     *  系统把音频路由切回扬声器并广播此意图。此时暂停本地播放器，避免外放泄露。
     *  App 进程级广播已覆盖音乐与悬浮窗视频，这里补上「本页前台播放」这一路。 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // 投屏中（画面在电视上）不暂停本地——手机此时只是遥控，本来就没出声
            runCatching { if (!casting) player?.pause() }
        }
    }

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
    /** 控制条上的倍速 chip：显示当前档位，点开选档（长按临时 3× 松手后回到该档位） */
    private lateinit var speedBtn: TextView
    /** 控制条上紧挨倍速的「选集」chip：点开右侧抽屉，列出当前线路全部剧集 */
    private lateinit var episodesBtn: TextView
    /** 全屏选集抽屉（activity_video.xml）：遮罩 / 卡片 / 关闭 / 标题 / 提示 / 剧集网格 */
    private lateinit var episodePanel: View
    private lateinit var episodePanelMask: View
    private lateinit var episodePanelCard: View
    private lateinit var episodePanelClose: TextView
    private lateinit var episodePanelTitle: TextView
    private lateinit var episodePanelHint: TextView
    private lateinit var episodeGrid: GridView
    /** 用户选定的播放倍速（换集 / 重建 ExoPlayer 后仍保持） */
    private var playbackSpeed = 1f
    /** 可选倍速档位 */
    private val speedOptions = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
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
    /** 播放层顶部居中显示的标题（完整串「片名 · 线路 · 集名」） */
    private var curVideoTitle = ""
    /** 标题里去掉集名的那截前缀（「片名 · 线路」）：切集时拼新集名用，空串表示原标题无「 · 」 */
    private var curTitlePrefix = ""
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
    /** 当前播放直链要带的完整请求头（playerContent 的 header 全量透传，旧版只带 Referer） */
    private var curHeaders: Map<String, String> = emptyMap()
    /** 切集防抖：ExoPlayer 切集异步（prepare→STATE_READY 要几秒），连点会连跳好几集 */
    private var lastSwitchAtMs: Long = 0L
    /** 403 时是否已经试过「去掉 Referer 重播」，防止无限重试 */
    private var noRefererRetryDone = false
    /** 正在等 JS 嗅探直链的集下标（-1 表示没有）；回传时用它丢弃过期结果 */
    private var pendingResolveIdx: Int = -1

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

    // ===================== VIP 官网链接解析（阶段 3） =====================
    /** 等 video.html 加载完再触发的官网播放页地址 */
    private var pendingVipUrl: String? = null
    /** 当前正在解析的官网地址（失败回退要用） */
    private var vipUrlCurrent: String? = null
    private var vipPlatformName = ""
    /** 快捷位名称：直连搜片名时的第一候选 */
    private var vipLabel = ""
    /** 一次解析只触发一次，避免 onPageFinished / onNewIntent 重复起播 */
    private var vipFired = false
    /** 独立解析态：页面用 ?vip=1 加载，跳过影视仓订阅/列表，不显示影视仓 UI */
    private var vipOnlyMode = false
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
        private const val HIST_KEY = "search_history"
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
        /** hop-by-hop 头交给 OkHttp/ExoPlayer 自己管；手动塞 Range 会打断分片请求 */
        private val HOP_BY_HOP = setOf(
            "host", "connection", "content-length", "transfer-encoding",
            "accept-encoding", "range", "upgrade", "proxy-connection", "keep-alive", "te"
        )
        /** WebView 嗅探只认媒体后缀，避免把页面 js/css 当成直链 */
        private val MEDIA_URL_RE = Regex("""\.(?:m3u8|mp4|flv|mpd)(?:[?#&]|$)""", RegexOption.IGNORE_CASE)
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
        // ⚠️ media3 PlayerControlViewLayoutManager 在「minimal 模式」（详情页内嵌小播放器）
        //    会把 exo_progress 的 bottomMargin 强制改成 0 —— 进度条贴到画面最底部，
        //    正好压住片头/片尾/投屏/倍数配置行，用户看到的就成了「进度条在设置按钮下边」。
        //    这里在每次绘制前把它纠正回 dimen 的 42dp，让内嵌态和全屏态共用同一套
        //    从底往上的层次：配置行 4~38dp → 进度条 42~90dp → 时间行 92~120dp。
        //    全屏态 layout manager 本来就设成 42dp，检测到相等就不会动它。
        //    同时 exo_time 已提到 merge 顶层，minimal 模式隐藏 exo_bottom_bar 时时长仍在。
        runCatching {
            val progressBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            if (progressBar != null) {
                val targetMargin = resources.getDimensionPixelSize(
                    androidx.media3.ui.R.dimen.exo_styled_progress_margin_bottom
                )
                playerView.viewTreeObserver.addOnPreDrawListener {
                    val lp = progressBar.layoutParams
                    if (lp is FrameLayout.LayoutParams && lp.bottomMargin != targetMargin) {
                        lp.bottomMargin = targetMargin
                        progressBar.layoutParams = lp
                    }
                    true
                }
            }
        }
        fullBtn = findViewById(R.id.videoFull)
        videoPip = findViewById(R.id.videoPip)
        videoTitle = findViewById(R.id.videoTitle)
        videoClock = findViewById(R.id.videoClock)
        videoPrev = findViewById(R.id.videoPrev)
        videoNext = findViewById(R.id.videoNext)
        sideBtns = findViewById(R.id.videoSideBtns)
        videoLock = findViewById(R.id.videoLock)
        // 中间控制条覆写后新增的两个按钮（见 exo_player_control_view.xml 注释）：
        // 用自定义 id，media3 不会给它们绑默认行为，切集完全走 epsList。
        centerPrev = playerView.findViewById(R.id.videoCenterPrev)
        centerNext = playerView.findViewById(R.id.videoCenterNext)
        videoLock.setOnClickListener { toggleLock() }
        centerPrev.setOnClickListener { playPrev() }
        centerNext.setOnClickListener { playNext() }
        videoBuffering = findViewById(R.id.videoBuffering)
        videoSeekHint = findViewById(R.id.videoSeekHint)
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
        // 倍数 chip：常驻可见入口（原先只有长按临时 3× 的隐藏手势，用户找不到倍数）
        // 点它就地在按钮旁弹出选档菜单，不弹屏幕居中的对话框
        speedBtn = playerView.findViewById(R.id.speedBtn)
        speedBtn.setOnClickListener { showSpeedMenu() }
        updateSpeedChip()
        // 选集 chip（紧挨倍速）+ 右侧抽屉：点任意一集直接复用 playIndex() 切集
        episodesBtn = playerView.findViewById(R.id.episodesBtn)
        episodesBtn.setOnClickListener { showEpisodePanel() }
        episodePanel = findViewById(R.id.videoEpisodePanel)
        episodePanelMask = findViewById(R.id.episodePanelMask)
        episodePanelCard = findViewById(R.id.episodePanelCard)
        episodePanelClose = findViewById(R.id.episodePanelClose)
        episodePanelTitle = findViewById(R.id.episodePanelTitle)
        episodePanelHint = findViewById(R.id.episodePanelHint)
        episodeGrid = findViewById(R.id.episodeGrid)
        episodePanel.setOnClickListener { hideEpisodePanel() }
        episodePanelMask.setOnClickListener { hideEpisodePanel() }
        episodePanelCard.setOnClickListener { /* 卡片空白吞掉点击，免得误关 */ }
        episodePanelClose.setOnClickListener { hideEpisodePanel() }
        episodeGrid.setOnItemClickListener { _, _, position, _ ->
            hideEpisodePanel()
            Log.d("VidPlay", "选集面板点选第 ${position + 1} 集 -> playIndex($position)")
            playIndex(position)
        }
        // 控制条显隐同步小窗按钮：只有播放中且控制条可见时才显示小窗按钮
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            // 锁定态不允许控制条再冒出来（自动 show / 播放状态变化都会触发这里），
            // 否则屏幕上又出现一排可点的按钮，锁就形同虚设。
            if (locked && visibility == View.VISIBLE) {
                playerView.hideController()
            } else {
                controlsVisible = visibility == View.VISIBLE
                Log.d("VidPlay", "控制条可见=$controlsVisible 播放中=${player?.isPlaying}")
                updatePipButton()
            }
        })

        // 耳机断开暂停本地视频（与 App 全局广播互补，覆盖本页前台播放场景）
        runCatching {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }

        // 播放层手势统一在 setupPlayGestures 里注册：
        //  左侧上下滑 = 调亮度 / 左右拖动 = 快进后退 / 轻点 = 切换控制条显隐。
        // 三者必须共用一个 OnTouchListener —— PlayerView 只保留一个，后注册的会顶掉前一个。
        brightnessInd = findViewById(R.id.videoBrightness)
        volumeInd = findViewById(R.id.videoVolume)
        speedInd = findViewById(R.id.videoSpeed)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupPlayGestures()
        fullBtn.setOnClickListener {
            // 内嵌态 ↔ 全屏：复用同一 ExoPlayer 实例，不重建、不中断播放进度
            if (inlineExpanded) shrinkToInline() else enterFullscreen()
        }

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
        videoPrev.setOnClickListener { playPrev() }
        videoNext.setOnClickListener { playNext() }
        // 返回键逐层回退：小窗→展开回全屏；播放中→关播放器(回到该视频详情页)；详情/搜索等浮层开着→交给页面关掉(回到搜索位置)；都没有才退出
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 选集抽屉开着时，返回键先只收抽屉，不退出播放
                if (::episodePanel.isInitialized && episodePanel.visibility == View.VISIBLE) {
                    hideEpisodePanel(); return
                }
                // 锁定态按返回先解锁，不直接退出播放（锁的意义就是防误触）
                if (locked) { toggleLock(); return }
                if (miniMode) { exitMini(); return }
                // 内嵌态全屏 → 收回详情页顶部内嵌继续播（不中断）
                if (inlineMode && inlineExpanded) { shrinkToInline(); return }
                // 内嵌态 → 关掉内嵌播放器（详情页仍留在背后，可继续浏览/再点选集）
                if (inlineMode) { closeVideo(); return }
                if (overlay.visibility == View.VISIBLE) { closeVideo(); return }
                webView.evaluateJavascript("window.__videoBack?window.__videoBack():false") { r ->
                    if (r != "true") finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)
        // VIP 官网链接：从自定义链接 / 链接页分流进来，等页面就绪后交给 JS 走解析链
        intent.getStringExtra(VipLinkDispatcher.EXTRA_OPEN_URL)
            ?.takeIf { it.isNotBlank() && VipParseStore.isEnabled(this) && VipLinkRouter.isVipPage(it) }
            ?.let {
                pendingVipUrl = it
                vipPlatformName = intent?.getStringExtra(VipLinkDispatcher.EXTRA_PLATFORM)
                    ?: VipLinkRouter.platformOf(it) ?: "官网"
                vipLabel = intent?.getStringExtra(VipLinkDispatcher.EXTRA_LABEL) ?: ""
                Log.i("VipParse", "onCreate open_url=$it platform=$vipPlatformName label=$vipLabel")
                Toast.makeText(this, "正在解析 $vipPlatformName 链接…", Toast.LENGTH_SHORT).show()
            }
        // 官网链接解析走独立态：页面不加载影视仓订阅与首页列表
        vipOnlyMode = intent?.getBooleanExtra(VipLinkDispatcher.EXTRA_VIP_ONLY, false) == true
        if (vipOnlyMode) Log.i("VipParse", "vip_only 模式：跳过影视仓订阅/列表加载")
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
        // 从链接页再次点同一条官网链接：复用本实例重新解析
        val vip = intent?.getStringExtra(VipLinkDispatcher.EXTRA_OPEN_URL)
            ?.takeIf { it.isNotBlank() && VipParseStore.isEnabled(this) && VipLinkRouter.isVipPage(it) }
        if (vip != null) {
            vipPlatformName = intent?.getStringExtra(VipLinkDispatcher.EXTRA_PLATFORM)
                ?: VipLinkRouter.platformOf(vip) ?: "官网"
            vipLabel = intent?.getStringExtra(VipLinkDispatcher.EXTRA_LABEL) ?: ""
            vipFired = false
            vipUrlCurrent = vip
            if (intent?.getBooleanExtra(VipLinkDispatcher.EXTRA_VIP_ONLY, false) == true) {
                vipOnlyMode = true
                if (pageReady) safeEval("if(window.enterVipOnly)window.enterVipOnly()")
            }
            Log.i("VipParse", "onNewIntent open_url=$vip platform=$vipPlatformName vipOnly=$vipOnlyMode")
            if (pageReady) fireVipParse(vip) else pendingVipUrl = vip
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
                // VIP 官网链接优先于历史续播：同一次启动只走一条
                pendingVipUrl?.let {
                    pendingVipUrl = null
                    pendingResumeJson = null
                    fireVipParse(it)
                } ?: pendingResumeJson?.let { fireResume(it); pendingResumeJson = null }
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
        webView.loadUrl(
            if (vipOnlyMode) "file:///android_asset/video.html?vip=1"
            else "file:///android_asset/video.html"
        )
    }

    // ===================== 原生能力桥 =====================
    private inner class VideoBridge {
        /** 爬虫源切集失败由 JS 回调（原来 JS 里两个静默 return，用户点了没任何反应） */
        @JavascriptInterface
        fun onSpiderSwitchFailed(reason: String) {
            runOnUiThread { toast("切集失败：$reason，请在选集页重新点一集") }
        }

        /**
         * 切集时「播放页地址」经 JS 嗅探出真实直链后的回传桥。
         * 只换播放地址 + 标题 + 历史，不重走 startVideo（否则会重建历史、重置内嵌布局）。
         * 解析结果回填 epsList，之后再切回这一集不必重复嗅探。
         */
        @JavascriptInterface
        fun onEpResolved(url: String, referer: String, idx: Int) {
            runOnUiThread {
                if (idx != pendingResolveIdx) {
                    Log.d("VidPlay", "解析回传过期：idx=$idx pending=$pendingResolveIdx，丢弃")
                    return@runOnUiThread
                }
                pendingResolveIdx = -1
                val ep = epsList.getOrNull(idx)
                if (url.isBlank() || ep == null) {
                    toast("「${ep?.name ?: "这一集"}」解析失败，请在选集页换条线路/源")
                    return@runOnUiThread
                }
                Log.d("VidPlay", "切集解析完成 -> ${ep.name} ${url.take(100)}")
                epsList = epsList.toMutableList().also { it[idx] = it[idx].copy(url = url) }
                curIdx = idx
                finishSwitch(idx, url, referer)
            }
        }

        /** 跨域 GET：结果用 base64 回传，避免转义问题。cbId 对应 JS 的 window.__videoCb */
        @JavascriptInterface
        fun httpGet(url: String, cbId: Int) {
            if (url.isBlank()) {
                runOnUiThread {
                    webView.evaluateJavascript("window.__videoCb($cbId,false,'',0)", null)
                }
                return
            }
            // 内置源包：asset://tvbox/<path> 直接读 APK assets，订阅服务器挂了也能
            // 拿到完整 sites/spider/parses 配置（见 assets/tvbox/manifest.json）。
            if (url.startsWith("asset://")) {
                val path = url.removePrefix("asset://")
                val body = runCatching {
                    this@VideoActivity.assets.open(path)
                        .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }.getOrDefault("")
                val ok = body.isNotEmpty()
                val b64 = Base64.encodeToString(
                    body.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
                )
                Log.d("VidHttp", "ASSET $path -> ${if (ok) 200 else 404} len=${body.length}")
                runOnUiThread {
                    safeEval("window.__videoCb($cbId,$ok,'$b64',${if (ok) 200 else 404})")
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
            // 详情页点选集起播：默认内嵌在详情页顶部（竖屏、可继续浏览选集）
            runOnUiThread { startVideo(url, title, referer, epsJson, idx, spiderJson, metaJson, inline = true) }
        }

        /** 爬虫源剧集解析完成后的起播桥：playSpiderEp 拿到直链后回传（同样内嵌） */
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
            runOnUiThread { startVideo(url, title, referer, epsJson, idx, spiderJson, metaJson, inline = true) }
        }

        /** 爬虫源解析完成后的起播桥（新）：headerJson 全量透传，不再只给 Referer。
         *  Jpys 这类 parse:0 直链源要求 UA + Origin + Referer 三件套，缺一个就 403。 */
        @JavascriptInterface
        fun playResolvedEx(
            url: String, title: String, headerJson: String,
            idx: Int, epsJson: String, spiderJson: String, metaJson: String
        ) {
            val h = parseHeaders(headerJson)
            val ref = h.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
            Log.d("VidPlay", "playResolvedEx headers=${h.keys} url=${url.take(90)}")
            runOnUiThread {
                startVideo(url, title, ref, epsJson, idx, spiderJson, metaJson, inline = true, headers = h)
            }
        }

        /** 带自定义请求头的 GET：在线解析接口常校验 UA/Referer，订阅用的 okhttp UA 会被拒 */
        @JavascriptInterface
        fun httpGetEx(url: String, headersJson: String, cbId: Int) {
            val h = parseHeaders(headersJson)
            val rb = Request.Builder().url(url).header("Accept", "*/*")
            if (h.keys.none { it.equals("User-Agent", true) }) rb.header("User-Agent", SNIFF_UA)
            h.forEach { (k, v) -> if (!HOP_BY_HOP.contains(k.lowercase())) rb.header(k, v) }
            Log.d("VidHttp", "GETEX $url headers=${h.keys}")
            client.newCall(rb.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { safeEval("window.__videoCb($cbId,false,'',0)") }
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = if (response.isSuccessful)
                        runCatching { response.body?.string() ?: "" }.getOrDefault("") else ""
                    val b64 = Base64.encodeToString(body.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    runOnUiThread { safeEval("window.__videoCb($cbId,${response.isSuccessful},'$b64',${response.code})") }
                }
            })
        }

        /** 解析通道 type:0：离屏 WebView 真正渲染播放页，拦截媒体请求拿直链 */
        @JavascriptInterface
        fun webSniff(url: String, headersJson: String, scriptsJson: String, cbId: Int) {
            val h = parseHeaders(headersJson)
            val scripts = runCatching {
                val a = JSONArray(if (scriptsJson.isBlank()) "[]" else scriptsJson)
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrDefault(emptyList())
            runOnUiThread { startWebSniff(url, h, scripts, cbId) }
        }

        /**
         * VIP 官网链接解析成功：全屏起播（独立态只显示解析面板，不适合内嵌播放器）。
         * idx 是官网链接里识别出的当前集下标，直连搜索会把整份剧集表一并传回来，
         * 这样上一集/下一集/播完连播都能直接复用影视仓已有的 epsList 逻辑。
         */
        @JavascriptInterface
        fun playVipResult(url: String, title: String, headerJson: String, epsJson: String, idx: Int) {
            val h = parseHeaders(headerJson)
            val ref = h.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
            Log.i("VipParse", "playVipResult headers=${h.keys} idx=$idx url=${url.take(120)}")
            runOnUiThread {
                startVideo(url, title, ref, epsJson, idx, "", "", inline = false, headers = h)
            }
        }

        /** 所有解析源都失败：按设置决定自动回退原网页还是留在当前页 */
        @JavascriptInterface
        fun onVipFailed(reason: String) {
            runOnUiThread { handleVipFailure(reason) }
        }

        /** 解析链关键节点日志（tag VipParse），真机验收时可直接 grep */
        @JavascriptInterface
        fun vipLog(msg: String) {
            Log.i("VipParse", msg)
        }

        /** 经典全屏起播（历史续播用）：铺满横屏 + 沉浸式，不进内嵌态 */
        @JavascriptInterface
        fun playVideoFull(
            url: String,
            title: String,
            referer: String,
            epsJson: String,
            idx: Int,
            spiderJson: String,
            metaJson: String
        ) {
            runOnUiThread { startVideo(url, title, referer, epsJson, idx, spiderJson, metaJson, inline = false) }
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
            // 第二参兼容两种形态：旧调用传 Referer 字符串，新调用传完整 header JSON
            val h = parseHeaders(referer)
            val ref = h.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
            Log.d("VidPlay", "sniff: $url headers=${h.keys} ref=$ref")
            val result = try {
                val rb = Request.Builder().url(url)
                if (h.keys.none { it.equals("User-Agent", true) }) rb.header("User-Agent", SNIFF_UA)
                h.forEach { (k, v) -> if (!HOP_BY_HOP.contains(k.lowercase())) rb.header(k, v) }
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

        /** 选集下载入口：JS 侧长按某集触发，把「解析后的最终直链 + 完整防盗链头」交过来。
         *  App 内用 okhttp 带 header 拉取（jx/防盗链源不会被 403）：mp4/flv 等单文件直链直接下；
         *  m3u8 拉索引、并发下所有 ts、按序合并成单个 .ts 文件。 */
        @JavascriptInterface
        fun downloadResolved(url: String, headerJson: String, title: String) {
            val h = parseHeaders(headerJson)
            // 合并播放链路所用的全量头(curHeaders)：播放时带的是 curHeaders 全量
            // (UA/Origin/Referer/Cookie…)，很多 CDN 校验这些，下载只带少量头会被 403/404。
            // 以 curHeaders 为基础、JS 传入的头作为补充覆盖。
            val merged = LinkedHashMap<String, String>()
            curHeaders.forEach { (k, v) -> if (v.isNotBlank()) merged[k] = v }
            h.forEach { (k, v) -> if (v.isNotBlank()) merged[k] = v }
            Log.d("VidDl", "downloadResolved url=${url.take(90)} headers=${merged.keys}")
            startDownload(url, merged, title)
        }

        /** 列出已下载的视频（Movies/从前慢）：API29+ 走 MediaStore，API28- 列应用私有 Movies 目录。
         *  返回 JSON 数组 [{name,size,uri,mime}]，uri 可直接喂给 playVideoDownload 在 App 内播放。 */
        @JavascriptInterface
        fun listVideoDownloads(): String {
            val dirName = "从前慢"
            return try {
                val list = mutableListOf<org.json.JSONObject>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val proj = arrayOf(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.RELATIVE_PATH
                    )
                    // MediaStore 的 RELATIVE_PATH 实际值带尾斜杠（'Movies/从前慢/'），
                    // 精确匹配 'Movies/从前慢' 会 0 命中，故用 LIKE 通配。
                    val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel,
                        arrayOf(android.os.Environment.DIRECTORY_MOVIES + "/$dirName%"),
                        "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
                    )?.use { c ->
                        val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        while (c.moveToNext()) {
                            val id = c.getLong(iId)
                            val u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            list.add(org.json.JSONObject()
                                .put("name", c.getString(iName))
                                .put("size", c.getLong(iSize))
                                .put("uri", u.toString())
                                .put("mime", dlMimeOf(c.getString(iName))))
                        }
                    }
                } else {
                    val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), dirName)
                    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
                        list.add(org.json.JSONObject()
                            .put("name", it.name)
                            .put("size", it.length())
                            .put("uri", android.net.Uri.fromFile(it).toString())
                            .put("mime", dlMimeOf(it.name)))
                    }
                }
                org.json.JSONArray(list).toString()
            } catch (e: Exception) {
                Log.w("VidDl", "listVideoDownloads fail: ${e.message}")
                "[]"
            }
        }

        /** 在 App 内用 ExoPlayer 播放已下载的视频（uri / mime 来自 listVideoDownloads） */
        @JavascriptInterface
        fun playVideoDownload(uri: String, mime: String) {
            runOnUiThread {
                ensurePlayer()
                val item = androidx.media3.common.MediaItem.Builder().setUri(uri).setMimeType(mime).build()
                val ds = androidx.media3.datasource.DefaultDataSource.Factory(
                    this@VideoActivity,
                    androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                )
                val src = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds).createMediaSource(item)
                player?.apply {
                    setPlaybackParameters(androidx.media3.common.PlaybackParameters(playbackSpeed, playbackParameters.pitch))
                    setMediaSource(src); prepare(); playWhenReady = true
                }
            }
        }

        /** 删除已下载的视频（file 路径或 MediaStore content URI 都支持） */
        @JavascriptInterface
        fun deleteVideoDownload(uri: String) {
            runOnUiThread {
                try {
                    val u = android.net.Uri.parse(uri)
                    if (u.scheme == "content") contentResolver.delete(u, null, null)
                    else { val f = java.io.File(u.path ?: ""); if (f.exists()) f.delete() }
                    this@VideoActivity.toast("已删除")
                } catch (e: Exception) { this@VideoActivity.toast("删除失败") }
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

        /** 读取影视搜索历史（JSON 数组字符串，最新在前，最多 10 条） */
        @JavascriptInterface
        fun getSearchHistory(): String = prefs.getString(HIST_KEY, "[]") ?: "[]"

        /** 保存影视搜索历史（JSON 数组字符串，JS 侧已去重并截断到 10 条） */
        @JavascriptInterface
        fun saveSearchHistory(json: String) {
            prefs.edit().putString(HIST_KEY, json).apply()
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

    // ===================== VIP 官网链接解析（阶段 3） =====================

    /** 页面就绪后把官网 URL + 用户配置的解析源交给 JS 的 __resolveVipAt。
     *  全部用 base64 传，避免 URL / header JSON 里的引号和反斜杠破坏 JS 字符串字面量。 */
    private fun fireVipParse(url: String) {
        if (vipFired) return
        vipFired = true
        vipUrlCurrent = url
        val srcs = VipParseStore.orderedSources(this)
        val arr = JSONArray()
        srcs.take(8).forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("prefix", s.prefix)
                    .put("encoded", s.encoded)
                    .put("headers", s.headerJson)
            )
        }
        fun b64(s: String) = Base64.encodeToString(
            s.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
        )
        val bUrl = b64(url)
        val bSrc = b64(arr.toString())
        val bPlat = b64(vipPlatformName)
        val sourcesCall = "window.__resolveVipAt('$bUrl','$bSrc','$bPlat')"
        val missing = "else if(window.Android&&Android.onVipFailed)Android.onVipFailed('页面未提供解析入口');"

        if (VipParseStore.directConfigured(this)) {
            // 二期：先按片名直连取真实播放列表；JS 侧失败会自己回落到一期解析接口
            val cfg = JSONObject()
                .put("suggest", VipParseStore.directSuggest(this))
                .put("detail", VipParseStore.directDetail(this))
            Log.i(
                "VipParse",
                "fire DIRECT url=$url label=$vipLabel platform=$vipPlatformName " +
                    "sources=${arr.length()} (total ${srcs.size})"
            )
            safeEval(
                "if(window.__resolveVipDirect)window.__resolveVipDirect(" +
                    "'$bUrl','${b64(cfg.toString())}','${b64(vipLabel)}','$bPlat','$bSrc');" +
                    "else if(window.__resolveVipAt)$sourcesCall;$missing"
            )
        } else {
            Log.i("VipParse", "fire url=$url platform=$vipPlatformName sources=${arr.length()} (total ${srcs.size})")
            safeEval("if(window.__resolveVipAt)$sourcesCall;$missing")
        }
    }

    /** 解析全失败：按设置里的「失败回退」决定自动打开原网页，还是让用户自己选 */
    private fun handleVipFailure(reason: String) {
        val u = vipUrlCurrent ?: return
        val fb = VipParseStore.fallbackOnFail(this)
        Log.i("VipParse", "failed reason=$reason fallback=$fb")
        vipFired = false
        if (fb) {
            Toast.makeText(this, "解析失败：$reason，已打开原网页", Toast.LENGTH_LONG).show()
            VipLinkDispatcher.fallback(this, u)
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("解析失败")
                .setMessage("$reason\n\n官网：$u")
                .setPositiveButton("打开原网页") { _, _ ->
                    VipLinkDispatcher.fallback(this, u)
                    finish()
                }
                .setNegativeButton(if (vipOnlyMode) "留在此页" else "留在影视仓", null)
                .show()
        }
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

    /** 把 JS 传来的 header 参数解析成 map：支持 JSON 对象，也兼容旧的「Referer 字符串」 */
    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        if (!raw.trimStart().startsWith("{")) return mapOf("Referer" to raw)
        return runCatching {
            val o = JSONObject(raw)
            val m = LinkedHashMap<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k, "") }
            m.filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    /** 离屏 WebView 嗅探：影视仓 parses type:0 的等价实现。
     *  真正渲染播放页（执行 JS、跟 iframe、跑 rules 点击脚本），
     *  在 shouldInterceptRequest 里拦截 m3u8/mp4 请求拿直链，
     *  比旧版「一次静态 GET + 4 条正则」多覆盖全部 iframe/JS 拼接型播放页。 */
    private val sniffViews = mutableSetOf<WebView>()

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebSniff(url: String, headers: Map<String, String>, scripts: List<String>, cbId: Int) {
        val wv = WebView(this)
        sniffViews.add(wv)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                ?: SNIFF_UA
        }
        var done = false
        fun emit(found: String?) {
            if (done) return
            done = true
            sniffViews.remove(wv)
            runCatching { wv.stopLoading(); wv.destroy() }
            val result = if (found != null)
                """{"ok":true,"url":${JSONObject.quote(found)}}"""
            else """{"ok":false,"msg":"WebView 未嗅探到直链"}"""
            val b64 = Base64.encodeToString(result.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            safeEval("window.__sniffCb($cbId,true,'$b64')")
            Log.d("VidPlay", "webSniff => ${found ?: "未命中"} url=${url.take(100)}")
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (MEDIA_URL_RE.containsMatchIn(u)) {
                    Log.d("VidPlay", "webSniff 拦截: ${u.take(160)}")
                    Handler(Looper.getMainLooper()).post { emit(u) }
                }
                return null
            }
            override fun onPageFinished(view: WebView, page: String) {
                scripts.forEach { runCatching { view.evaluateJavascript(it, null) } }
            }
        }
        val extra = headers.filterKeys { !HOP_BY_HOP.contains(it.lowercase()) }
        runCatching { wv.loadUrl(url, extra) }.onFailure { emit(null) }
        Handler(Looper.getMainLooper()).postDelayed({ emit(null) }, 13_000)
    }

    // ===================== 视频播放 =====================
    private fun ensurePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(this).build().also {
                playerView.player = it
                // 播放器可能被释放后重建（返回/小窗/换片），重新套用用户选定的倍速
                it.setPlaybackParameters(
                    androidx.media3.common.PlaybackParameters(playbackSpeed, it.playbackParameters.pitch)
                )
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
                        val epName = epsList.getOrNull(curIdx)?.name ?: "当前集"
                        Log.w("VidPlay", "onPlayerError: $detail (${error.errorCodeName}) ep=$epName url=${curCastUrl.take(100)}")
                        // 切集后 CDN 变了却沿用旧 Referer 常被判盗链 403：去掉 Referer 自动重试一次
                        if (detail.startsWith("HTTP 403") && curReferer.isNotBlank() && !noRefererRetryDone) {
                            noRefererRetryDone = true
                            toast("「$epName」防盗链拒绝，正在去掉 Referer 重试一次")
                            playMediaUrl(curCastUrl, "", isRetry = true)
                            return
                        }
                        val friendly = when {
                            detail.startsWith("HTTP 403") -> "该线路拒绝访问(403)，可能需登录/防盗链，请换其他线路/源"
                            detail.startsWith("HTTP 404") -> "该线路地址不存在(404)，请换其他线路/源"
                            detail.startsWith("HTTP 502") || detail.startsWith("HTTP 503") -> "该线路源站故障($detail)，请换其他线路/源"
                            detail.contains("Source error", true) ->
                                "该集地址不是可直接播放的视频（多为已失效或仍是网页），请换线路/源或回选集页重选"
                            else -> "播放失败：$detail（可换其他线路/源）"
                        }
                        Toast.makeText(
                            this@VideoActivity,
                            "「$epName」$friendly",
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
        metaJson: String = "",
        inline: Boolean = true,
        headers: Map<String, String>? = null
    ) {
        Log.d("VidPlay", "startVideo: $title | $url | referer=$referer | idx=$idx | inline=$inline")
        hideEpisodePanel()      // 起播新片：收掉可能还开着、内容属于上一部的选集抽屉
        // 只有 playResolvedEx 会带完整 header；历史续播/旧桥没有这份信息，必须清掉，
        // 否则会把上一部片的 UA/Origin 沿用到新片上，反而被判 403。
        curHeaders = headers ?: emptyMap()
        curReferer = if (headers != null) {
            headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: referer
        } else referer
        pendingResolveIdx = -1        // 新片起播：丢掉上一部可能还在飞嗅探的回传
        curCastTitle = title          // 投屏时作为 DIDL-Lite 标题展示在电视端
        // 播放层标题：JS 传的是「片名 · 线路 · 集名」，切集时只换集名那一截
        curVideoTitle = title
        curTitlePrefix = title.substringBeforeLast(" · ", "")
        videoTitle.text = title
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
        // 三态布局统一由 applyPlayerLayout 处理：
        // - inlineMode 由调用方按资源形态指定（内嵌源 true / 经典全屏源 false）；
        // - inlineExpanded（内嵌⇄全屏）不再在此强制复位：它反映「用户当前想要的布局」，
        //   切集（手动点集 playVideo / 爬虫源自动连播 playResolvedEx）时原样保留，
        //   故全屏看瓜子类爬虫源切集不会退回内嵌态；
        //   用户点全屏(enterFullscreen)/收起(shrinkToInline)/关播(closeVideo)/进小窗(clearInlineDock)
        //   时已各自把 inlineExpanded 复位成内嵌，所以「进入新详情首次起播」默认仍是内嵌态。
        inlineMode = inline
        applyPlayerLayout()
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
     * 播放层手势（用户需求：左右拖动快进后退 + 左侧上下滑动调亮度 + 轻点显隐控制条）。
     *
     * 三种手势必须写在同一个 OnTouchListener 里：PlayerView 只保留一个 listener，
     * 后注册的会整个顶掉前一个，分两个函数各注册一次的话只有最后一个生效。
     *
     * 实现要点：
     *  - 方向裁决只做一次：纵向占优 → 亮度（且起点在左半屏），横向占优 → 快进/后退，
     *    右半屏竖向滑动不接管（留给系统/其它交互）。
     *  - 起点落在「进度条那一行 / 片头片尾配置行」内时不接管——那一下是用户在拖进度条，
     *    抢了它就再也没法拖进度条了。控制条隐藏时这些 View 的 bounds 为 0，判断自然失效，
     *    那时整幅画面都能滑。
     *  - 小窗 / 投屏 / 播放层隐藏时不接管，避免影响其它交互。
     *  - 灵敏度：拖动 100px ≈ 跳 15 秒；亮度按屏幕高度比例调；松手才真正 seek
     *    （拖动中只更新提示文字，不频繁 seek）。
     *  - 投屏中 player 是空的，改走 castCtrl.seekTo（本地播放器已停）。
     *  - 轻点（没产生位移）时手动切换控制条：PlayerView 自带的该行为就是它的
     *    OnTouchListener 实现的，被我们覆盖后必须补回。
     */
    private fun setupPlayGestures() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val msPerPx = 150f
        val padDp = 16 * resources.displayMetrics.density
        // 不能抢的区域：自带进度条占位行 + 下方的片头/片尾配置行
        val protectedViews = listOfNotNull(
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress_placeholder),
            playerView.findViewById<View>(R.id.skipCfgRow)
        )
        val loc = IntArray(2)
        fun inProtected(rx: Float, ry: Float): Boolean = protectedViews.any { v ->
            v.getLocationOnScreen(loc)
            rx >= loc[0] - padDp && rx <= loc[0] + v.width + padDp &&
                ry >= loc[1] - padDp && ry <= loc[1] + v.height + padDp
        }

        var downX = 0f
        var downY = 0f
        var startPos = 0L
        var target = 0L
        var seeking = false
        var moved = false
        var takeOver = false

        playerView.setOnTouchListener { v, ev ->
            // 锁定态吞掉所有触摸：轻点不会唤出控制条，左右滑不会快进，
            // 上下滑不会调亮度/音量，长按不会临时 3 倍速。锁按钮在 overlay 上层、
            // 是独立 View，不受本 listener 影响，仍可点它解锁。
            if (locked) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    startPos = currentPosMs()
                    target = startPos
                    seeking = false
                    moved = false
                    brightStartX = ev.x
                    brightStartY = ev.y
                    brightStartVal = getScreenBrightness()
                    brightDecided = false
                    brightActive = false
                    volDecided = false
                    volActive = false
                    // 小窗 / 投屏 / 播放层隐藏时不接管，避免影响其它交互；
                    // 起点落在进度条 / 片头片尾配置行时也不接管（那是用户在拖进度条）
                    val idle = !miniMode && !casting && overlay.visibility == View.VISIBLE
                    val onBar = inProtected(ev.rawX, ev.rawY)
                    takeOver = idle && !onBar
                    // 长按屏幕 = 3 倍速：按下后若一直按住不动（未超过 slop），到点切 3 倍速
                    longPressActive = false
                    // 起点档位 = 用户在倍速 chip 上选的档，长按松手后回到它（不再是死值 1.0×）
                    origSpeed = playbackSpeed
                    longPressTimer?.cancel()
                    longPressTimer = object : android.os.CountDownTimer(LP_TRIGGER_MS, LP_TRIGGER_MS) {
                        override fun onTick(millisUntilFinished: Long) {}
                        override fun onFinish() {
                            if (!takeOver || moved || miniMode || casting) return
                            if (overlay.visibility != View.VISIBLE) return
                            if (brightActive || volActive || seeking) return
                            val p = player ?: return          // 仅原生 ExoPlayer 在线时生效
                            if (!p.isPlaying) return
                            longPressActive = true
                            origSpeed = p.playbackParameters.speed
                            p.setPlaybackParameters(
                                androidx.media3.common.PlaybackParameters(3f, p.playbackParameters.pitch)
                            )
                            speedInd.visibility = View.VISIBLE
                            speedInd.text = "3.0×"
                            Log.d("VidPlay", "长按倍速进入 3.0×（松手回到 ${fmtSpeed(origSpeed)}×）")
                        }
                    }.apply { start() }
                    takeOver // 消费 DOWN 才能稳定收到后续 MOVE/UP
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!takeOver) return@setOnTouchListener false
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        // 一旦产生位移，长按判定作废；若已进入 3 倍速则退出恢复
                        longPressTimer?.cancel()
                        if (longPressActive) restoreLongPressSpeed()
                    }
                    // 方向裁决（只判一次）：左半屏纵向=亮度，右半屏纵向=音量，横向占优=快进，其余不动
                    if (!brightDecided && !seeking && !volDecided) {
                        when {
                            abs(dy) > slop && abs(dy) > abs(dx) -> {
                                if (downX < v.width / 2f) {
                                    brightDecided = true
                                    brightActive = true        // 左半屏：调亮度
                                    brightStartVal = getScreenBrightness()
                                    brightStartY = ev.y
                                } else {
                                    volDecided = true
                                    volActive = true           // 右半屏：调音量
                                    volStartVal = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                    volStartY = ev.y
                                }
                            }
                            abs(dx) > slop && abs(dx) > abs(dy) * 1.5f -> seeking = true
                            abs(dx) > slop || abs(dy) > slop -> brightDecided = true
                        }
                    }
                    when {
                        brightActive -> {   // 上滑变亮、下滑变暗
                            val delta = (brightStartY - ev.y) / v.height
                            val nv = (brightStartVal + delta).coerceIn(BRIGHT_MIN, BRIGHT_MAX)
                            setScreenBrightness(nv)
                            showBrightnessInd(nv)
                        }
                        volActive -> {      // 右半屏上滑加音量、下滑减音量
                            val maxv = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (volStartY - ev.y) / v.height
                            val nv = ((volStartVal + delta * maxv).coerceIn(0f, maxv.toFloat()) + 0.5f)
                                .toInt().coerceIn(0, maxv)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                            val pct = if (maxv > 0) (nv * 100 / maxv) else 0
                            volumeInd.visibility = View.VISIBLE
                            volumeInd.text = if (nv <= 0) "🔇 静音" else "🔊 $pct%"
                            mainHandler.removeCallbacks(volHide)
                            mainHandler.postDelayed(volHide, VOL_HIDE_DELAY)
                        }
                        seeking -> {
                            val dur = runCatching { player?.duration }.getOrNull() ?: 0L
                            target = (startPos + (dx * msPerPx).toLong()).coerceAtLeast(0L)
                            if (dur > 0) target = target.coerceAtMost(dur)
                            val delta = target - startPos
                            videoSeekHint.text = buildString {
                                append(fmtTime(target))
                                append("   ")
                                append(if (delta >= 0) "快进 ${delta / 1000} 秒" else "后退 ${-delta / 1000} 秒")
                            }
                            videoSeekHint.visibility = View.VISIBLE
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 长按计时器无论如何都要取消（短按/抬手都不再触发）
                    longPressTimer?.cancel()
                    longPressTimer = null
                    if (!takeOver) return@setOnTouchListener false
                    val wasBright = brightActive
                    val wasSeek = seeking
                    val wasVol = volActive
                    val wasLong = longPressActive
                    brightActive = false
                    brightDecided = false
                    seeking = false
                    volActive = false
                    volDecided = false
                    longPressActive = false
                    when {
                        wasLong -> {
                            // 长按 3 倍速：松手恢复原始倍速（日志必须在 restore 前打，
                            // 上面已把 longPressActive 置回 false，restore 内部判不到它）
                            Log.d("VidPlay", "长按倍速结束，恢复 -> ${fmtSpeed(origSpeed)}×")
                            restoreLongPressSpeed()
                        }
                        wasBright -> mainHandler.postDelayed(brightHide, BRIGHT_HIDE_DELAY)
                        wasVol -> mainHandler.postDelayed(volHide, VOL_HIDE_DELAY)
                        wasSeek -> {
                            videoSeekHint.visibility = View.GONE
                            if (target != startPos) {
                                if (casting) castCtrl?.seekTo(target) { _, _ -> }
                                else runCatching { player?.seekTo(target) }
                            }
                        }
                        // 没移动过 = 轻点：PlayerView 自带的「点按切换控制条」被本 listener 顶掉了，这里补回
                        !moved -> toggleController()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ===================== 屏幕亮度（左侧上下滑动调节） =====================
    private fun getScreenBrightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0f) 0.5f else b   // 未设置过则是系统默认值(-1)，从 0.5 起调
    }

    private fun setScreenBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v.coerceIn(BRIGHT_MIN, BRIGHT_MAX)
        window.attributes = lp
    }

    private fun showBrightnessInd(v: Float) {
        val pct = (v.coerceIn(BRIGHT_MIN, BRIGHT_MAX) * 100).toInt()
        brightnessInd.visibility = View.VISIBLE
        brightnessInd.text = "☀ $pct%"
        mainHandler.removeCallbacks(brightHide)
        mainHandler.postDelayed(brightHide, BRIGHT_HIDE_DELAY)
    }

    /** 退出长按 3 倍速，恢复按下前的原始倍速（松手 / 手势接管时调用） */
    private fun restoreLongPressSpeed() {
        longPressActive = false
        if (::speedInd.isInitialized) speedInd.visibility = View.GONE
        runCatching {
            val p = player ?: return
            p.setPlaybackParameters(
                androidx.media3.common.PlaybackParameters(origSpeed, p.playbackParameters.pitch)
            )
        }
    }

    /** 倍速数字格式化：1f→"1.0"、0.5f→"0.5"、1.25f→"1.25"，整数补一位、其余去尾零 */
    private fun fmtSpeed(s: Float): String {
        val raw = String.format(Locale.getDefault(), "%.2f", s).trimEnd('0').trimEnd('.')
        return if ('.' in raw) raw else "$raw.0"
    }

    /** 同步倍数 chip 文案，例如「倍数 2.0×」 */
    private fun updateSpeedChip() {
        if (::speedBtn.isInitialized) speedBtn.text = "倍数 ${fmtSpeed(playbackSpeed)}×"
    }

    /** 应用倍速：改 ExoPlayer 的同时记住档位，换集 / 重建 player 后重新套用 */
    private fun applyPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        updateSpeedChip()
        runCatching {
            player?.let { p ->
                p.setPlaybackParameters(
                    androidx.media3.common.PlaybackParameters(speed, p.playbackParameters.pitch)
                )
            }
        }
        Log.d("VidPlay", "倍速 -> ${fmtSpeed(speed)}×")
    }

    /** 点倍数 chip：菜单就贴在按钮旁弹出（不遮挡整幅画面），当前档打勾。
     *  长按屏幕仍可临时 3×，松手回到这里设的档 */
    private fun showSpeedMenu() {
        if (casting) { toast("投屏中不能调整倍数"); return }
        val popup = android.widget.PopupMenu(this, speedBtn, Gravity.END)
        speedOptions.forEachIndexed { i, s ->
            val label = "${fmtSpeed(s)}×"
            val mark = if (abs(s - playbackSpeed) < 0.001f) "✓ " else "   "
            popup.menu.add(0, i, i, mark + label)
        }
        popup.setOnMenuItemClickListener { item ->
            applyPlaybackSpeed(speedOptions[item.itemId])
            true
        }
        popup.show()
    }

    /**
     * 点「选集」chip：右侧抽屉列出当前线路全部剧集，点一格直接切（复用 playIndex）。
     * 数据就是 startVideo 时 JS 带过来的 epsJson（epsList），与「上一集/下一集」同源，
     * 因此直链 / 播放页嗅探 / 爬虫源解析三条切集路径全都照旧可用，不另写一套。
     */
    private fun showEpisodePanel() {
        if (locked) return                       // 锁定态控制条都不该露出来
        if (miniMode || isInPictureInPictureMode) {
            toast("小窗播放时请展开后再选集")
            return
        }
        if (casting) { toast("投屏中请在遥控层切集"); return }
        if (epsList.isEmpty()) {
            toast("这一路没有剧集列表，请在选集页重新点一集")
            return
        }
        val d = resources.displayMetrics.density
        val land = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // 横屏抽屉放宽到 420dp / 5 列，竖屏 320dp / 4 列；左侧遮罩是 weight=1，
        // 改卡片宽度后会自动吃掉剩下的宽度。
        (episodePanelCard.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.width = ((if (land) 420 else 320) * d).toInt()
            episodePanelCard.layoutParams = it
        }
        episodeGrid.numColumns = if (land) 5 else 4
        episodePanelTitle.text = "选集 · 共 ${epsList.size} 集"
        episodePanelHint.text = if (curIdx in epsList.indices) {
            val n = epsList[curIdx].name.ifBlank { "第${curIdx + 1}集" }
            "当前：$n · 点任意一集立即播放"
        } else "点任意一集立即播放"
        val cur = curIdx
        episodeGrid.adapter = object : BaseAdapter() {
            override fun getCount(): Int = epsList.size
            override fun getItem(p: Int): Any = epsList[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val tv = (cv as? TextView) ?: TextView(this@VideoActivity).apply {
                    layoutParams = AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, (40 * d).toInt()
                    )
                    gravity = Gravity.CENTER
                    textSize = 12f
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val pad = (4 * d).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                val name = epsList[p].name.ifBlank { "第${p + 1}集" }
                tv.text = if (p == cur) "✓ $name" else name
                tv.background = GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(if (p == cur) 0xFF7B5CFF.toInt() else 0x33FFFFFF)
                }
                tv.setTextColor(if (p == cur) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
                tv.setTypeface(null, if (p == cur) Typeface.BOLD else Typeface.NORMAL)
                return tv
            }
        }
        // 集数多时自动滚到当前集附近，不用每次从第 1 集翻起
        if (cur in epsList.indices) episodeGrid.setSelection(cur)
        episodePanel.visibility = View.VISIBLE
        episodePanel.bringToFront()
        playerView.showController()
        Log.d("VidPlay", "选集面板打开 共${epsList.size}集 当前第${cur + 1}集 横屏=$land")
    }

    /** 收起选集抽屉：返回键 / 遮罩 / 关闭 / 选完集 / 锁定 / 小窗 / 投屏 / 关播都会走到 */
    private fun hideEpisodePanel() {
        if (::episodePanel.isInitialized && episodePanel.visibility == View.VISIBLE) {
            episodePanel.visibility = View.GONE
            Log.d("VidPlay", "选集面板关闭")
        }
    }

    /** 轻点视频区域 = 切换控制条显隐 */
    private fun toggleController() {
        if (locked) return
        if (!playerView.useController) return
        if (controlsVisible) playerView.hideController() else playerView.showController()
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
        // 第一颗永远是「⏱ 当前 mm:ss」：把当前播放进度一键填进输入框，不用手动敲时间（用户需求）
        val now = currentPosMs()
        val quicks: List<Pair<String, Long>> = buildList {
            if (now > 0) add("⏱ 当前 ${fmtTime(now)}" to now)
            if (isStart) {
                add("30秒" to 30_000L); add("1分" to 60_000L); add("1分半" to 90_000L); add("2分" to 120_000L)
            } else if (dur > 0) {
                add("剩3分" to (dur - 180_000L)); add("剩5分" to (dur - 300_000L)); add("剩10分" to (dur - 600_000L))
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
    private fun playMediaUrl(url: String, referer: String, isRetry: Boolean = false) {
        if (url.isBlank()) {
            toast("这一集没有可用的播放地址，请在选集里换条线路/源")
            return
        }
        curCastUrl = url              // 投屏用：记住当前直链，随时可投/切回本地续投
        if (!isRetry) noRefererRetryDone = false
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
            url.contains(".ts", true) -> itemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        val item = itemBuilder.build()
        val headers = LinkedHashMap<String, String>()
        // playerContent 的 header 全量透传（UA/Origin/Referer/Cookie…），
        // 但 hop-by-hop 头交给播放器自己管；403 去 Referer 重试时要把这份也去掉。
        curHeaders.forEach { (k, v) ->
            if (v.isNotBlank() && !HOP_BY_HOP.contains(k.lowercase())) headers[k] = v
        }
        if (headers.keys.none { it.equals("Referer", true) } && referer.isNotBlank())
            headers["Referer"] = referer
        if (isRetry) headers.keys.firstOrNull { it.equals("Referer", true) }?.let { headers.remove(it) }
        Log.d("VidPlay", "playMediaUrl headers=${headers.keys} url=${url.take(100)}")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
        player?.apply {
            // 换集 / 换线路都保持用户选定的倍速（setMediaSource 不会重置 PlaybackParameters，
            // 但重建 player 会，所以这里统一再套一次）
            setPlaybackParameters(
                androidx.media3.common.PlaybackParameters(playbackSpeed, playbackParameters.pitch)
            )
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ===================== 选集下载 =====================
    /** 下载专用 OkHttp（长读超时，适配大文件/慢 CDN）；复用项目 client 的 HTTP/1.1 与重定向策略 */
    private val dlClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 选集下载：后台线程用 okhttp 带完整 headers 拉取，mp4 直下 / m3u8 合并，写公共目录并通知媒体库 */
    private fun startDownload(rawUrl: String, headers: Map<String, String>, title: String) {
        if (rawUrl.isBlank()) { toast("没有可用的下载地址"); return }
            val safeTitle = sanitizeName(title.ifBlank { "从前慢_${System.currentTimeMillis()}" })
            curDlTitle = safeTitle
            lastDlPct = -1
            val hdrs = LinkedHashMap<String, String>()
            headers.forEach { (k, v) -> if (v.isNotBlank() && k.lowercase() !in HOP_BY_HOP) hdrs[k] = v }
            var out: DlOutput? = null
            Thread {
                try {
                    val isM3u8 = isHlsPlaylist(rawUrl, hdrs)
                    out = prepareDownloadOutput(safeTitle, if (isM3u8) "mp4" else guessExt(rawUrl))
                    showDlProgress(safeTitle, 0, true)
                    if (isM3u8) downloadM3u8(rawUrl, hdrs, out!!.stream) else downloadSingle(rawUrl, hdrs, out!!.stream)
                    out!!.stream.close()
                    runOnUiThread {
                        toast("下载完成：$safeTitle")
                        scanDownloaded(out!!.scanPath)
                        webView.evaluateJavascript(
                            "if(window.onDlDone)onDlDone(${org.json.JSONObject.quote(safeTitle)})", null)
                    }
                } catch (e: Exception) {
                    Log.w("VidDl", "下载失败: ${e.message}")
                    out?.file?.delete()
                    out?.uri?.let { runCatching { contentResolver.delete(it, null, null) } }
                    runOnUiThread { toast("下载失败：${e.message?.take(80)}") }
                } finally { dismissDlProgress() }
            }.start()
    }

    /** 输出目标：API29+ 写 MediaStore（公共 Movies/从前慢，免权限）；否则写应用私有 Movies 目录。
     *  file/uri 用于在下载失败时回滚(删除已创建的空文件/媒体库条目)。 */
    private data class DlOutput(val stream: java.io.OutputStream, val scanPath: String,
                               val file: File? = null, val uri: android.net.Uri? = null)
    private fun prepareDownloadOutput(name: String, ext: String): DlOutput {
        val dirName = "从前慢"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.$ext")
                put(MediaStore.Video.Media.MIME_TYPE, if (ext == "ts") "video/mp2t" else "video/$ext")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/$dirName")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw Exception("无法创建媒体库条目")
            DlOutput(contentResolver.openOutputStream(uri)!!, "", file = null, uri = uri)
        } else {
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            val d = File(dir, dirName); if (!d.exists()) d.mkdirs()
            val f = File(d, "$name.$ext")
            DlOutput(FileOutputStream(f), f.absolutePath, file = f, uri = null)
        }
    }

    /** 单文件下载（mp4/flv/...），带进度通知 */
    private fun downloadSingle(url: String, headers: Map<String, String>, out: java.io.OutputStream) {
        Log.d("VidDl", "downloadSingle GET ${url.take(120)} headers=${headers.keys}")
        dlClient.newCall(headerRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message} @ ${url.take(120)}")
            val total = resp.body?.contentLength() ?: -1L
            val `in` = resp.body?.byteStream() ?: throw Exception("空响应 @ ${url.take(120)}")
            val buf = ByteArray(64 * 1024); var read: Int; var done: Long = 0
            while (`in`.read(buf).also { read = it } > 0) {
                out.write(buf, 0, read); done += read
                if (total > 0) updateDlProgress((done * 100 / total).toInt())
            }
        }
    }

    /** m3u8 下载：拉索引 → 检测加密 → 并发下所有 ts 切片到临时目录 → 用 ffmpeg concat demuxer
     *  把切片无损（-c copy）重封装成标准 MP4（理顺各切片不连续的时间戳），再写入最终 output。
     *  产物是 .mp4，系统相册 / 系统播放器都能正常播放；App 内下载管理页也能播。
     *  注意：必须按索引下载（并发到临时文件），全部完成后交给 ffmpeg 按序合并，不能按
     *  「谁先下完谁先写」——否则文件乱序；时间戳连续性由 ffmpeg 负责。 */
    private fun downloadM3u8(indexUrl: String, headers: Map<String, String>, out: java.io.OutputStream) {
        val idxText = httpGetText(indexUrl, headers)
        val lines = idxText.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-KEY") && it.contains("METHOD=AES-128") })
            throw Exception("该视频为 AES-128 加密流，App 暂不支持，请用 IDM+/ADM 嗅探下载")
        val variantIdx = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        val mediaUrl = if (variantIdx >= 0) {
            val uri = lines.getOrNull(variantIdx + 1)?.takeIf { !it.startsWith("#") } ?: indexUrl
            resolveUrl(indexUrl, uri)
        } else indexUrl
        val mediaText = if (mediaUrl != indexUrl) httpGetText(mediaUrl, headers) else idxText
        val segs = mediaText.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (segs.isEmpty()) throw Exception("m3u8 解析无切片")
        val total = segs.size
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val latch = java.util.concurrent.CountDownLatch(total)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val tmpDir = File(cacheDir, "m3u8_${System.currentTimeMillis()}").also { it.mkdirs() }
        segs.forEachIndexed { i, seg ->
            pool.execute {
                try {
                    val segUrl = resolveUrl(mediaUrl, seg)
                    val bytes = httpGetBytes(segUrl, headers)
                    File(tmpDir, "$i").writeBytes(bytes)   // 按索引落临时切片（无扩展名，交由 ffmpeg 探测 TS / fMP4）
                    updateDlProgress(i * 100 / total)
                } catch (e: Exception) { Log.w("VidDl", "ts#$i 失败: ${e.message}"); failed.set(true) }
                finally { latch.countDown() }
            }
        }
        latch.await(); pool.shutdown()
        if (failed.get()) { tmpDir.deleteRecursively(); throw Exception("部分切片下载失败，已中止") }
        val mp4 = File(cacheDir, "m3u8_${System.currentTimeMillis()}.mp4")
        try {
            val rc = ffmpegConcatRemux(tmpDir, total, mp4)
            if (rc != 0 || !mp4.exists() || mp4.length() == 0L)
                throw Exception("视频封装失败（源可能为加密/特殊编码，请用 IDM/ADM 下载）")
            mp4.inputStream().use { it.copyTo(out) }
            out.flush()
        } finally {
            tmpDir.deleteRecursively(); mp4.delete()
        }
    }

    /** 用 ffmpeg concat demuxer 把按索引命名的切片（无扩展名，TS 或 fMP4 由 ffmpeg 自动探测）
     *  无损（-c copy）重封装为 mp4（理顺各切片不连续的时间戳）。返回 0 成功。 */
    private fun ffmpegConcatRemux(tmpDir: File, total: Int, outMp4: File): Int {
        val list = File(cacheDir, "m3u8_${System.currentTimeMillis()}.txt")
        return try {
            list.bufferedWriter().use { w ->
                for (i in 0 until total) w.write("file '${File(tmpDir, "$i").absolutePath}'\n")
            }
            val cmd = "-y -fflags +genpts -f concat -safe 0 -i ${list.absolutePath} " +
                    "-c copy -movflags +faststart ${outMp4.absolutePath}"
            Log.d("VidDl", "ffmpeg concat remux: $cmd")
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            val rc = session.returnCode
            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(rc)) 0 else rc.value
        } catch (e: Exception) { Log.w("VidDl", "ffmpeg exec fail: ${e.message}"); -1 }
        finally { list.delete() }
    }

    /** 拼接相对/绝对 URL（基于 base 目录）—— 用 RFC 3986 解析，与浏览器/ExoPlayer 一致，
     *  能正确处理「../」回退、相对路径、各段保留，避免手拼路径把 m3u8 的 master→variant
     *  或 ts 切片地址拼错导致下载 404（播放用 ExoPlayer 解析正常，下载自己拼就歪了）。 */
    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("//")) return "https:$rel"
        if (rel.startsWith("http://", true) || rel.startsWith("https://", true)) return rel
        try {
            return java.net.URI(base).resolve(rel).toString()
        } catch (e: Exception) { Log.w("VidDl", "resolveUrl 失败 base=${base.take(80)} rel=$rel"); return rel }
    }

    /** 判断是否为 HLS 播放列表(m3u8)：不能只靠 .m3u8 后缀——大量影视源直链无后缀
     *  (如 /play/xxxx、?token= 形式)，播放靠 ExoPlayer 内容探测能识别，下载若误判成单文件
     *  会直接把 m3u8 文本下成文件。策略：有后缀直接认；否则先 HEAD 看 Content-Type，
     *  再降级拉前 2KB 检测是否以 #EXTM3U 开头（不全下载，避免拉整个 mp4）。 */
    private fun isHlsPlaylist(url: String, headers: Map<String, String>): Boolean {
        if (url.contains(".m3u8", true)) return true
        // 1) HEAD 看 Content-Type（许多 CDN 返回 application/vnd.apple.mpegurl 等）
        try {
            val head = dlClient.newCall(headerRequest(url, headers).newBuilder().head().build()).execute()
            val ct = (head.header("Content-Type") ?: "").lowercase()
            if (ct.contains("mpegurl")) return true
            if (ct.startsWith("video/") || ct.startsWith("audio/")) return false
        } catch (_: Exception) { }
        // 2) 降级：拉前 2KB 看是否 #EXTM3U（不全下载，避免拉整个 mp4）
        try {
            val req = headerRequest(url, headers).newBuilder().header("Range", "bytes=0-2047").build()
            val resp = dlClient.newCall(req).execute()
            val `in` = resp.body?.byteStream() ?: return false
            val buf = ByteArray(2048); var total = 0; var n = 0
            while (total < 2048 && `in`.read(buf, total, 2048 - total).also { n = it } > 0) total += n
            `in`.close()
            val head = String(buf, 0, total, StandardCharsets.UTF_8).trimStart()
            return head.startsWith("#EXTM3U")
        } catch (_: Exception) { return false }
    }

    /** 带 headers 的 GET 文本 */
    private fun httpGetText(url: String, headers: Map<String, String>): String {
        Log.d("VidDl", "httpGetText GET ${url.take(120)} headers=${headers.keys}")
        dlClient.newCall(headerRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message} @ ${url.take(120)}")
            return resp.body?.string() ?: ""
        }
    }
    /** 带 headers 的 GET 字节（ts 切片） */
    private fun httpGetBytes(url: String, headers: Map<String, String>): ByteArray {
        dlClient.newCall(headerRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message} @ ${url.take(120)}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }
    /** 构造带 headers 的请求 */
    private fun headerRequest(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url).header("Accept", "*/*")
        if (headers.none { it.key.equals("User-Agent", true) }) b.header("User-Agent", "Mozilla/5.0")
        headers.forEach { (k, v) -> if (k.lowercase() !in HOP_BY_HOP) b.header(k, v) }
        return b.build()
    }

    /** 从 URL 推断扩展名（默认 mp4） */
    private fun guessExt(url: String): String {
        val p = url.substringBefore("?").substringBefore("#")
        val e = p.substringAfterLast(".", "")
        return if (e.length in 2..4) e.lowercase() else "mp4"
    }
    /** 按扩展名推断视频 mime（下载管理页用本地文件精确播放，避免靠 URI 推断失败） */
    private fun dlMimeOf(name: String): String = when {
        name.endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
        name.endsWith(".mpd", true) -> MimeTypes.APPLICATION_MPD
        name.endsWith(".mp4", true) -> MimeTypes.VIDEO_MP4
        name.endsWith(".ts", true) -> MimeTypes.VIDEO_MP2T
        name.endsWith(".flv", true) -> MimeTypes.VIDEO_FLV
        name.endsWith(".mkv", true) -> MimeTypes.VIDEO_MATROSKA
        name.endsWith(".webm", true) -> MimeTypes.VIDEO_WEBM
        else -> "video/*"
    }
    /** 文件名去除非法字符 */
    private fun sanitizeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { "video" }

    /** 下载完成后让媒体库扫描（仅私有目录需要；MediaStore 写入后自动可见） */
    private fun scanDownloaded(path: String) {
        if (path.isNotBlank()) MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf("video/*"), null)
    }

    // ---- 下载进度通知 ----
    private val DL_CHANNEL = "gdmusic_download"
    private val DL_NOTIFY_ID = 2001
    /** 当前下载标题与上次推送的进度（去重，避免 evaluateJavascript 洪水） */
    private var curDlTitle = ""
    private var lastDlPct = -1
    /** 把下载进度推到 WebView，让 App 内（视频页 / 下载管理页）也能看到进度条 */
    private fun dlProgressToJs(title: String, pct: Int, indeterminate: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onDlProgress)onDlProgress(${org.json.JSONObject.quote(title)},$pct,${if (indeterminate) "true" else "false"})", null)
        }
    }
    private fun dlHideToJs() {
        runOnUiThread { webView.evaluateJavascript("if(window.onDlHide)onDlHide()", null) }
    }
    private fun ensureDlChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(DL_CHANNEL) == null) {
                val ch = NotificationChannel(DL_CHANNEL, "从前慢下载", NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannel(ch)
            }
        }
    }
    private fun showDlProgress(title: String, pct: Int, indeterminate: Boolean) {
        ensureDlChannel()
        val n = NotificationCompat.Builder(this, DL_CHANNEL)
            .setContentTitle("下载中：$title")
            .setContentText(if (indeterminate) "准备下载…" else "$pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(DL_NOTIFY_ID, n)
        dlProgressToJs(title, pct, indeterminate)
    }
    private fun updateDlProgress(pct: Int) {
        if (pct == lastDlPct) return
        lastDlPct = pct
        val n = NotificationCompat.Builder(this, DL_CHANNEL)
            .setContentTitle("下载中")
            .setContentText("$pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(DL_NOTIFY_ID, n)
        dlProgressToJs(curDlTitle, pct, false)
    }
    private fun dismissDlProgress() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DL_NOTIFY_ID)
        dlHideToJs()
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
        videoTitle.visibility = View.GONE
        // 投屏遥控层是全屏覆盖，但锁按钮在 overlay 里、不在 PlayerView 内，
        // 不显式收掉会浮在遥控层上；同时把锁态解掉，免得退出投屏回来还锁着。
        forceUnlock()
        updatePipButton()
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

    /**
     * 切到第 i 集：
     *  - 爬虫源：回传 JS 经 playerContent 解析（原有逻辑）；
     *  - 播放页地址（非直链，如量子 /share/<hash>）：先让 JS 嗅探出真实直链再播。
     *    以前这里直接把播放页 HTML 丢给 ExoPlayer，就是用户看到的「Source error」；
     *  - 媒体直链：直接播。
     */
    private fun playIndex(i: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSwitchAtMs < 500L) return
        if (pendingResolveIdx == i) return          // 同一集正在嗅探，忽略连点
        lastSwitchAtMs = now
        if (miniMode || isInPictureInPictureMode) {
            toast("小窗播放时请展开后再切集")
            return
        }
        if (i < 0 || i >= epsList.size) {
            toast("剧集列表里没有第 ${i + 1} 集，请在选集页重新选")
            return
        }
        val ep = epsList[i]
        Log.d(
            "VidPlay",
            "切集请求 i=$i/${epsList.size} name=${ep.name} spider=${ep.spider} " +
                "url=${ep.url.take(80)} casting=$casting mini=$miniMode player=${player != null}"
        )
        if (!ep.spider && ep.url.isBlank()) {
            toast("「${ep.name}」没有播放地址，请在选集页换条线路/源")
            return
        }
        pendingResolveIdx = -1
        // 投屏中切集：把新集重新推给电视（旧 URI 先 Stop 再 SetAVTransportURI）
        if (casting) {
            if (ep.spider || !isPlayableUrl(ep.url)) {
                toast("投屏中不能切换需要解析的剧集，请先退出投屏")
                return
            }
            curIdx = i
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
            curIdx = i
            Log.d("VidPlay", "切集(爬虫源) -> __playSpiderAt($i)")
            safeEval("if(window.__playSpiderAt)window.__playSpiderAt($i);" +
                "else if(window.Android&&Android.onSpiderSwitchFailed)Android.onSpiderSwitchFailed('页面还没准备好')")
            return
        }
        if (!isPlayableUrl(ep.url)) {
            pendingResolveIdx = i
            Log.d("VidPlay", "切集(待解析) -> __resolveDirectAt($i) ${ep.url.take(80)}")
            toast("正在解析「${ep.name}」播放地址…")
            val b64 = Base64.encodeToString(ep.url.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            safeEval("if(window.__resolveDirectAt)window.__resolveDirectAt($i,'$b64');" +
                "else if(window.Android&&Android.onSpiderSwitchFailed)Android.onSpiderSwitchFailed('页面还没准备好')")
            return
        }
        curIdx = i
        finishSwitch(i, ep.url, "")
    }

    /** 看起来就是媒体直链（m3u8/mp4/mpd/flv/ts/mkv…），口径与 JS isDirectMedia 一致 */
    private fun isPlayableUrl(u: String): Boolean {
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        return arrayOf(".m3u8", ".mp4", ".mpd", ".flv", ".ts", ".mkv", ".mov", ".avi", ".webm")
            .any { path.endsWith(it) } || path.contains("/index.m3u8")
    }

    /** 切集落定：播新直链、换标题、同步历史（直链集与嗅探后的集共用） */
    private fun finishSwitch(i: Int, url: String, referer: String) {
        val ep = epsList[i]
        Log.d("VidPlay", "切集 -> ${ep.name}")
        // 不同集常落在不同 CDN：沿用上一集的 Referer 会被判盗链 403。
        // host 变了就不带 Referer；若仍 403，onPlayerError 会自动再去掉 Referer 重试一次。
        val newHost = runCatching { android.net.Uri.parse(url).host }.getOrNull()
        val oldHost = runCatching { android.net.Uri.parse(curCastUrl).host }.getOrNull()
        val ref = when {
            referer.isNotBlank() -> referer
            !newHost.isNullOrBlank() && !oldHost.isNullOrBlank() && newHost != oldHost -> ""
            else -> curReferer
        }
        // 切集同步 header：host 变了就去掉 Referer，其余（UA/Origin）沿用上一集
        val next = LinkedHashMap(curHeaders)
        next.keys.firstOrNull { it.equals("Referer", true) }?.let { next.remove(it) }
        if (ref.isNotBlank()) next["Referer"] = ref
        curHeaders = next
        playMediaUrl(url, ref)
        // 前缀（片名 · 线路）不动，只换集名；前缀为空就只显示集名
        curVideoTitle = if (curTitlePrefix.isNotBlank()) "${curTitlePrefix} · ${ep.name}" else ep.name
        videoTitle.text = curVideoTitle
        // 切集同步历史：集名/下标/直链变，播放位置从头开始
        VideoHistoryStore.updateEpisode(this, curHisKey, ep.name, i, url)
        curHisRec?.put("epName", ep.name)?.put("idx", i)?.put("url", url)?.put("positionMs", 0)
    }

    private fun playNext() {
        if (epsList.isEmpty()) {
            toast("这一路没有剧集列表，请在选集页重新点一集后再切")
            return
        }
        if (curIdx + 1 < epsList.size) playIndex(curIdx + 1)
        else toast("已是最后一集（共 ${epsList.size} 集）")
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

    /**
     * 播放层三态布局：内嵌（详情页顶部，竖屏）/ 全屏展开（横屏沉浸）/ 经典全屏。
     * 都不重建 ExoPlayer，只改 overlay 尺寸 + 屏幕方向 + 系统栏 + 控制条，进度无缝保留。
     * 内嵌态会驱动 WebView 详情页顶部占位（__setInlineSpace）给原生播放器腾位置。
     */
    private fun applyPlayerLayout() {
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        when {
            inlineMode && !inlineExpanded -> {
                // 内嵌：docking 在详情页顶部，高度 INLINE_HEIGHT_DP，竖屏、系统栏可见
                val h = (INLINE_HEIGHT_DP * resources.displayMetrics.density).toInt()
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = h
                lp.gravity = Gravity.TOP
                lp.setMargins(0, 0, 0, 0)
                overlay.layoutParams = lp
                playerView.useController = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                showSystemBars()
                fullBtn.visibility = View.VISIBLE
                fullBtn.setImageResource(R.drawable.ic_fullscreen)   // 展开全屏
                positionSideBtns(true)
                safeEval("if(window.__setInlineSpace)__setInlineSpace($INLINE_HEIGHT_DP)")
                // 不在这里手动藏小窗按钮：显隐统一交给 updatePipButton（见方法注释）
            }
            inlineExpanded -> {
                // 内嵌态下展开成横屏全屏：铺满、沉浸、隐藏系统栏
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.NO_GRAVITY
                lp.setMargins(0, 0, 0, 0)
                overlay.layoutParams = lp
                playerView.useController = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemBars()
                fullBtn.visibility = View.VISIBLE
                fullBtn.setImageResource(R.drawable.ic_fullscreen_exit)   // 收回内嵌
                positionSideBtns(false)
                safeEval("if(window.__setInlineSpace)__setInlineSpace(0)")
            }
            else -> {
                // 经典全屏（playVideoFull / 历史续播）：铺满横屏沉浸
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.NO_GRAVITY
                lp.setMargins(0, 0, 0, 0)
                overlay.layoutParams = lp
                playerView.useController = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemBars()
                fullBtn.visibility = View.GONE
                positionSideBtns(false)
            }
        }
        // 布局切换后同步小窗按钮 / 标题的显隐
        updatePipButton()
    }

    /**
     * 上一集/下一集按钮容器 + 全屏键定位：
     *  - sideBtns 竖排靠右：内嵌态沉到右上（避开顶部标题/时钟），全屏态居中靠右；
     *  - 全屏键真正贴右下角（end 8dp / bottom 4dp）。底部「片头/片尾/投屏」配置行也贴底
     *    4dp 且居中，右侧留白足够；时间/按钮行已上移到 90~150dp，不会与贴底的全屏键重叠。
     *    旧值 54dp / 112dp 把按钮悬在半空，用户反馈「没有真的贴右下角」。
     */
    private fun positionSideBtns(inline: Boolean) {
        val d = resources.displayMetrics.density
        val lp = sideBtns.layoutParams as FrameLayout.LayoutParams
        val flp = fullBtn.layoutParams as FrameLayout.LayoutParams
        if (inline) {
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = (50 * d).toInt()
        } else {
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            lp.topMargin = 0
        }
        lp.bottomMargin = 0
        lp.marginEnd = (8 * d).toInt()
        // 与 skipCfgRow（marginBottom=4dp）齐平，右端留 8dp
        flp.bottomMargin = (4 * d).toInt()
        flp.marginEnd = (8 * d).toInt()
        sideBtns.layoutParams = lp
        fullBtn.layoutParams = flp
    }

    /** 内嵌态下点全屏按钮：铺满横屏（进度保留） */
    private fun enterFullscreen() {
        if (!inlineMode) return
        inlineExpanded = true
        applyPlayerLayout()
    }

    /** 全屏返回：收回详情页顶部内嵌继续播（进度保留） */
    private fun shrinkToInline() {
        if (!inlineMode) return
        inlineExpanded = false
        applyPlayerLayout()
    }

    private fun closeVideo() {
        hideEpisodePanel()
        forceUnlock()
        if (casting) stopCast(false)   // 关播放器：电视端也要停，但不切回本地
        saveHisPosition()
        hisHandler.removeCallbacks(hisTick)
        stopSkipTick()
        player?.pause()
        player?.stop()
        overlay.visibility = View.GONE
        clockHandler.removeCallbacks(clockTick)
        // 内嵌态关播放器：复位内嵌标志 + 详情页顶部占位 + 播放层尺寸，避免下次起播残留内嵌尺寸
        if (inlineMode) {
            inlineMode = false
            inlineExpanded = false
            fullBtn.visibility = View.GONE
            safeEval("if(window.__setInlineSpace)__setInlineSpace(0)")
            val lp = overlay.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, 0)
            overlay.layoutParams = lp
        }
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
        videoTitle.visibility = View.GONE
        updatePipButton()
        // 返回后把"播放前那一页"找回来：若刚才是全网搜索，恢复搜索页+该视频详情弹窗，
        // 避免退回 App 首页推荐流（页面 DOM 仍在，只是要让对应浮层重新显示）。
        webView.post { safeEval("if(window.__restoreAfterPlay)window.__restoreAfterPlay()") }
    }

    /** 保持屏幕常亮（仅播放时） */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 小窗按钮 + 视频标题：仅在「未进小窗 + 正在播放 + 控制条可见」时显示。
     *  控制条自动隐藏（持续播放几秒后）时二者一并隐藏，点屏幕唤出控制条即重新出现，
     *  与暂停/快进按钮保持一致。
     *
     *  ⚠️ 这里不能再拿 inlineMode 当隐藏条件：详情页起播默认就是内嵌态（inlineMode=true），
     *  用它一刀切会把「小窗按钮」整个藏掉 —— 这就是「影视播放时小窗按钮不见了」的根因。
     *  真正要区分的是「紧凑短条」还是「已展开全屏」，所以用 isCompactInline。 */
    private fun updatePipButton() {
        // 形态切到小窗 / 系统 PiP / 投屏 / 锁定 时，选集抽屉不该继续盖在上面
        if (miniMode || isInPictureInPictureMode || locked || casting) hideEpisodePanel()
        // 小窗 / 系统 PiP / 投屏 / 暂停 / 控制条隐藏 / 锁定态 时都不显示
        val show = !miniMode && !isInPictureInPictureMode && !locked &&
            player?.isPlaying == true && controlsVisible
        // 小窗按钮：所有形态都显示（含紧凑短条）—— 顶部左侧是空的，不挡任何东西。
        videoPip.visibility = if (show) View.VISIBLE else View.GONE
        // 标题只在「非紧凑短条」时显示：紧凑短条里标题居中（maxWidth 300dp）会与小窗按钮撞上，
        // 而详情页下方本来就有片名，藏掉不亏；尚未起播（标题为空）时也不占位。
        videoTitle.visibility =
            if (show && !isCompactInline && curVideoTitle.isNotBlank()) View.VISIBLE else View.GONE
        // 所有形态切换都汇到这里，锁按钮 / 右侧竖排上下集的显隐统一在这两个方法里收口
        syncLockButton()
        syncEpisodeButtons()
    }

    /** 上一集：中间控制条（全屏主入口）与右侧竖排（紧凑内嵌短条）共用同一套切集逻辑 */
    private fun playPrev() {
        if (curIdx <= 0) toast("已经是第一集（共 ${epsList.size} 集）") else playIndex(curIdx - 1)
    }

    /**
     * 点左侧锁按钮：锁住 / 解开播放界面。锁定只作用于交互层，视频照常播放、进度照常走。
     * 锁定时：控制条强制隐藏且不许再冒出来、屏幕手势全部吞掉、顶部标题/时钟/小窗/
     * 全屏键/右侧上下集一并隐藏，只留左侧锁按钮常驻（图标变闭合锁）用来解锁。
     */
    private fun toggleLock() {
        if (miniMode || isInPictureInPictureMode || casting || isCompactInline) return
        locked = !locked
        if (locked) {
            // 锁屏时顺手收掉选集抽屉，否则它会盖在锁屏层上面还能点
            hideEpisodePanel()
            // 记下锁定前状态，解锁时原样恢复（fullBtn 在经典全屏本来就是 GONE，
            // 不能一律设成 VISIBLE；也别用 applyPlayerLayout 重置，那会连带改方向/系统栏）
            fullBtnVisBeforeLock = fullBtn.visibility
            clockVisBeforeLock = videoClock.visibility
            playerView.hideController()
            videoPip.visibility = View.GONE
            videoTitle.visibility = View.GONE
            videoClock.visibility = View.GONE
            fullBtn.visibility = View.GONE
            toast("已锁定播放界面，点左侧 🔒 解锁")
        } else {
            playerView.showController()
            videoClock.visibility = clockVisBeforeLock
            fullBtn.visibility = fullBtnVisBeforeLock
            toast("已解锁播放界面")
        }
        Log.d("VidPlay", "锁定播放界面=$locked")
        updatePipButton()
    }

    /** 锁按钮：只在「全屏 + 本地播放 + 播放层可见」时出现。
     *  未锁时跟控制条一起显隐（控制条自动隐藏后不碍眼）；锁定后常驻，否则无法解锁。 */
    private fun syncLockButton() {
        val canShow = !miniMode && !isInPictureInPictureMode && !casting &&
            !isCompactInline && overlay.visibility == View.VISIBLE
        videoLock.visibility =
            if (!canShow) View.GONE
            else if (locked || controlsVisible) View.VISIBLE
            else View.GONE
        videoLock.text = if (locked) "🔒" else "🔓"
        videoLock.contentDescription = if (locked) "解锁播放界面" else "锁定播放界面"
        videoLock.alpha = if (locked) 1f else 0.85f
    }

    /** 右侧竖排上下集：全屏态已搬到中间播放/暂停左右 → 隐藏，避免同屏两套；
     *  紧凑内嵌短条控制条进 minimal、中间按钮组不可见 → 保留，切集功能不丢。
     *  小窗 / 系统 PiP / 投屏 / 锁定态一律隐藏。 */
    private fun syncEpisodeButtons() {
        val usable = !miniMode && !isInPictureInPictureMode && !casting &&
            !locked && overlay.visibility == View.VISIBLE
        val show = usable && isCompactInline
        sideBtns.visibility = if (show) View.VISIBLE else View.GONE
        // 顺手兜住一个老问题：进 App 内小窗时 enterMini() 把两个子按钮单独 GONE 了，
        // 退出小窗的 exitMini() 没恢复；容器虽然 VISIBLE，按钮却是空的。
        // 这里在「确定要显示右侧竖排」时把子按钮显式拉回来。
        if (show) {
            videoPrev.visibility = View.VISIBLE
            videoNext.visibility = View.VISIBLE
        }
    }

    /** 离开全屏形态（进小窗 / 系统 PiP / 关播放器 / 切投屏）时强制解锁，
     *  否则锁态会残留到别的界面：控制条被 hide、手势被吞、回到全屏还锁着。 */
    private fun forceUnlock() {
        if (!locked) return
        locked = false
        videoClock.visibility = clockVisBeforeLock
        fullBtn.visibility = fullBtnVisBeforeLock
        Log.d("VidPlay", "离开全屏形态，自动解锁")
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

    /**
     * 从「内嵌短条」形态进小窗/悬浮窗前调用：把详情页顶部给原生播放器腾的占位撤掉。
     * 否则原生播放器已经不在那个位置了，详情页顶部会留一条 230px 空白。
     * 顺带复位 inlineMode，让后续按「经典全屏」处理（横屏铺满）。
     */
    private fun clearInlineDock() {
        if (!inlineMode) return
        inlineMode = false
        inlineExpanded = false
        fullBtn.visibility = View.GONE
        safeEval("if(window.__setInlineSpace)__setInlineSpace(0)")
    }

    /** 交给系统悬浮窗播放：同一个 player 实例挂到浮层上，不重新缓冲 */
    private fun startFloat() {
        val p = player ?: return
        forceUnlock()
        clearInlineDock()                 // 撤掉详情页顶部占位，别留空白
        VideoFloatState.player = p
        playerView.player = null            // 先摘下来，交给浮层
        videoPip.visibility = View.GONE
        videoTitle.visibility = View.GONE
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
        updatePipButton()
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
        forceUnlock()
        miniMode = true
        clearInlineDock()                 // 撤掉详情页顶部占位，别留空白
        miniUserMoved = false   // 每次进小窗回到默认右下角；拖动后不再被转屏/换清晰度弹回
        miniScale = 1.0f        // 每次进小窗回到默认大小，缩放从 1.0 重新开始
        // 先缩窗口再转竖屏，避免全屏播放器在竖屏下闪一下
        applyMiniLayout()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
        setKeepScreenOn(false)
        videoPip.visibility = View.GONE
        videoTitle.visibility = View.GONE
        videoClock.visibility = View.GONE
        videoPrev.visibility = View.GONE
        videoNext.visibility = View.GONE
        videoMiniLayer.visibility = View.VISIBLE
        playerView.useController = false
        updatePipButton()
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
            forceUnlock()
            wasMiniBeforePip = miniMode
            videoPip.visibility = View.GONE
            videoTitle.visibility = View.GONE
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
                videoTitle.visibility = View.GONE
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
        // 标题与 updatePipButton 同条件（这行兜住 PiP 退出/小窗恢复两条路径的瞬间状态）
        videoTitle.visibility =
            if (miniMode || isCompactInline || curVideoTitle.isBlank() || !controlsVisible ||
                player?.isPlaying != true) View.GONE else v
        // PlayerView 自带的播放控制条也隐藏：系统小窗只几寸大，控制条会糊满画面；
        // 退出小窗恢复（useController 切换会重建 SubtitleView 等子视图，必须主线程）
        playerView.useController = !isInPictureInPictureMode && !miniMode
        updatePipButton()
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
        runCatching { unregisterReceiver(noisyReceiver) }
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
