package com.linghit.gdmusic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Song(
    val id:String, val name:String, val artist:String, val album:String,
    val source:String, val picId:String, val lyricId:String,
    /** 本地文件地址（导入/缓存歌专用）：有了它 key 才唯一，否则导入歌 id/source 全空会共用一个 key "|"，
     *  导致 localUri 按 key 查永远命中第一条 → 点哪首都播固定的那一首 */
    val localUri:String="",
    /** 直链音频地址（播客单集 / 电台流）：非空时 PlayerManager 跳过在线解析直接播，用于播客/电台独立内容线 */
    val directUrl:String="",
    /** 直链封面地址（播客/电台封面）：非空时优先用 OkHttp 直接下载，不走音乐 API 封面接口 */
    val coverUrl:String=""
) {
    fun key() = when {
        localUri.isNotBlank() -> "local|$localUri"
        directUrl.isNotBlank() -> "direct|$directUrl"
        else -> "$source|$id"
    }
}

/** 音源 id -> 中文名。保留旧音源(tencent/kugou/kuwo/migu)兜底，避免旧收藏/离线记录显示原始 id */
fun sourceLabel(s:String) = when(s){
    "netease"->"网易云"; "joox"->"JOOX"; "bilibili"->"B站"
    "tencent"->"QQ音乐"; "kugou"->"酷狗"; "kuwo"->"酷我"; "migu"->"咪咕"
    "tidal"->"Tidal"; "qobuz"->"Qobuz"; "apple"->"Apple Music"
    "ytmusic"->"YouTube Music"; "spotify"->"Spotify"
    else->s
}

class MainActivity : AppCompatActivity(), PlayerUiListener {
    // 接口地址改为可配置：默认 GD 音乐台，用户可在「设置」里更换（防接口失效无需重装）
    private val api: String
        get() {
            val raw = prefs.getString(SettingsActivity.KEY_API, SettingsActivity.DEFAULT_API)
                ?: SettingsActivity.DEFAULT_API
            return normalizeApi(raw)
        }
    // 实测结论(2026-09-28)：本 API 只有 netease 能端到端播放。
    private val sources=listOf("netease")
    // 每页条数，与 API 的 count 对应。实测：count=50 稳定；count=100 返回 0 条，故取 50
    private val pageSize=50
    private var currentPage=1
    private var isLoadingMore=false
    private var hasMore=true
    private var lastKeyword=""
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(15,TimeUnit.SECONDS).build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)

    private lateinit var input:EditText; private lateinit var status:TextView
    private lateinit var nowPlaying:TextView; private lateinit var nowArtist:TextView
    // 播放器已从 Activity 成员改为全局单例（PlayerManager），从根本上避免息屏被回收导致停播
    private val player get() = PlayerManager.getPlayer()
    private lateinit var playPauseBtn:ImageButton
    private lateinit var prevBtn:ImageButton
    private lateinit var nextBtn:ImageButton
    private lateinit var modeBtn:ImageButton
    private lateinit var playerBar:View
    // 三场景续听 chips（有音乐/播客队列或上次电台时才显隐）
    private lateinit var sceneResumeRow: View
    private lateinit var chipResumeMusic: TextView
    private lateinit var chipResumePodcast: TextView
    private lateinit var chipResumeRadio: TextView
    // 分段切换：播放列表（默认）/ 搜索
    private lateinit var tabPlaylist:TextView
    private lateinit var tabSearch:TextView
    private lateinit var playlistPanel:View
    private lateinit var searchPanel:View
    /** 页签内容区：左右滑动切页签（SwipeFrameLayout，只拦横向手势） */
    private lateinit var tabContent: SwipeFrameLayout
    /** 首页自定义资源位容器（不限量，记账页 📌 / 「＋」面板添加；点=进入，长按抓起可拖动排序，按住不动=删除） */
    private lateinit var shortcutRow: FlowLayout
    // —— 首页双列信息卡（第一排：黄金金价 + 高铁日程；第二排：今日消费 + 新闻热榜）——
    private lateinit var goldCard: View
    private lateinit var goldDomVal: TextView
    private lateinit var goldIntlVal: TextView
    private lateinit var railCard: View
    private lateinit var railCardVal: TextView
    private lateinit var railCardSub: TextView
    private lateinit var spendCard: View
    private lateinit var spendVal: TextView
    private lateinit var spendCnt: TextView
    private lateinit var newsCard: View
    private lateinit var newsTitle: TextView
    private lateinit var newsSub: TextView
    /** 兜底底色：无每日一图时露出的主题渐变（原头部渐变改为全屏通幅） */
    private lateinit var fallbackBg: View
    /** 每日一图（全屏沉浸背景）：用户文件夹按日轮换，点问候行手动换 */
    private lateinit var dailyImage: ImageView
    private lateinit var greetingRow: View
    private lateinit var greetingText: TextView
    private lateinit var dateText: TextView
    // —— 首页自定义入口拖动排序状态（长按抓起 → 移动=实时换序，按住不动=删除） ——
    private var dragChip: View? = null
    private var dragIndex = -1
    private var dragOrder: MutableList<HomeShortcutStore.Item> = mutableListOf()
    private var grabReady = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private val flowLoc = IntArray(2)
    /** 当前已确认的落点槽位（带迟滞，见 hitSlot）：防止手指停在两 chip 边界时来回翻 */
    private var hitHold = -1
    private val grabHandler = Handler(Looper.getMainLooper())
    /** 长按抓起延时：比系统长按(500ms)略短，先抓住并给视觉反馈 */
    private val grabDelayMs = 300L
    private val chipTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val grabRun = Runnable {
        grabReady = true
        Log.i("ChipDrag", "grab target=${dragChip?.tag?.let { (it as? HomeShortcutStore.Item)?.target }}")
        dragChip?.let { c ->
            c.isPressed = false
            c.bringToFront() // 置顶：跟手时不被后面的 chip 盖住（只改绘制序，不发 CANCEL）
            c.elevation = 10f
            c.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.75f).setDuration(90).start()
            c.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private var showSearch=false
    private val uiHandler=Handler(Looper.getMainLooper())
    private lateinit var adapter:SongAdapter
    private var selectedSources=sources
    private val prefs by lazy { getSharedPreferences("music",Context.MODE_PRIVATE) }

    /** Android 13+ 通知权限请求器：授权后开启前台播放通知（息屏续播的锁屏/通知栏控制） */
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 权限状态由 PlayerManager 实时查询，这里只需在刚授权时补一次前台保活
        if (granted) runCatching { PlayerManager.updateForeground() }
    }

    private fun syncNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 上次若崩过，重开 App 直接弹窗展示堆栈，可一键复制（免 adb 排查） */
    private fun showCrashLogIfAny() {
        val log = CrashHandler.read(this)
        if (log.isBlank()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("上次崩溃信息")
            .setMessage(log.takeLast(4000))
            .setPositiveButton("复制") { d, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                Toast.makeText(this, "已复制崩溃日志", Toast.LENGTH_SHORT).show()
                CrashHandler.clear(this); d.dismiss()
            }
            .setNegativeButton("清除") { d, _ -> CrashHandler.clear(this); d.dismiss() }
            .setNeutralButton("关闭", null)
            .show()
    }

    // 离线下载：静默缓存存到 App 私有音乐目录（卸载即清，不污染相册）
    private val musicDir by lazy { getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.apply { mkdirs() } }
    private val dlStates = mutableMapOf<String,String>() // song.key() -> none|downloading|done|error

    // 手动下载：临时挂起的歌曲 + SAF 目录选择器（每次点⬇都弹系统文件夹选择器）
    private var pendingDownload: Song? = null
    private val pickDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val song = pendingDownload ?: return@registerForActivityResult
        pendingDownload = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { /* 少数机型不授予持久权限，本次仍可用 */ }
        prefs.edit().putString("lastTreeUri", uri.toString()).apply()
        scope.launch(Dispatchers.IO) { downloadToTree(song, uri) }
    }

    /** 每日一图文件夹选择器：SAF 选一次并持久化授权，之后往文件夹扔图即自动轮换 */
    private val dailyFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        DailyImageStore.setTreeUri(this, uri)
        loadDailyImage()
        val n = DailyImageStore.imageCount(this)
        Toast.makeText(
            this,
            if (n > 0) "已选好，共 $n 张图，每天自动轮换" else "这个文件夹里还没有图片",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(b:Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_main)
        input=findViewById(R.id.searchInput); status=findViewById(R.id.statusText)
        nowPlaying=findViewById(R.id.nowPlaying); nowArtist=findViewById(R.id.nowArtist)
        CrashHandler.init(this) // 崩溃捕获：闪退后重开 App 进「设置 → 崩溃日志」即可查看（免 adb）
        showCrashLogIfAny()     // 上次崩过则直接弹窗显示堆栈，可一键复制
        DesktopLyric.init(this) // 桌面歌词（悬浮窗）控制器

        PlayerManager.init(this) // 初始化全局播放管理器（幂等）
        // 通知权限（Android 13+ 必须，否则播放时前台服务 startForeground 会崩溃）
        syncNotificationPermission()
        playPauseBtn=findViewById(R.id.playPauseBtn)
        prevBtn=findViewById(R.id.prevBtn)
        nextBtn=findViewById(R.id.nextBtn)
        modeBtn=findViewById(R.id.modeBtn)
        playerBar=findViewById(R.id.playerBar)
        // 播放条按需求常驻（没歌也显示占位），不再按「有无正在播放」隐藏
        playerBar.visibility=View.VISIBLE
        // 整条播放条（除 prev/playpause/next/mode 四个控制按钮外）点击进队列页看完整列表/切歌/移除。
        // 四个控制按钮各自消费了 click，点它们不会触发这里；点歌名/空白区会冒泡到 playerBar。
        playerBar.setOnClickListener {
            if (PlayerManager.current != null) startActivity(Intent(this, QueueActivity::class.java))
        }
        // 播放/暂停统一走 PlayerManager.togglePlayPause：冷启动恢复的「当前歌」可能还没加载进
        // 播放器（媒体项为空），直接 player.play() 是空操作、点了没反应，里面会兜底重新起播
        playPauseBtn.setOnClickListener{ PlayerManager.togglePlayPause() }
        // 播放条切歌：上一首/下一首（与歌词页、桌面歌词共用 PlayerManager 队列）
        prevBtn.setOnClickListener { PlayerManager.skipPrev() }
        nextBtn.setOnClickListener { PlayerManager.skipNext() }
        modeBtn.setOnClickListener {
            val m = (PlayerManager.playMode + 1) % 3
            PlayerManager.setMode(m)
            val tip = when (m) { 1 -> "列表循环"; 2 -> "随机播放"; else -> "顺序播放（播完停）" }
            status.text = tip
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        }
        applyPlayMode()
        // 上次开着桌面歌词的话，重启后自动恢复（进程被杀窗口也会没，需重建）
        if (DesktopLyric.enabled && DesktopLyric.hasPermission()) DesktopLyric.show()
        // 常驻悬浮按钮：默认开启（真正显示还需「显示在其他应用上层」权限，服务里兜底判断）；
        // 关掉/改动作在设置页或长按小球
        if (FloatButtonStore.enabled(this)) FloatButtonService.start(this)
        uiHandler.post(progressRunnable)

        adapter=SongAdapter(
            // 点单首=临时播放：只播这首，不动播放列表（要进列表用每行的 ⏫ 插入）
            onPlay = { PlayerManager.playTemp(it) },
            onFavorite = { s -> onStarClick(s); adapter.notifyDataSetChanged() },
            isFavorite = { isFavorite(it) },
            onDownload = { requestManualDownload(it) },
            dlState = { dlStates[it.key()] ?: if (isDownloaded(it)) "done" else "none" },
            onInsert = { PlayerManager.insertFrontAndPlay(it) }
        )
        findViewById<RecyclerView>(R.id.songList).apply {
            val lm=LinearLayoutManager(this@MainActivity)
            layoutManager=lm; adapter=this@MainActivity.adapter
            addOnScrollListener(object:RecyclerView.OnScrollListener(){
                override fun onScrolled(rv:RecyclerView,dx:Int,dy:Int){
                    if(dy>0 && !isLoadingMore && hasMore &&
                        lm.findLastVisibleItemPosition() >= this@MainActivity.adapter.itemCount-3){
                        loadMore()
                    }
                }
            })
        }
        findViewById<Button>(R.id.searchButton).setOnClickListener { search() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) { search(); true }
            else false
        }

        findViewById<View>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 自定义资源位：固定入口 + 自定义快捷方式 + 「＋」统一网格渲染（见 renderShortcuts）
        shortcutRow = findViewById(R.id.shortcutRow)
        goldCard = findViewById(R.id.goldCardRow)
        goldDomVal = findViewById(R.id.goldDomVal)
        goldIntlVal = findViewById(R.id.goldIntlVal)
        railCard = findViewById(R.id.railCardRow)
        railCardVal = findViewById(R.id.railCardVal)
        railCardSub = findViewById(R.id.railCardSub)
        spendCard = findViewById(R.id.spendCardRow)
        spendVal = findViewById(R.id.spendVal)
        spendCnt = findViewById(R.id.spendCnt)
        newsCard = findViewById(R.id.newsCardRow)
        newsTitle = findViewById(R.id.newsTitle)
        newsSub = findViewById(R.id.newsSub)
        // 信息卡点击：黄金=手动刷新金价；高铁=进高铁往返页；消费=进记账页；新闻=进热榜页
        // ⚠️ 必须挂内部行（xxCardRow）而非外层 MaterialCardView：内部 LinearLayout 是
        // clickable 且铺满卡片，点击被它消费，挂外层监听永远收不到（同 queueEntryRow 教训）
        goldCard.setOnClickListener { loadGoldPrice() }
        railCard.setOnClickListener { startActivity(Intent(this, RailActivity::class.java)) }
        spendCard.setOnClickListener { startActivity(Intent(this, NetAssetActivity::class.java)) }
        newsCard.setOnClickListener { startActivity(Intent(this, NewsActivity::class.java)) }
        fallbackBg = findViewById(R.id.themeFallbackBg)
        dailyImage = findViewById(R.id.dailyImage)
        greetingRow = findViewById(R.id.greetingRow)
        greetingText = findViewById(R.id.greetingText)
        dateText = findViewById(R.id.dateText)
        applyThemeColors() // 按当前主题重画兜底底色与播放按钮圆圈（drawable 无法用 ?attr 上色）
        // 每日一图：载入今天该显示的那张；顶栏「切背景图」按钮 / 点问候行 = 手动换一张，长按=更换图片文件夹
        loadDailyImage()
        greetingRow.setOnClickListener { switchDailyImage() }
        findViewById<View>(R.id.switchBgBtn).setOnClickListener { switchDailyImage() }
        // 三场景续听 chips：绑定 + 点击切场景续听（音乐/播客互不覆盖，电台续听上次单流）
        sceneResumeRow = findViewById(R.id.sceneResumeRow)
        chipResumeMusic = findViewById(R.id.chipResumeMusic)
        chipResumePodcast = findViewById(R.id.chipResumePodcast)
        chipResumeRadio = findViewById(R.id.chipResumeRadio)
        chipResumeMusic.setOnClickListener { PlayerManager.switchScene(Scene.MUSIC); refreshSceneResume() }
        chipResumePodcast.setOnClickListener { PlayerManager.switchScene(Scene.PODCAST); refreshSceneResume() }
        chipResumeRadio.setOnClickListener { PlayerManager.switchScene(Scene.RADIO); refreshSceneResume() }
        greetingRow.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("每日一图")
                .setMessage("图片放在你选的文件夹里，每天 0 点自动轮换；点首页问候行可手动换一张。")
                .setPositiveButton("更换文件夹") { _, _ -> dailyFolderLauncher.launch(null) }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        // 主题切换：弹出选色面板（12 套色板，含渐变），选中即应用并重建页面
        findViewById<View>(R.id.themeBtn).setOnClickListener { showThemePicker() }
        // 等宽 chip 需要实测行宽：onCreate 时 shortcutRow.width 还是 0，
        // 先按估算渲染一版，布局完成后再用真实宽度精确渲染一次
        renderShortcuts()
        shortcutRow.post { renderShortcuts() }
        // 高铁日程卡：距上次离家 N 天 / 正在家 / 下次计划（无记录显示「暂无行程」）
        loadRailHomeStat()
        // 今日消费卡：读记账页 flows（type=支出）按今天日期求和
        loadTodaySpend()
        // 新闻卡：拉热榜第 1 条（最新）展示，进首页就刷一次
        loadNewsHot()
        // 音源胶囊（全部/网易云）与「播放全部结果」已按需求隐藏：本 API 仅网易云可用，搜索即全量结果

        // ===== 主面板（进 App 默认落点）：首页 / 搜索两个页签 =====
        tabPlaylist=findViewById(R.id.tabPlaylist); tabSearch=findViewById(R.id.tabSearch)
        playlistPanel=findViewById(R.id.playlistPanel); searchPanel=findViewById(R.id.searchPanel)
        // 队列入口 = 底部播放条整条点击进 QueueActivity（在播放条初始化处已设 onClick）
        tabPlaylist.setOnClickListener { switchTab(false) }
        tabSearch.setOnClickListener { switchTab(true) }
        switchTab(false) // 默认进入首页

        // ===== 左右滑动切页签（与点 pill 等效）：左滑=搜索，右滑=回首页 =====
        tabContent = findViewById(R.id.tabContent)
        // 快捷方式格子区排除：那里靠横向拖动排序，不能被滑动手势抢（否则拖动被 CANCEL）
        tabContent.addExcludedView(shortcutRow)
        tabContent.onSwipe = { dir -> switchTab(dir == 0) }

        // 外部播放意图（下载页/收藏页的播放全部）必须放到所有视图初始化之后再处理
        handlePlayIntent()
        // QueueActivity 空列表的「去搜索」跳回来时带 open_tab=search，直接切到搜索页签
        if(intent.getStringExtra("open_tab")=="search") switchTab(true)

        // 注册为播放 UI 监听，并把当前播放状态同步到播放条（用于息屏后重新打开 App）
        PlayerManager.uiListener = this
        // 进程上次被杀过：恢复上次的播放列表和高亮（不自动播放），
        // 否则用户从下载页点过播放全部、退出重进列表就空了
        PlayerManager.restoreQueueIfEmpty()
        PlayerManager.onUiAttached()
    }

    /** 播放条显隐：按需求常驻——只在搜索页签下隐藏（给搜索结果腾地方），
     *  不再要求有正在播放的歌；没歌时显示「未播放 / 还没有在播放的歌」占位 */
    private fun syncPlayerBar(){
        playerBar.visibility = if(showSearch) View.GONE else View.VISIBLE
    }

    /** 分段切换：showSearch=false 首页 / true 搜索 */
    private fun switchTab(search: Boolean){
        showSearch=search
        playlistPanel.visibility=if(search) View.GONE else View.VISIBLE
        searchPanel.visibility=if(search) View.VISIBLE else View.GONE
        syncPlayerBar()
        tabPlaylist.setBackgroundResource(if(search) R.drawable.bg_pill_glass else R.drawable.bg_pill_white)
        tabPlaylist.setTextColor(ContextCompat.getColor(this, if(search) R.color.on_primary else R.color.primary))
        tabSearch.setBackgroundResource(if(search) R.drawable.bg_pill_white else R.drawable.bg_pill_glass)
        tabSearch.setTextColor(ContextCompat.getColor(this, if(search) R.color.primary else R.color.on_primary))
        if(!search) refreshQueueEntry()
    }

    override fun onResume() {
        super.onResume()
        // 记账页可能钉了新的链接资源位回来，刷新一行
        if (::shortcutRow.isInitialized) renderShortcuts()
        // 切回前台自动刷新顶部金价（首次进 App 也走这里；接口失败则显示「--」）
        if (::goldDomVal.isInitialized) loadGoldPrice()
        // 高铁日程卡：从高铁页保存后回到首页，同步「距上次离家 N 天」
        if (::railCard.isInitialized) loadRailHomeStat()
        // 今日消费卡：从记账页记完账回来，同步今日支出合计
        if (::spendVal.isInitialized) loadTodaySpend()
        // 新闻卡：每次回前台都刷新成当前热榜第 1 条
        if (::newsTitle.isInitialized) loadNewsHot()
        // 每日一图：跨天自动轮换（在后台过 0 点回来时刷新），文件夹换过也在这里生效
        if (::dailyImage.isInitialized) loadDailyImage()
        // 从 QueueActivity 等子页面回来：重新抢回播放 UI 回调（uiListener 是单槽，
        // 子页面 onResume 会抢走；不抢回来息屏切歌后首页播放条就不更新了）
        PlayerManager.uiListener = this
        // 队列可能在 QueueActivity 里被改过（移除/清空），同步入口卡计数
        refreshQueueEntry()
        // 续听 chips 也可能因队列变化需要刷新显隐
        refreshSceneResume()
    }

    /** 无边界涟漪（按压反馈）：网格版 chip 没有 pill 底色，靠这个给触摸反馈 */
    private fun borderlessRipple(): android.graphics.drawable.Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    /** 固定入口（原工具行的四个 pill，现收编为统一网格前 4 格，不可拖不可删） */
    private val fixedShortcuts = listOf(
        HomeShortcutStore.Item("page", "netasset", "记账", "📒"),
        HomeShortcutStore.Item("page", "video", "看片", "🎬"),
        HomeShortcutStore.Item("page", "favorites", "收藏", "❤"),
        HomeShortcutStore.Item("page", "podcast", "播客", "🎙"),
        HomeShortcutStore.Item("page", "downloads", "下载", "⬇"),
        // 端侧 AI（一期验证页）：手机本地跑 LiteRT-LM 模型，零 Key 零费用
        HomeShortcutStore.Item("page", "ai", "AI", "🤖")
    )

    /**
     * 首页资源位统一网格：固定入口 + 自定义资源位 + 「＋」同一样式，一行 6 个等宽 cell。
     *  - page 类徽章 = 自带 emoji，软底用主题色 33% 透明
     *  - link 类徽章 = 站点字典 emoji 或「首字+哈希配色」字母徽章（见 [ShortcutBadge]）
     *  - 名称 11sp 居中、最多两行、超出省略——横向宽度全部给文字，根治截断
     *  - 固定入口 + 自定义 + 「＋」同一网格不区分样式：固定 4 项在前，自定义随后，「＋」收尾
     *  - 只有自定义 cell 挂 tag=item + 触摸监听（可拖可删）；固定项与「＋」无 tag，
     *    childIndexAt 对无 tag 的 view 返回 -1，天然不会成为拖动落点
     *  - 一行 6 个等宽的公式（含 -6 份右间距的坑）见下，FlowLayout 换行行为不变
     */
    private fun renderShortcuts() {
        shortcutRow.removeAllViews()
        val d = resources.displayMetrics.density
        val badgeSize = (48 * d).toInt()
        val marginV = (4 * d).toInt()
        // 一行 6 格，**首格左缘对齐、末格右缘对齐**：格间空隙不手填，由行宽均分派生——
        // gapH = (行宽 − 6×徽章直径) / 5；cell 宽 = 徽章直径（徽章即格），
        // 行末 cell 右 margin 归零 → 6 格总宽 = 6×徽章 + 5×gapH ≤ 行宽。
        // ⚠️ FlowLayout 换行判断是 x + (子宽+左右margin) > 行宽（严格大于，见 FlowLayout.onMeasure），
        // 均分后正好相等不掉行；整数除法最多剩几 px 尾差，看不见。
        // ⚠️ 旧方案「等宽 cell + 每格右 margin」首末永远偏出 half cell + margin 贴不了边；
        // 且 marginH 调多大都被 chipW 同步变小抵消（视觉间距恒 ≈8dp），已废弃。
        // 现在调松紧只剩一个入口：badgeSize（徽章越大间隙越小）。
        // 行宽取 shortcutRow 实测宽（布局后）；首次渲染可能还是 0，用屏宽-父容器横向 padding 兜底。
        // ⚠️ 兜底值必须和实际 chrome 对齐：playlistPanel 12dp + 玻璃卡 4dp×2 = 20dp。
        // 曾写成 24dp（旧卡 12dp 时代）→ gapH 算大 → 第一帧 6 格超行宽掉一行，post 重渲染才纠正，肉眼能看到一跳。
        val perRow = 6
        val rowW = if (shortcutRow.width > 0) shortcutRow.width
        else resources.displayMetrics.widthPixels - (20 * d).toInt()
        val gapH = ((rowW - badgeSize * perRow) / (perRow - 1)).coerceAtLeast(0)
        val labelW = badgeSize + gapH
        val primary = ThemePrefs.currentPack(this).primary
        val softPrimary = (primary and 0x00FFFFFF) or 0x55000000.toInt()

        /** 固定入口（原工具行的四个 pill，现收编为网格前 4 格，点击走 ShortcutRouter 同一套路由） */
        val fixed = fixedShortcuts
        val fixedIds = fixed.map { it.id() }.toSet()
        // 自定义项里与固定入口同 id 的（如旧版加过的「影视仓/记账本」）不再重复渲染，避免一格两入口
        val custom = HomeShortcutStore.list(this).filter { it.id() !in fixedIds }

        val total = fixed.size + custom.size + 1
        /** 行末格 = 每行第 6 格，或整表最后一项（「＋」恒为最后）；行末格右 margin 归零实现右缘贴边 */
        fun isRowEnd(i: Int) = i % perRow == perRow - 1 || i == total - 1

        // 网格 cell：徽章在上、名称在下。draggable=true 才挂 tag + 触摸监听（可拖可删）
        fun gridCell(item: HomeShortcutStore.Item, draggable: Boolean, index: Int): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // label 宽 = 徽章+gap 比 cell 宽，居中后向两侧溢出，不裁切
                clipChildren = false
                background = borderlessRipple()
                setOnClickListener { openShortcut(item) }
                if (draggable) {
                    tag = item // 拖动排序用 tag 找 view，不依赖 child 下标
                    setOnTouchListener { _, ev -> onChipTouch(this, item, ev) }
                }
            }
            val badge = ShortcutBadge.of(item)
            val badgeTv = TextView(this).apply {
                text = badge.text
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (badge.emoji) softPrimary else badge.color)
                }
                if (badge.emoji) {
                    textSize = 24f
                } else {
                    textSize = 20f
                    setTextColor(0xFFFFFFFF.toInt())
                    paint.isFakeBoldText = true
                }
            }
            cell.addView(badgeTv, LinearLayout.LayoutParams(badgeSize, badgeSize))
            val label = TextView(this).apply {
                text = item.label
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, (5 * d).toInt(), 0, 0)
            }
            cell.addView(
                label,
                LinearLayout.LayoutParams(labelW, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            val lp = android.view.ViewGroup.MarginLayoutParams(
                badgeSize,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // 行末格右 margin 归零 → 末格徽章右缘 = 行右缘（贴边）；
                // 其余格右 margin = 均分 gapH；下 margin 管换行后行与行的间距
                setMargins(0, 0, if (isRowEnd(index)) 0 else gapH, marginV)
            }
            // ⚠️⚠️ 必须显式挂 lp：addView(child) 不带 params 时走
            // ViewGroup.generateDefaultLayoutParams() = MATCH_PARENT×MATCH_PARENT，
            // 格子会被拉成整行宽（一格一行），gapH / isRowEnd / 行末归零全部失效。
            // （d7bfc16 就栽在这：lp 造了却没 attach，贴边逻辑整个死掉。）
            cell.layoutParams = lp
            return cell
        }

        var idx = 0
        fixed.forEach { shortcutRow.addView(gridCell(it, draggable = false, index = idx++)) }
        custom.forEach { shortcutRow.addView(gridCell(it, draggable = true, index = idx++)) }

        // 「＋」收尾：单独一个图标（徽章样式，无名称），点开添加面板
        val plusCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = borderlessRipple()
            setOnClickListener { showShortcutPicker() }
        }
        plusCell.addView(
            TextView(this).apply {
                text = "＋"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(softPrimary)
                }
            },
            LinearLayout.LayoutParams(badgeSize, badgeSize)
        )
        val plusLp = android.view.ViewGroup.MarginLayoutParams(
            badgeSize,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // 「＋」恒为整表最后一项 → 行末格，右 margin 归零贴右边
            setMargins(0, 0, 0, marginV)
        }
        shortcutRow.addView(plusCell, plusLp)
    }

    /**
     * 按当前主题重画兜底底色与播放按钮圆圈（这两个是 drawable，无法用 ?attr 上色，故代码生成）。
     * 渐变套：兜底底色与播放圈走 primary → primaryEnd 的斜向渐变；纯色套：primary → primaryDark。
     * 全屏沉浸版式下兜底底色是通幅的（不再有头部底部圆弧，圆弧只属于旧版式）。
     */
    private fun applyThemeColors() {
        val pack = ThemePrefs.currentPack(this)
        val end = pack.primaryEnd
        fallbackBg.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (end != null) intArrayOf(pack.primary, end) else intArrayOf(pack.primary, pack.primaryDark)
        )
        playPauseBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (end != null) {
                colors = intArrayOf(pack.primary, end)
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(pack.primary)
            }
        }
        Log.i("ThemeStyle", "pack=${pack.name} primary=${pack.primary} end=$end")
    }

    /**
     * 主题色弹窗：3 行 × 4 列网格展示全部色板。
     * 渐变套的圆点也画成渐变的，所见即所得；当前选中的格子加浅底 + 白描边 + 加粗。
     */
    private fun showThemePicker() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val curIdx = ThemePrefs.currentIndex(this)
        val onSurface = ContextCompat.getColor(this, R.color.on_surface)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var picker: AlertDialog? = null

        for (rowStart in ThemePrefs.PACKS.indices step 4) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in rowStart until minOf(rowStart + 4, ThemePrefs.PACKS.size)) {
                val p = ThemePrefs.packAt(i)
                val selected = i == curIdx
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    if (selected) {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(0x1A000000)
                        }
                    }
                    setOnClickListener {
                        ThemePrefs.set(this@MainActivity, i)
                        picker?.dismiss()
                        recreate()
                    }
                }
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (p.primaryEnd != null) {
                            colors = intArrayOf(p.primary, p.primaryEnd)
                            orientation = GradientDrawable.Orientation.TL_BR
                        } else {
                            setColor(p.primary)
                        }
                        if (selected) setStroke(dp(3), 0xFFFFFFFF.toInt())
                    }
                }
                cell.addView(dot, LinearLayout.LayoutParams(dp(46), dp(46)))
                val label = TextView(this).apply {
                    text = p.name
                    textSize = 11f
                    setTextColor(onSurface)
                    gravity = Gravity.CENTER
                    if (selected) setTypeface(typeface, Typeface.BOLD)
                }
                cell.addView(
                    label,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                )
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        picker = AlertDialog.Builder(this)
            .setTitle("选个颜色")
            .setView(root)
            .setNegativeButton("取消", null)
            .create()
        picker.show()
    }

    /**
     * 首页黄金金价卡：国际价直接取 gold-api 的 XAU price（整数显示）；
     * 国内价 = 国际价 × er-api 的 USD→CNY 汇率 ÷ 31.1035（克/盎司换算，整数显示）。
     * 卡片常驻显示：接口失败时国内/国际显示「--」，不整卡隐藏
     * （双列布局下一个卡隐藏会导致另一边落空，视觉断裂）。
     */
    private fun loadGoldPrice() {
        scope.launch {
            try {
                val intlPrice = withContext(Dispatchers.IO) {
                    JSONObject(get("https://api.gold-api.com/price/XAU")).getDouble("price")
                }
                val cnyRate = withContext(Dispatchers.IO) {
                    val rates = JSONObject(get("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates")
                    // 用户写的是 CHY，实际字段是 CNY；两者都兜底
                    if (rates.has("CNY")) rates.getDouble("CNY") else rates.getDouble("CHY")
                }
                val intl = Math.round(intlPrice).toInt()
                val dom = Math.round(intlPrice * cnyRate / 31.1035).toInt()
                goldIntlVal.text = "国际 $intl"
                goldDomVal.text = "国内 ¥$dom"
            } catch (e: Exception) {
                // API 挂了：卡片保留，数值显示「--」
                goldIntlVal.text = "国际 --"
                goldDomVal.text = "国内 --"
            }
        }
    }

    /**
     * 首页今日消费卡：读 networth_store 的 payload.flows（记账页 index.html 的 data.flows），
     * 按今天日期 + type=="支出" 求和 + 计笔数。结构：{date:"YYYY-MM-DD...", amount:Number, type:"支出"/"收入"}。
     * 线程免疫（可能来自播放器回调线程）：不切线程，本地 SharedPreferences 读取足够快。
     */
    private fun loadTodaySpend() {
        if (!::spendVal.isInitialized) return // 防御：onCreate 早期被回调时视图还没就绪
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val raw = getSharedPreferences("networth_store", MODE_PRIVATE).getString("payload", "") ?: ""
        var total = 0.0
        var cnt = 0
        try {
            val flows = org.json.JSONObject(raw).optJSONArray("flows") ?: org.json.JSONArray()
            for (i in 0 until flows.length()) {
                val f = flows.optJSONObject(i) ?: continue
                if (f.optString("type") != "支出") continue
                val d = f.optString("date", "")
                if (d.isEmpty() || !d.startsWith(today)) continue
                total += f.optDouble("amount", 0.0)
                cnt++
            }
        } catch (e: Exception) { /* JSON 坏了当没记过 */ }
        spendVal.text = if (cnt == 0) "¥0" else "¥" + money(total)
        spendCnt.text = when (cnt) {
            0 -> "今天还没记账"
            1 -> "1 笔支出"
            else -> "$cnt 笔支出"
        }
    }

    /** 金额显示：整数不带小数点，小数保留 1 位（去掉 .0 尾巴） */
    private fun money(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.1f", v)

    /**
     * 首页新闻卡：拉今日头条热榜第 1 条（最新）展示，只取第一条。
     * 主行 newsTitle = 标题，XML 限制最多 2 行（超 2 行截省略号）；
     * 副行 newsSub 已按需求去掉（热度、入口提示「今日热榜 ›」均不展示），
     * 成功时整行 GONE 让标题占满卡；仅加载失败时显示「点这里重试 ›」引导。
     * 卡片常态点击仍进 NewsActivity。
     * 与 NewsActivity 同一接口，但这里只解析第 1 条，开销最小。
     */
    private fun loadNewsHot() {
        if (!::newsTitle.isInitialized) return
        scope.launch {
            val first = try {
                withContext(Dispatchers.IO) {
                    val arr = JSONObject(
                        get("https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc")
                    ).optJSONArray("data") ?: JSONArray()
                    if (arr.length() > 0) arr.getJSONObject(0) else null
                }
            } catch (e: Exception) { null }
            if (first == null) {
                newsTitle.text = "热榜加载失败"
                newsSub.text = "点这里重试 ›"
                newsSub.visibility = View.VISIBLE
            } else {
                newsTitle.text = first.optString("Title", "")
                // 热度与「今日热榜 ›」入口提示均按需求去掉，副行隐藏让标题占满卡
                newsSub.text = ""
                newsSub.visibility = View.GONE
            }
        }
    }

    /**
     * 首页高铁日程卡：读 networth_store 的 payload.rail（高铁页落盘的数据）。
     * 主行（railCardVal，13sp 单行）= 状态（正在家 / 距上次离家 N 天 / 暂无行程）；
     * 副行（railCardSub，11sp）= 下次计划（MM-dd · 还有 M 天），无计划则隐藏。
     * 拆两行是因为半宽卡放不下整句，主副结构也跟黄金卡的「主数值+副说明」对齐，双列视觉齐平。
     * 卡片常驻（与黄金卡对成双列），不整卡隐藏。
     */
    private fun loadRailHomeStat() {
        if (!::railCard.isInitialized) return
        fun noTrip() {
            railCardVal.text = "暂无行程"
            railCardSub.visibility = View.GONE
        }
        // 高铁数据与记账共用 networth_store（payload.rail 键），见 RailStore
        val raw = RailStore.readRail(this).toString()
        if (raw.isBlank()) { noTrip(); return }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val md = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val today = cal.time
        // 每条行程的 (出发日, 返程日)；返程日可能为空（单程/正在家/计划）
        val pairs = try {
            val arr = org.json.JSONArray(
                org.json.JSONObject(raw).optJSONArray("trips").toString()
            )
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val outS = o.optString("out", "").takeIf { it.isNotBlank() }
                    ?.let { runCatching { sdf.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val backS = o.optString("back", "").takeIf { it.isNotBlank() }
                    ?.let { runCatching { sdf.parse(it) }.getOrNull() }
                outS to backS
            }
        } catch (e: Exception) { emptyList<Pair<java.util.Date, java.util.Date?>>() }
        if (pairs.isEmpty()) { noTrip(); return }
        // 最近一次已出发的行程还没回来（back 为空或返程日还没到）→ 人还在家
        val latestPast = pairs.filter { (out, _) -> !out.after(today) }.maxByOrNull { it.first }
        val latestBack = latestPast?.second
        val onTrip = latestPast != null && (latestBack == null || latestBack.after(today))
        // 已结束行程（有返程日且<=今天）里取最近一次返程日 = 距上次离家
        val lastBack = pairs.mapNotNull { (out, back) -> back?.takeIf { !out.after(today) && !it.after(today) } }
            .maxOrNull()
        val next = pairs.map { it.first }.filter { it.after(today) }.minOrNull()
        // 主行：状态一句话（单行放得下）
        railCardVal.text = when {
            onTrip -> "正在家 🏠"
            lastBack != null -> {
                val daysSince = ((today.time - lastBack.time) / 86400000L).toInt()
                if (daysSince <= 0) "今天刚走" else "距上次离家 $daysSince 天"
            }
            else -> "暂无行程"
        }
        // 副行：下次计划行程，无则隐藏
        if (next != null) {
            val d2 = ((next.time - today.time) / 86400000L).toInt()
            railCardSub.text = "下次 ${md.format(next)} · 还有 ${d2} 天"
            railCardSub.visibility = View.VISIBLE
        } else {
            railCardSub.visibility = View.GONE
        }
    }

    // ==================== 每日一图（全屏沉浸背景） ====================

    /**
     * 手动换一张背景图（每日一图）：顶栏「切背景图」按钮与点问候行共用同一套逻辑。
     *  - 还没配图片文件夹 → 直接弹系统选择器让用户选图片文件夹（不然点了没反应）
     *  - 已配好 → 切下一张并提示；0 点后自动恢复按日轮换（nextManual 只影响当天显示）
     */
    private fun switchDailyImage() {
        if (DailyImageStore.imageCount(this) == 0) {
            dailyFolderLauncher.launch(null)
            return
        }
        if (DailyImageStore.nextManual(this) != null) {
            loadDailyImage()
            Toast.makeText(this, "已换一张，0 点后恢复自动轮换", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 载入今天该显示的每日一图：
     *  - 文件夹没配/没图/图坏了 → 隐藏 dailyImage，露出主题渐变兜底底色
     *  - 有图 → 按屏幕分辨率动态采样（屏幕最长边×2，封顶4000）铺满全屏，1:1 清晰不爆内存；蒙层改为两端渐变压暗、中部通透
     * 手动切过图时 DailyImageStore 会记住当天的手动序号，跨天自动失效。
     */
    private fun loadDailyImage() {
        if (!::dailyImage.isInitialized) return
        greetingText.text = greetingByTime()
        // 日期/星期/农历：无论有没有设每日一图都先写上（没图时不再回退成「未设置…」提示）
        updateDateText()
        val imgs = runCatching { DailyImageStore.listImages(this) }.getOrElse { emptyList() }
        if (imgs.isEmpty()) {
            dailyImage.setImageDrawable(null)
            dailyImage.visibility = View.GONE
            return
        }
        val idx = DailyImageStore.todayIndex(this).coerceAtLeast(0)
        val uri = imgs[idx]
        scope.launch {
            // 之前锁死 1600 + 2 的幂跳变，常只解到 ~1000 宽，全屏放大必糊。
            // 改为按屏幕实际分辨率：最长边×2 封顶 4000，解码尺寸贴着屏幕跑 → 基本 1:1 清晰，
            // 又不至于超大图（8000×6000）OOM。全屏 ImageView 按 1 bitmap px = 1 screen px 绘制，无需乘 density。
            val dm = resources.displayMetrics
            val screenLong = Math.max(dm.widthPixels, dm.heightPixels)
            val target = Math.min(screenLong * 2, 4000)
            val bmp = withContext(Dispatchers.IO) { ImagePrep.decodeSampled(this@MainActivity, uri, target) }
            if (bmp != null) {
                dailyImage.setImageBitmap(bmp)
                dailyImage.visibility = View.VISIBLE
            } else {
                // 单张图解码失败：退兜底底色，别留一张黑的
                dailyImage.setImageDrawable(null)
                dailyImage.visibility = View.GONE
            }
        }
    }

    /**
     * 刷新问候行下方的日期/农历：M月D日 周X · 农历…（不显示时间）。
     * 与每日一图解耦——没设图片文件夹也照常显示当前日期农历。
     */
    private fun updateDateText() {
        val cal = java.util.Calendar.getInstance()
        val week = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            .getOrElse(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1) { "" }
        val lunar = LunarUtil.text(cal)   // 农历九月十四；表外日期（1900前/2099后）为空串
        val lunarPart = if (lunar.isNotBlank()) " · $lunar" else ""
        dateText.text = "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 $week$lunarPart"
    }

    /** 时段问候：早上好 / 中午好 / 下午好 / 晚上好 / 夜深了 */
    private fun greetingByTime(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            in 18..23 -> "晚上好"
            else -> "夜深了"
        }
    }

    // ==================== 首页自定义入口：拖动排序 ====================

    /**
     * 自定义入口 chip 的手势（「拿起-放下」方案）：
     *  - 轻点 → 打开
     *  - 按住约 300ms → 抓起（放大 + 震动 + 置顶），此时移动 = 进入拖动排序：
     *    被拖 chip 跟手 + 落点槽位高亮预览，**拖动中不 remove/add 任何 view**，
     *    所以手势不会断、页面不会跳、松手落位和预览完全一致
     *  - 抓起后直接松手（没移动过）→ 删除
     *
     * 历史教训（已废弃的实时换序实现）：removeViewAt/addView 换序时，
     * ① 被拖 chip 的实际下标不是 t（右拖 +1、左拖 -1），dragIndex 记错后续全乱；
     * ② 视图操作与 swap(dragOrder) 语义不同，拖几次后视图序≠数据序，松手重建当场跳变；
     * ③ 第二次交换时 other 恰是被拖 chip 自己，remove 它直接触发 ACTION_CANCEL 手势断裂。
     */
    private fun onChipTouch(chip: View, item: HomeShortcutStore.Item, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dragChip != null) return true // 防御：同屏只拖一个
                downRawX = ev.rawX; downRawY = ev.rawY
                dragChip = chip
                dragOrder = HomeShortcutStore.list(this)
                // Item 非 data class（无 equals），list() 每次解析出新实例，必须按 id() 比
                dragIndex = dragOrder.indexOfFirst { it.id() == item.id() }
                hitHold = dragIndex
                shortcutRow.getLocationOnScreen(flowLoc)
                grabReady = false; dragging = false
                grabHandler.removeCallbacks(grabRun)
                grabHandler.postDelayed(grabRun, grabDelayMs)
                Log.i("ChipDrag", "down target=${item.target} dragIndex=$dragIndex")
                return false // 先交还：也可能只是普通点击
            }
            MotionEvent.ACTION_MOVE -> {
                val chipView = dragChip ?: return false
                if (!grabReady) {
                    // 还没抓起：小幅移动=想点开/页面在滚动，交还手势；
                    // 大幅移动=用户没想抓，取消抓起判定（防误触）
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (abs(dx) > chipTouchSlop || abs(dy) > chipTouchSlop) {
                        grabHandler.removeCallbacks(grabRun)
                    }
                    return false
                }
                if (!dragging) {
                    // 抓起后必须真的移过 slop 才算拖动：长按松手时手指的 1~3px 抖动
                    // 不该被当成「空拖动」，否则删除框永远弹不出来
                    val ddx = ev.rawX - downRawX
                    val ddy = ev.rawY - downRawY
                    if (abs(ddx) <= chipTouchSlop && abs(ddy) <= chipTouchSlop) return true
                    dragging = true
                    Log.i("ChipDrag", "drag-start target=${item.target}")
                }
                // 跟手：平移量 = 手指位移，并钳制在 FlowLayout 边界内（防拖出屏幕被裁）
                applyDragTranslation(chipView, ev.rawX, ev.rawY)
                // 落点预览：命中哪个槽位就高亮哪个（不真的移动任何 view）
                val t = hitSlot(ev.rawX - flowLoc[0], ev.rawY - flowLoc[1])
                if (t != hitHold) {
                    Log.i("ChipDrag", "slot-change from=$hitHold to=$t")
                    hitHold = t
                    highlightSlot(t)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                grabHandler.removeCallbacks(grabRun)
                val wasDragging = dragging
                val wasGrab = grabReady
                dragging = false; grabReady = false; dragChip = null
                when {
                    wasDragging -> {
                        commitDragOrder()
                        renderShortcuts() // 重建一行，清掉拖动位移/缩放/高亮
                    }
                    wasGrab -> {
                        renderShortcuts() // 复位抓起动画
                        showRemoveShortcutDialog(item)
                    }
                }
                return wasDragging || wasGrab
            }
            MotionEvent.ACTION_CANCEL -> {
                // 被系统打断（来电/通知等）：已经拖出来的顺序照常保存，别让用户白拖
                grabHandler.removeCallbacks(grabRun)
                val wasDragging = dragging
                dragging = false; grabReady = false; dragChip = null
                if (wasDragging) {
                    commitDragOrder()
                    renderShortcuts()
                }
                return wasDragging
            }
        }
        return false
    }

    /** 一次性落位：把被拖 chip 从 dragIndex 挪到 hitHold 并持久化（dragOrder 在拖动中保持不变，松口才改） */
    private fun commitDragOrder() {
        if (hitHold in 0..dragOrder.size && hitHold != dragIndex && dragIndex in dragOrder.indices) {
            dragOrder.add(hitHold, dragOrder.removeAt(dragIndex))
            HomeShortcutStore.replaceAll(this, dragOrder)
            Log.i("ChipDrag", "commit from=$dragIndex to=$hitHold order=${dragOrder.joinToString(",") { it.target }}")
        } else {
            Log.i("ChipDrag", "commit-skip hitHold=$hitHold dragIndex=$dragIndex")
        }
    }

    /**
     * 被拖 chip 跟手，并把芯片屏幕位置钳制在 FlowLayout 边界 ± 余量内。
     * clipChildren=false 只是不裁，视觉上飞出屏幕更怪，所以跟手范围必须有界。
     */
    private fun applyDragTranslation(chip: View, rawX: Float, rawY: Float) {
        val density = resources.displayMetrics.density
        val overY = 48 * density // 上下留余量：允许略微拖出行间
        val overX = 8 * density
        val baseX = flowLoc[0] + chip.left
        val baseY = flowLoc[1] + chip.top
        var tx = rawX - baseX
        var ty = rawY - baseY
        val left = baseX + tx
        val top = baseY + ty
        val right = left + chip.width
        val bottom = top + chip.height
        val minX = flowLoc[0] - overX
        val maxX = flowLoc[0] + shortcutRow.width + overX - chip.width
        val minY = flowLoc[1] - overY
        val maxY = flowLoc[1] + shortcutRow.height + overY - chip.height
        // 只补超出的那一侧
        tx += (minX - left).coerceAtLeast(0f) + (maxX - right).coerceAtMost(0f)
        ty += (minY - top).coerceAtLeast(0f) + (maxY - bottom).coerceAtMost(0f)
        chip.translationX = tx
        chip.translationY = ty
    }

    /** 拖动中高亮落点槽位（缩小 + 半透明预览），其余恢复 */
    private fun highlightSlot(t: Int) {
        val target = dragOrder.getOrNull(t)?.let { findChipByTag(it) }
        for (i in 0 until shortcutRow.childCount) {
            val c = shortcutRow.getChildAt(i)
            if (c == dragChip) continue
            val on = c == target && t != dragIndex
            c.animate().cancel()
            c.animate()
                .scaleX(if (on) 0.92f else 1f)
                .scaleY(if (on) 0.92f else 1f)
                .alpha(if (on) 0.55f else 1f)
                .setDuration(120)
                .start()
        }
    }

    private fun findChipByTag(item: HomeShortcutStore.Item): View? {
        for (i in 0 until shortcutRow.childCount) {
            val c = shortcutRow.getChildAt(i)
            // tag 里是 renderShortcuts 时新建的实例，与手势里的 item 不是同一引用，按 id() 比
            if ((c.tag as? HomeShortcutStore.Item)?.id() == item.id()) return c
        }
        return null
    }

    /** 命中测试：FlowLayout 坐标 (x,y) 落在哪个 chip 上（边界外扩 14dp，更容易落位），返回 dragOrder 里的位置（-1=没命中） */
    private fun childIndexAt(x: Float, y: Float): Int {
        val infl = 14 * resources.displayMetrics.density
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until shortcutRow.childCount) {
            val c = shortcutRow.getChildAt(i)
            if (c == dragChip) continue // 跳过被拖 chip：它在手指下，不该当落点
            if (x < c.left - infl || x > c.right + infl ||
                y < c.top - infl || y > c.bottom + infl
            ) continue
            val cx = (c.left + c.right) / 2f
            val cy = (c.top + c.bottom) / 2f
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return -1
        // child 下标 → dragOrder 位置（bringToFront 会打乱 child 顺序，故按 tag/id 换算）
        val item = shortcutRow.getChildAt(best).tag as? HomeShortcutStore.Item ?: return -1
        return dragOrder.indexOfFirst { it.id() == item.id() }
    }

    /**
     * 命中槽位（带迟滞）：候选槽位必须比当前已确认槽位（hitHold）明显更近（≥10dp）才切换。
     * 手指恰停在两个 chip 中点附近时，最近中心会在两者间反复翻转，有了迟滞就不会闪。
     */
    private fun hitSlot(x: Float, y: Float): Int {
        val best = childIndexAt(x, y)
        if (best < 0 || best == hitHold) return hitHold
        val holdItem = dragOrder.getOrNull(hitHold)
        val bestItem = dragOrder.getOrNull(best) ?: return hitHold
        if (holdItem != null && holdItem != bestItem) {
            val holdView = findChipByTag(holdItem)
            val bestView = findChipByTag(bestItem)
            if (holdView != null && bestView != null &&
                distTo(bestView, x, y) + 10 * resources.displayMetrics.density >= distTo(holdView, x, y)
            ) return hitHold
        }
        return best
    }

    private fun distTo(v: View, x: Float, y: Float): Float {
        val dx = x - (v.left + v.right) / 2f
        val dy = y - (v.top + v.bottom) / 2f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 长按（按住不动）删除自定义入口 */
    private fun showRemoveShortcutDialog(item: HomeShortcutStore.Item) {
        AlertDialog.Builder(this)
            .setTitle("移除「${item.label}」？")
            .setPositiveButton("移除") { _, _ ->
                HomeShortcutStore.remove(this, item.id())
                renderShortcuts()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点自定义资源位：link=直接开链接，page=进对应功能/记账子页（与悬浮按钮共用 ShortcutRouter） */
    private fun openShortcut(item: HomeShortcutStore.Item) = ShortcutRouter.open(this, item)

    /**
     * 「＋」添加面板：App 内功能 + 自定义链接（名称+网址，走 LinkRouter.normalize，
     * 小红书分享链/深链/网页/app:// 伪协议都能自动识别，点击时由 ShortcutRouter 分发）
     */
    private fun showShortcutPicker() {
        // 固定入口同 id 的（影视仓/记账本/下载/收藏）不再进面板：网格前 4 格已有，加了也会被渲染过滤掉
        val catalog = listOf(
            HomeShortcutStore.Item("page", "add", "记一笔", "✏️"),
            HomeShortcutStore.Item("page", "flow", "收支查询", "📊"),
            HomeShortcutStore.Item("page", "journal", "日志", "📖"),
            HomeShortcutStore.Item("page", "links", "链接收藏", "🔗"),
            HomeShortcutStore.Item("page", "rail", "高铁往返", "🚄"),
            HomeShortcutStore.Item("page", "reminder", "提醒", "⏰"),
            HomeShortcutStore.Item("page", "settings", "设置", "⚙")
        ).filter { it.id() !in fixedShortcuts.map { f -> f.id() } }
        val have = HomeShortcutStore.list(this).map { it.id() }.toSet()
        val names = catalog.map {
            (if (it.id() in have) "✓ " else "") + it.emoji + " " + it.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("添加到首页")
            .setItems(names) { _, i ->
                val it = catalog[i]
                if (HomeShortcutStore.add(this, it)) {
                    renderShortcuts()
                    Toast.makeText(this, "已添加，长按可拖动排序，按住不动可删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "已经在首页了", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("🔗 自定义链接") { _, _ -> showAddLinkDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 添加自定义链接快捷位：名称 + 网址。同步写入链接页（LinksStore），
     *  target 用链接页的链接 id——链接页后续改标题/网址按这个 id 反同步首页 */
    private fun showAddLinkDialog() {
        val nameInput = EditText(this).apply {
            hint = "名称，如：摸鱼日报"
            setSingleLine()
        }
        val urlInput = EditText(this).apply {
            hint = "网址，如 https://…（小红书/深链/app:// 均可）"
            setSingleLine()
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义链接")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val label = nameInput.text.toString().trim().ifBlank { "链接" }
                val raw = urlInput.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(this, "网址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // normalize 只做全角转半角；用户常直接粘裸域名（baidu.com），
                // 没协议头存进去 WebView 加载会失败，这里补 https://
                val url = LinkRouter.normalize(raw).let { n ->
                    if (n.contains("://")) n else "https://$n"
                }
                // 双写：① 链接页数据（同网址已存在则复用其 id，不重复加）
                //      ② 首页快捷位（target=链接 id，供链接页编辑时反同步）
                val linkId = LinksStore.addOrGet(this, label, url)
                val item = HomeShortcutStore.Item("link", linkId, label, "🔗", url)
                if (HomeShortcutStore.add(this, item)) {
                    renderShortcuts()
                    Toast.makeText(this, "已添加「$label」，长按可拖动排序", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "这个链接已经在首页了", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("返回") { _, _ -> showShortcutPicker() }
            .setOnCancelListener { showShortcutPicker() }
            .show()
    }

    /**
     * 已废弃：队列 pill 改为纯 🎵 图标（用户要求不显示歌曲数），无动态内容可刷新。
     * 保留函数与全部调用点不动，避免逐处删调用漏改；pill 点击进 QueueActivity 看完整队列。
     */
    private fun refreshQueueEntry(){
        // no-op：pill 文本固定为 🎵
    }

    /** 三场景续听 chips：按各场景是否还有可续听内容显隐对应 chip */
    private fun refreshSceneResume(){
        if (!::sceneResumeRow.isInitialized) return
        val showMusic = PlayerManager.sceneHasContent(Scene.MUSIC)
        val showPod = PlayerManager.sceneHasContent(Scene.PODCAST)
        val showRadio = PlayerManager.sceneHasContent(Scene.RADIO)
        chipResumeMusic.visibility = if (showMusic) View.VISIBLE else View.GONE
        chipResumePodcast.visibility = if (showPod) View.VISIBLE else View.GONE
        chipResumeRadio.visibility = if (showRadio) View.VISIBLE else View.GONE
        sceneResumeRow.visibility = if (showMusic || showPod || showRadio) View.VISIBLE else View.GONE
    }

    private fun applyPlayMode() {
        val m = PlayerManager.playMode
        modeBtn.setImageResource(when (m) { 1 -> R.drawable.ic_repeat; 2 -> R.drawable.ic_shuffle; else -> R.drawable.ic_sequence })
        modeBtn.contentDescription = when (m) { 1 -> "列表循环"; 2 -> "随机播放"; else -> "顺序播放" }
    }

    /** 去重键必须带 source：否则不同音源的"同名歌"会被合并，只留先查到的 netease */
    private fun dedupKey(x:Song)=x.source.lowercase()+"|"+x.name.lowercase()+"|"+x.artist.lowercase()

    private fun search(){
        val kw=input.text.toString().trim(); if(kw.isEmpty())return
        lastKeyword=kw; currentPage=1; hasMore=true; isLoadingMore=false
        status.text="正在搜索……"
        scope.launch{
            val failed=mutableListOf<String>()
            val results=withContext(Dispatchers.IO){ fetchPage(kw,1,failed) }
                .distinctBy{ dedupKey(it) }
            adapter.submit(results)
            status.text = buildStatus(results.size, failed)
        }
    }

    /** 滚动到底部自动加载下一页 */
    private fun loadMore(){
        if(isLoadingMore || !hasMore || lastKeyword.isBlank()) return
        isLoadingMore=true
        val next=currentPage+1
        status.text="正在加载第 $next 页……"
        scope.launch{
            val failed=mutableListOf<String>()
            val page=withContext(Dispatchers.IO){ fetchPage(lastKeyword,next,failed) }
            val seen=adapter.snapshot().map{ dedupKey(it) }.toHashSet()
            val fresh=page.distinctBy{ dedupKey(it) }.filter{ dedupKey(it) !in seen }
            adapter.append(fresh)
            currentPage=next
            hasMore = page.isNotEmpty() && fresh.isNotEmpty()
            isLoadingMore=false
            status.text = if(hasMore) "已加载 ${adapter.snapshot().size} 首 · 继续下滑加载更多"
            else "已加载全部 ${adapter.snapshot().size} 首"
        }
    }

    private fun buildStatus(count:Int, failed:List<String>):String{
        val base = if(failed.isEmpty()) "找到 $count 首歌曲"
                   else "找到 $count 首（${failed.joinToString("、"){ sourceLabel(it) }} 暂不可用）"
        return if(count>0 && hasMore) "$base · 下滑可加载更多" else base
    }

    private fun fetchPage(kw:String,page:Int,failed:MutableList<String>):List<Song>{
        return selectedSources.flatMap{ s->
            runCatching{ searchSource(kw,s,page) }
                .onFailure{ failed += s }
                .getOrDefault(emptyList())
        }
    }

    private fun searchSource(kw:String,source:String,page:Int=1):List<Song>{
        val u="$api?types=search&source=$source&name=${enc(kw)}&count=$pageSize&pages=$page"
        val a=JSONArray(get(u)); val out=mutableListOf<Song>()
        for(i in 0 until a.length()){
            val o=a.getJSONObject(i); val ar=o.optJSONArray("artist")
            val artist=if(ar!=null)(0 until ar.length()).joinToString(" / "){ar.optString(it)}
            else o.optString("artist")
            out+=Song(o.optString("id"),o.optString("name"),artist,o.optString("album"),
                o.optString("source",source),o.optString("pic_id"),o.optString("lyric_id",o.optString("id")))
        }
        return out
    }

    // ---- 收藏（多歌单） ----
    /** ⭐ 点击：单歌单直接 toggle；多歌单弹选择框 */
    private fun onStarClick(s:Song){
        val pls=PlaylistStore.getPlaylists(this)
        if(pls.size<=1){
            if(PlaylistStore.isInPlaylist(this, PlaylistStore.DEFAULT_ID, s))
                PlaylistStore.removeSong(this, PlaylistStore.DEFAULT_ID, s)
            else PlaylistStore.addSong(this, PlaylistStore.DEFAULT_ID, s)
            refreshStar(s)
        } else {
            showPlaylistPicker(s)
        }
    }

    private fun refreshStar(s:Song){
        adapter.notifyDataSetChanged()
    }

    private fun showPlaylistPicker(s:Song){
        val pls=PlaylistStore.getPlaylists(this)
        val names=pls.map{ it.name }.toTypedArray()
        val checked=pls.map{ PlaylistStore.isInPlaylist(this, it.id, s) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("收藏到歌单")
            .setMultiChoiceItems(names, checked){ _, which, isChecked ->
                val p=pls[which]
                if(isChecked) PlaylistStore.addSong(this, p.id, s) else PlaylistStore.removeSong(this, p.id, s)
            }
            .setPositiveButton("完成"){ d,_ -> d.dismiss(); refreshStar(s) }
            .setNeutralButton("＋新建"){ _,_ -> newPlaylistThenAdd(s) }
            .show()
    }

    private fun newPlaylistThenAdd(s:Song){
        val edit=EditText(this); edit.hint="歌单名称"
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(edit)
            .setPositiveButton("创建"){ _,_ ->
                val id=PlaylistStore.createPlaylist(this, edit.text.toString())
                PlaylistStore.addSong(this, id, s)
                refreshStar(s)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isFavorite(s:Song)=PlaylistStore.isFavoritedAnywhere(this, s)

    private fun saveHistory(s:Song){
        val old=prefs.getStringSet("history",emptySet())!!.toMutableList()
        old.remove(s.key()); old.add(0,s.key()); if(old.size>50)old.removeAt(old.lastIndex)
        prefs.edit().putStringSet("history",old.toSet()).apply()
    }

    // ---- 离线下载 ----
    private fun baseName(s:Song)=LyricFiles.baseName(s)
    private fun localUri(s:Song):Uri? = DownloadStore.localUri(this, s)
    private fun isDownloaded(s:Song)=localUri(s)!=null

    private fun requestManualDownload(s:Song){
        val k=s.key()
        if(isDownloaded(s)){ setDl(k,"done"); return }
        pendingDownload=s
        pickDirLauncher.launch(null)
    }

    /** 手动下载：把音频写到用户选的 SAF 目录里。注意：本方法在 Dispatchers.IO 上跑，
     *  所有碰 UI 的操作（adapter.notifyDataSetChanged）必须经 setDl 切回主线程，
     *  否则 RecyclerView 在子线程 requestLayout 直接崩（CalledFromWrongThreadException） */
    private fun downloadToTree(s:Song, treeUri:Uri){
        val k=s.key()
        setDl(k,"downloading")
        try{
            val o=JSONObject(get("$api?types=url&source=${enc(s.source)}&id=${enc(s.id)}&br=320"))
            val url=o.optString("url")
            if(url.isBlank()||url=="null"){ setDl(k,"error"); return }
            val resp=client.newCall(Request.Builder().url(url).header("User-Agent","GDMusicPlayer/3.0").build()).execute()
            if(!resp.isSuccessful){ resp.close(); setDl(k,"error"); return }
            val ct=resp.header("Content-Type")
            val ext=extFrom(url,ct)
            val mime=mimeFromExt(ext)
            val tree=DocumentFile.fromTreeUri(this, treeUri)
            val folderName=tree?.name ?: "音乐"
            val safeName="${s.name}-${s.artist}".replace(Regex("[\\\\/:*?\"<>|]"),"_").take(80)
            val doc=tree?.createFile(mime, "$safeName.$ext")
            if(doc==null){ setDl(k,"error"); return }
            val os=contentResolver.openOutputStream(doc.uri)
            if(os==null){ setDl(k,"error"); return }
            resp.body?.byteStream()?.use{ inp-> os.use{ inp.copyTo(it) } }
            resp.close()
            val size=doc.length()
            // 歌词随音频一并下载到同一目录（失败不影响音频；纯离线时靠它显示歌词）
            val lrc=if(s.source.isNotBlank() && s.lyricId.isNotBlank()) runCatching{
                val lo=JSONObject(get("$api?types=lyric&source=${enc(s.source)}&id=${enc(s.lyricId)}"))
                lo.optString("lyric").takeIf{ it.isNotBlank() }
            }.getOrNull() else null
            val lrcUri=if(lrc!=null && tree!=null) LyricFiles.writeToTree(this, tree, safeName, lrc) else null
            DownloadStore.add(this, s, doc.uri, "manual", folderName, size, lrcUri ?: "")
            setDl(k,"done")
        }catch(e:Exception){ setDl(k,"error") }
    }

    /** 下载状态刷新。做成线程免疫：不管谁在什么线程调，都保证切到主线程再碰 RecyclerView，
     *  杜绝 CalledFromWrongThreadException（downloadToTree 跑在 Dispatchers.IO 上） */
    private fun setDl(k:String, st:String){
        if(Looper.myLooper()==Looper.getMainLooper()){
            dlStates[k]=st; adapter.notifyDataSetChanged()
        }else{
            uiHandler.post{ dlStates[k]=st; adapter.notifyDataSetChanged() }
        }
    }

    private fun mimeFromExt(ext:String)=when(ext.lowercase()){
        "mp3"->"audio/mpeg"; "m4a"->"audio/mp4"; "flac"->"audio/flac"
        "wav"->"audio/wav"; "ogg"->"audio/ogg"; "opus"->"audio/ogg"
        "aac"->"audio/aac"; else->"audio/mpeg"
    }

    private fun extFrom(url:String, ct:String?):String{
        val p=url.substringBefore('?').substringAfterLast('.',"")
        if(p.length in 2..4) return p.lowercase()
        return when{
            ct?.contains("mpeg")==true || ct?.contains("mp3")==true -> "mp3"
            ct?.contains("mp4")==true || ct?.contains("m4a")==true -> "m4a"
            ct?.contains("flac")==true -> "flac"
            ct?.contains("wav")==true -> "wav"
            ct?.contains("ogg")==true || ct?.contains("opus")==true -> "ogg"
            else -> "mp3"
        }
    }

    private fun get(u:String):String{
        val r=Request.Builder().url(u).header("User-Agent","GDMusicPlayer/3.0").build()
        return client.newCall(r).execute().use{if(!it.isSuccessful)throw Exception("HTTP ${it.code}");it.body?.string()?:""}
    }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        handlePlayIntent()
        // QueueActivity 空列表的「去搜索」跳回来时带 open_tab=search，直接切到搜索页签
        if(intent.getStringExtra("open_tab")=="search") switchTab(true)
    }

    /**
     * 统一处理外部（收藏页/下载页）传入的播放意图。
     * 旧代码分两个函数且都读 play_queue_json，会被连续执行两次导致重复起播，现合并。
     * 优先级：队列播放 > 单首本地 > 单首在线。
     */
    private fun handlePlayIntent(){
        // 1) 队列播放（下载页/收藏页的「播放全部」和「点单首」都走这支，idx 区分起点）
        val queueJson=intent.getStringExtra("play_queue_json")
        if(queueJson!=null){
            val list=songsFromJsonArray(queueJson)
            val idx=intent.getIntExtra("play_index",0).coerceIn(0, maxOf(0, list.size-1))
            if(list.isNotEmpty()){
                PlayerManager.playInternal(list[idx], list, idx, Scene.MUSIC)
                // 显式刷新一次入口卡计数：不依赖 onSongStart 回调（起播失败时回调可能不来）
                refreshQueueEntry()
            }
            return
        }
        // 2) 单首本地播放（uri 直传）
        val uriStr=intent.getStringExtra("play_local_uri")
        if(uriStr!=null){
            val json=intent.getStringExtra("play_local_json")
            val song=if(json!=null) runCatching{
                val o=JSONObject(json)
                Song(o.optString("id"),o.optString("name"),o.optString("artist"),
                    o.optString("album"),o.optString("source"),o.optString("picId"),
                    o.optString("lyricId",o.optString("id")),o.optString("localUri").ifBlank{uriStr})
            }.getOrDefault(Song("","","","","","","",uriStr)) else Song("","","","","","","",uriStr)
            PlayerManager.playInternal(song, listOf(song), 0, Scene.MUSIC)
            return
        }
        // 3) 单首在线播放（临时播放：不动播放列表）
        val json=intent.getStringExtra("play_song_json") ?: return
        runCatching{
            val o=JSONObject(json)
            val s=Song(o.optString("id"),o.optString("name"),o.optString("artist"),
                o.optString("album"),o.optString("source"),o.optString("picId"),
                o.optString("lyricId",o.optString("id")))
            PlayerManager.playTemp(s, Scene.MUSIC)
        }
    }

    private val progressRunnable=object:Runnable{
        override fun run(){ updateProgress(); uiHandler.postDelayed(this,500) }
    }
    private fun updateProgress(){
        // 歌曲进度条已从播放条去掉，这里只维护播放/暂停图标与桌面歌词进度
        playPauseBtn.setImageResource(if(player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        DesktopLyric.onProgress(player.currentPosition) // 桌面歌词按进度滚动到当前行
    }

    /** 把用户填写的接口地址归一化：去掉首尾空白与末尾斜杠，确保以 /api.php 结尾 */
    private fun normalizeApi(raw:String):String{
        var s=raw.trim()
        if(s.isBlank()) s=SettingsActivity.DEFAULT_API
        while(s.endsWith("/")) s=s.substring(0, s.length-1)
        if(!s.endsWith("/api.php", ignoreCase=true)) s+="/api.php"
        return s
    }

    override fun onDestroy(){
        // 不再释放播放器：播放由前台服务保活，息屏/退出首页都不会中断；仅断开 UI 监听
        uiHandler.removeCallbacks(progressRunnable)
        scope.cancel()
        // 仅当槽里还是自己才清：本页在后台被回收时，前台可能是 QueueActivity（它持有监听），
        // 无条件置空会把队列页的刷新也打断
        if(PlayerManager.uiListener === this) PlayerManager.uiListener = null
        super.onDestroy()
    }

    // ===== PlayerUiListener：播放管理器把状态推给这里，刷新底部播放条 =====
    override fun onSongStart(song: Song, isLocal: Boolean, statusText: String) {
        syncPlayerBar()
        nowPlaying.text = song.name
        nowArtist.text = "${song.artist} · ${song.album}"
        status.text = statusText
        // 关键：播放条换了歌，入口卡的「正在播放」计数必须同步刷新，
        // 否则自动切歌/点歌后计数停在旧值（完整队列的高亮在 QueueActivity 里刷新）
        refreshQueueEntry()
    }
    // 正在播放卡封面统一用默认音符图，不接在线封面回调
    override fun onCover(bitmap: Bitmap?) {}
    override fun onLyrics(text: String) { DesktopLyric.setLyrics(text) }
    override fun onStatus(text: String) { status.text = text }
    override fun onPlayStateChanged(isPlaying: Boolean) {
        playPauseBtn.setImageResource(if(isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }
    override fun onModeChanged(mode: Int) { applyPlayMode() }
    override fun onCacheStateChanged(key: String, state: String) {
        dlStates[key] = state; adapter.notifyDataSetChanged()
    }
    override fun onQueueChanged() { refreshQueueEntry(); refreshSceneResume() }
}

/** 歌单/下载页把歌曲列表传给 MainActivity 的公共序列化（同包可见） */
fun songsToJsonArray(list: List<Song>): String {
    val a=JSONArray(); list.forEach { a.put(FavoritesStore.songToJson(it)) }; return a.toString()
}
fun songsFromJsonArray(str: String?): List<Song> {
    if(str.isNullOrBlank()) return emptyList()
    val a=JSONArray(str); val out=mutableListOf<Song>()
    for(i in 0 until a.length()) FavoritesStore.fromJsonStr(a.optString(i))?.let { out.add(it) }
    return out
}

class SongAdapter(
    private val onPlay:(Song)->Unit,
    private val onFavorite:(Song)->Unit,
    private val isFavorite:(Song)->Boolean,
    private val onDownload:(Song)->Unit,
    private val dlState:(Song)->String,
    private val onInsert:(Song)->Unit
):RecyclerView.Adapter<SongAdapter.VH>(){
    private val data=mutableListOf<Song>()
    fun submit(l:List<Song>){data.clear();data.addAll(l);notifyDataSetChanged()}
    fun append(l:List<Song>){
        if(l.isEmpty())return
        val start=data.size; data.addAll(l); notifyItemRangeInserted(start,l.size)
    }
    fun snapshot():List<Song> = data.toList()
    override fun getItemCount()=data.size
    override fun onCreateViewHolder(p:ViewGroup,v:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_song,p,false))
    override fun onBindViewHolder(h:VH,p:Int)=h.bind(data[p])
    inner class VH(v:View):RecyclerView.ViewHolder(v){
        private val content=v.findViewById<View>(R.id.contentLayout)
        private val n=v.findViewById<TextView>(R.id.songName)
        private val a=v.findViewById<TextView>(R.id.songArtist)
        private val s=v.findViewById<TextView>(R.id.songSource)
        private val dl=v.findViewById<TextView>(R.id.dlBtn)
        private val fav=v.findViewById<TextView>(R.id.favBtn)
        private val ins=v.findViewById<TextView>(R.id.insertBtn)
        fun bind(x:Song){
            n.text=x.name
            a.text="${x.artist} · ${x.album}"
            s.text=sourceLabel(x.source)
            val on=isFavorite(x)
            fav.text=if(on)"★" else "☆"
            fav.setTextColor(ContextCompat.getColor(fav.context, if(on) R.color.star_on else R.color.star_off))
            val st=dlState(x)
            dl.text=when(st){"downloading"->"⏳";"done"->"✓";"error"->"⚠";else->"⬇"}
            dl.setTextColor(ContextCompat.getColor(dl.context, when(st){
                "done"->R.color.download_done
                "error"->R.color.download_err
                "downloading"->R.color.primary
                else->R.color.star_off
            }))
            content.setOnClickListener{ onPlay(x) }
            fav.setOnClickListener{ onFavorite(x) }
            ins.setOnClickListener{ onInsert(x) }
            dl.setOnClickListener{ if(dlState(x)!="done" && dlState(x)!="downloading") onDownload(x) }
        }
    }
}

/** 播放列表适配器：行点击=切到该首播放，右侧 ✕=从队列移除；当前播放项高亮 */
class QueueAdapter(
    private val onPlay:(Int, Song)->Unit,
    private val onRemove:(Int, Song)->Unit
):RecyclerView.Adapter<QueueAdapter.VH>(){
    private val data=mutableListOf<Song>()
    fun submit(l:List<Song>){data.clear();data.addAll(l);notifyDataSetChanged()}
    override fun getItemCount()=data.size
    override fun onCreateViewHolder(p:ViewGroup,v:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_queue_song,p,false))
    override fun onBindViewHolder(h:VH,p:Int)=h.bind(data[p],p)
    inner class VH(private val root:View):RecyclerView.ViewHolder(root){
        private val idx=root.findViewById<TextView>(R.id.qIndex)
        private val n=root.findViewById<TextView>(R.id.qName)
        private val a=root.findViewById<TextView>(R.id.qArtist)
        private val playing=root.findViewById<TextView>(R.id.qPlaying)
        private val rm=root.findViewById<TextView>(R.id.qRemove)
        // ⚠️ 行点击必须挂内部 itemRoot，不能挂外层 root（MaterialCardView）：
        // itemRoot 是 clickable 且铺满卡片，挂外层监听永远收不到点击（同 16f8037 信息卡的坑），
        // 症状就是「队列里点歌没反应、以为不能播放」
        private val row=root.findViewById<View>(R.id.itemRoot)
        // 主题属性取色：高亮=当前主题主色（12 套主题都正确），常规=浅色中性字色。
        // ⚠️ 不能用 R.color.primary（静态橙，非默认主题下高亮跑色）；
        // ⚠️ 也不能再用 on_dark/on_dark_dim（首页深色玻璃卡的白字系，白卡上等于隐形）
        private val cPrimary=ThemePrefs.currentPack(root.context).primary
        private val cName=ContextCompat.getColor(root.context, R.color.on_surface)
        private val cDim=ContextCompat.getColor(root.context, R.color.on_surface_variant)
        fun bind(x:Song, position:Int){
            // 高亮「正在播放」：优先对象 identity（队列里就是 current 那个对象），
            // 再退按键匹配。不能只按键——同一首歌在队列里出现两次时按键会撞车，
            // 高亮落到第一首上，而实际播的是第二首（用户看到「显示在播 A、实际响 B」）。
            // 临时播放期间不高亮任何一项：响的是队列外的歌，列表原样没动。
            val cur=PlayerManager.current
            val isCur=!PlayerManager.tempPlaying && cur!=null && (cur===x || cur.key()==x.key())
            idx.text=(position+1).toString()
            idx.setTextColor(if(isCur) cPrimary else cDim)
            n.text=x.name
            n.setTextColor(if(isCur) cPrimary else cName)
            a.text=if(x.artist.isBlank()) x.album else "${x.artist} · ${x.album}"
            a.setTextColor(cDim)
            rm.setTextColor(cDim)
            playing.visibility=if(isCur) View.VISIBLE else View.GONE
            row.setOnClickListener{ onPlay(position, x) }
            rm.setOnClickListener{ onRemove(position, x) }
        }
    }
}
