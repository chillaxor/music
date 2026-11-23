package com.linghit.gdmusic

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.linghit.gdmusic.spider.SpiderEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生影视对照版 —— 1:1 复刻 assets/video.html 整套 UI，底层改原生实现，
 * 用于与 WebView 版做同界面速度对比。
 *
 * 页面：豆瓣榜首页（11 榜 / 按天缓存 / 左右滑切榜 / 滚动翻页）→ 全网搜索结果页
 * （左侧站点 tab + 右侧海报网格，边到边渲染）→ 详情选集 → 播放（借壳 VideoActivity）。
 * 顶栏：音乐 / 全网-本站 / 爬虫开关 / 历史 / 源管理 / 下载管理 / 切回 WebView 版。
 * 历史与源列表与老版共用 video_sources 这个 SharedPreferences，两个版本数据互通。
 */
class NativeVideoActivity : AppCompatActivity() {

    private data class SiteInfo(
        val name: String, val api: String, val key: String,
        val spider: Boolean = false, val jar: String = "", val ext: String = "",
        // 订阅完整配置（parses/rules/spider），播放解析链要用；Web 版存 STATE.srcCfg[i]
        val cfg: JSONObject = JSONObject()
    )
    private data class Vod(
        val id: String, val name: String, val pic: String, val year: String,
        val area: String, val typeName: String, val remarks: String,
        val siteName: String, val api: String,
        // 爬虫源上下文：搜索结果要带齐，详情/播放才能交给 SpiderEngine 解析
        val spider: Boolean = false, val spiderJar: String = "", val spiderExt: String = "", val siteKey: String = "",
        // 订阅配置（parses/rules）贯通到播放链，对齐 WebView 版 cfgOf(t)
        val cfg: JSONObject = JSONObject()
    )
    private data class EpGroup(val from: String, val eps: List<Pair<String, String>>)
    private data class DbItem(val title: String, val rating: Double, val sub: String, val pic: String)
    private data class SearchGroup(val name: String, val api: String, val items: MutableList<Vod>)

    companion object {
        private const val TAG = "VidNative"
        private const val SNIFF_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PREF = "video_sources"
        private const val KEY_SOURCES = "sources"
        private const val KEY_HIST = "search_history"
        private const val KEY_DB = "native_douban_cache"
        private const val DB_PAGE = 21
        private const val DEFAULT_SOURCES =
            "[{\"name\":\"盒子迷多仓\",\"url\":\"https://盒子迷.top/禁止贩卖\"}," +
                "{\"name\":\"盒子迷动漫仓\",\"url\":\"https://盒子迷.top/摸鱼\"}]"
        private val DB_CHARTS = arrayOf(
            "tv_hot" to "热播剧集", "tv_global_best_weekly" to "全球口碑",
            "tv_chinese_best_weekly" to "华语口碑", "tv_domestic" to "国产剧",
            "tv_korean" to "韩剧", "tv_american" to "美剧", "tv_japanese" to "日剧",
            "tv_variety_show" to "综艺", "tv_animation" to "动画",
            "movie_showing" to "正在热映", "movie_top250" to "电影Top250"
        )
    }

    private val prefs by lazy { getSharedPreferences(PREF, MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val pool = Executors.newFixedThreadPool(12) { r ->
        Thread(r, "native-search").apply { isDaemon = true }
    }
    // 详情/播放解析用独立池：搜索 132 源会把 pool 12 线程占满（每源 CountDownLatch 同步等 18s），
    // 详情若复用同一池会排队卡死在「正在加载详情」。独立池保证搜索期间点详情仍能立即解析。
    private val detailPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "native-detail").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    // 播放解析链（直嗅 WebView → parses → 静态嗅探），与 WebView 版 playSpiderEp 同口径
    private val resolver by lazy { VideoResolver(this) }

    // 视图
    private lateinit var kw: EditText
    private lateinit var stat: TextView
    private lateinit var load: TextView
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var mainList: RecyclerView
    private lateinit var empty: TextView
    private lateinit var histPanel: LinearLayout
    private lateinit var searchPage: View
    private lateinit var spKw: TextView
    private lateinit var spStat: TextView
    private lateinit var srcTabs: RecyclerView
    private lateinit var srcResults: RecyclerView
    private lateinit var sheet: View
    private lateinit var dPic: ImageView
    private lateinit var dTit: TextView
    private lateinit var dInfo: TextView
    private lateinit var dDesc: TextView
    private lateinit var detailTabs: LinearLayout
    private lateinit var epsGrid: android.widget.GridLayout
    private lateinit var epsHint: TextView
    private lateinit var dlPage: View
    private lateinit var dlList: RecyclerView
    private lateinit var dlEmpty: TextView
    private lateinit var srcDrawer: View

    // adapter
    private lateinit var dbAdapter: DbAdapter
    private lateinit var tabAdapter: TabAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var simpleAdapter: SimpleAdapter

    // 状态
    private var chartIdx = 0
    private var dbItems = ArrayList<DbItem>()
    private var dbTotal = 0
    private var dbReq = 0
    private var dbLoadingMore = false
    private var dbNoMore = false
    private var scopeAll = true
    private var spiderOn = false

    private var searchSeq = 0
    private var startedAt = 0L
    private var firstAt = 0L
    private var doneCnt = 0
    private var totalCnt = 0
    private var hitCnt = 0
    private var resultCnt = 0
    private val groups = ArrayList<SearchGroup>()
    private var tabSel = 0

    /**
     * 与 VideoActivity / App 同款拦截：爬虫 jar（LocalFile / Market 等 init 里调
     * Init.checkPermission()）用 Init.getActivity() 反射拿到「当前 Activity」拉起
     * 系统「所有文件访问」设置页。原生影视页现在也跑 132 个爬虫源，必须在这里同样挡掉，
     * 否则一开 🕷 就被甩到权限页。只挡这一个 action，页面自身跳转不受影响。
     */
    override fun startActivity(intent: Intent) {
        if (intent.action == VideoActivity.ACTION_MANAGE_ALL_FILES_ACCESS) {
            Log.w("VidSpider", "拦截爬虫 jar 权限页(NativeVideoActivity): ${intent.action}")
            return
        }
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (intent.action == VideoActivity.ACTION_MANAGE_ALL_FILES_ACCESS) {
            Log.w("VidSpider", "拦截爬虫 jar 权限页(NativeVideoActivity): ${intent.action}")
            return
        }
        super.startActivity(intent, options)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_native_video)
        bindViews()
        setupHome()
        setupSearchPage()
        setupDetail()
        setupDownloads()
        setupSources()
        setupTopbar()

        if (b == null) {
            intent.getStringExtra("kw")?.takeIf { it.isNotBlank() }?.let {
                kw.setText(it)
                doSearch(it)
            } ?: loadDouban(chartIdx)
        } else restoreState(b)
    }

    private fun bindViews() {
        kw = findViewById(R.id.nvKw)
        stat = findViewById(R.id.nvStat)
        load = findViewById(R.id.nvLoad)
        chips = findViewById(R.id.nvChips)
        chipsScroll = findViewById(R.id.nvChipsScroll)
        mainList = findViewById(R.id.nvMainList)
        empty = findViewById(R.id.nvEmpty)
        histPanel = findViewById(R.id.nvHistPanel)
        searchPage = findViewById(R.id.nvSearchPage)
        spKw = findViewById(R.id.nvSpKw)
        spStat = findViewById(R.id.nvSpStat)
        srcTabs = findViewById(R.id.nvSrcTabs)
        srcResults = findViewById(R.id.nvSrcResults)
        sheet = findViewById(R.id.nvSheet)
        dPic = findViewById(R.id.nvDPic)
        dTit = findViewById(R.id.nvDTit)
        dInfo = findViewById(R.id.nvDInfo)
        dDesc = findViewById(R.id.nvDDesc)
        detailTabs = findViewById(R.id.nvDetailTabs)
        epsGrid = findViewById(R.id.nvEpsGrid)
        epsHint = findViewById(R.id.nvEpsHint)
        dlPage = findViewById(R.id.nvDlPage)
        dlList = findViewById(R.id.nvDlList)
        dlEmpty = findViewById(R.id.nvDlEmpty)
        srcDrawer = findViewById(R.id.nvSrcDrawer)
    }

    private fun restoreState(b: Bundle) {
        chartIdx = b.getInt("chartIdx", 0)
        if (b.getBoolean("searching", false)) {
            spKw.text = b.getString("kw", "")
            searchPage.visibility = View.VISIBLE
            groups.clear()
            tabSel = 0
            tabAdapter.notifyDataSetChanged(); vodAdapter.notifyDataSetChanged()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("chartIdx", chartIdx)
        out.putBoolean("searching", searchPage.visibility == View.VISIBLE)
        out.putString("kw", spKw.text.toString())
    }

    // ==================== 顶栏 ====================
    private fun setupTopbar() {
        findViewById<View>(R.id.nvGo).setOnClickListener { doSearch(kw.text.toString().trim()) }
        kw.setOnEditorActionListener { _, _, _ -> doSearch(kw.text.toString().trim()); true }
        kw.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = renderHist()
        })
        kw.setOnFocusChangeListener { v, has -> if (has) renderHist() else histPanel.visibility = View.GONE }

        findViewById<View>(R.id.nvMusic).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        val scopeBtn = findViewById<TextView>(R.id.nvScope)
        scopeBtn.setOnClickListener {
            scopeAll = !scopeAll
            scopeBtn.text = if (scopeAll) "全网" else "本站"
            toast(if (scopeAll) "全网搜索：并发所有直连站" else "本站搜索：当前接口源")
        }
        val spiderBtn = findViewById<TextView>(R.id.nvSpider)
        // adb 直启可带 --es spider true，方便 A/B 计时：am start ... --es kw 庆余年 --es spider true
        if (intent.getBooleanExtra("spider", false) && !spiderOn) {
            spiderOn = true
            spiderBtn.textColor("#F55E1A")
        }
        spiderBtn.setOnClickListener {
            spiderOn = !spiderOn
            spiderBtn.textColor(if (spiderOn) "#F55E1A" else "#8b8f99")
            val n = if (spiderOn) loadSites(true).count { it.spider } else 0
            toast(if (spiderOn) "爬虫源已开（$n 个🕷源，下次搜索一起并发）" else "爬虫源已关")
        }
        findViewById<View>(R.id.nvHistory).setOnClickListener { renderHist(true) }
        findViewById<View>(R.id.nvSources).setOnClickListener { srcDrawer.visibility = View.VISIBLE }
        findViewById<View>(R.id.nvDownloads).setOnClickListener { openDownloads() }
        findViewById<View>(R.id.nvSwitch).setOnClickListener {
            val i = Intent(this, VideoActivity::class.java)
            if (kw.text.toString().isNotBlank()) i.putExtra("native_kw", kw.text.toString().trim())
            startActivity(i)
            finish()
        }
    }

    // ==================== 首页 豆瓣榜 ====================
    private fun setupHome() {
        dbAdapter = DbAdapter { item ->
            kw.setText(item.title)
            doSearch(item.title)
        }
        mainList.layoutManager = LinearLayoutManager(this)
        mainList.adapter = dbAdapter
        renderChips()

        // 滚动翻页 + 左右滑切榜
        mainList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || searchPage.visibility == View.VISIBLE) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= dbAdapter.itemCount - 4) loadMoreDouban()
            }
        })
        var sx = 0f; var sy = 0f; var st = 0L; var track = false
        mainList.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; st = SystemClock.elapsedRealtime(); track = true }
                MotionEvent.ACTION_UP -> {
                    if (track && searchPage.visibility != View.VISIBLE) {
                        val dx = e.x - sx; val dy = e.y - sy; val dt = SystemClock.elapsedRealtime() - st
                        if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5f && dt < 900) {
                            val next = chartIdx + if (dx < 0) 1 else -1
                            if (next in DB_CHARTS.indices) { chartIdx = next; renderChips(); loadDouban(next) }
                        }
                    }
                    track = false
                }
                MotionEvent.ACTION_CANCEL -> track = false
            }
            false
        }
    }

    private fun renderChips() {
        chips.removeAllViews()
        DB_CHARTS.forEachIndexed { i, (_, name) ->
            val t = TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(Color.parseColor(if (i == chartIdx) "#FFFFFF" else "#8b8f99"))
                setPadding(14, 6, 14, 6)
                background = chipBg(i == chartIdx)
                setOnClickListener {
                    if (i == chartIdx) return@setOnClickListener
                    chartIdx = i; renderChips(); loadDouban(i)
                    chipsScroll.post { chips.getChildAt(i)?.let { c -> chipsScroll.smoothScrollTo(c.left - chipsScroll.width / 2 + c.width / 2, 0) } }
                }
            }
            chips.addView(t)
        }
    }

    private fun chipBg(on: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 999f
        setColor(Color.parseColor(if (on) "#F55E1A" else "#1a1c22"))
        setStroke(1, Color.parseColor(if (on) "#F55E1A" else "#2c2f38"))
    }

    private fun loadDouban(idx: Int) {
        val id = DB_CHARTS[idx].first
        val name = DB_CHARTS[idx].second
        val my = ++dbReq
        dbItems.clear(); dbAdapter.notifyDataSetChanged()
        dbNoMore = false; dbLoadingMore = false; dbTotal = 0
        val cache = dbCacheGet(id)
        if (cache != null) {
            dbItems.addAll(cache.first); dbTotal = cache.second
            dbNoMore = dbTotal > 0 && dbItems.size >= dbTotal
            dbAdapter.notifyDataSetChanged(); dbState(name); return
        }
        setStat("正在加载豆瓣「$name」…")
        pool.execute {
            val res = dbFetch(id, 0)
            main.post {
                if (my != dbReq) return@post
                if (res == null) {
                    empty.visibility = View.VISIBLE
                    setStat("豆瓣「$name」加载失败（网络或限流），点其他榜重试")
                    return@post
                }
                dbItems.addAll(res.second); dbTotal = res.first
                dbNoMore = res.second.size < DB_PAGE || (dbTotal > 0 && dbItems.size >= dbTotal)
                dbCachePut(id, dbItems, dbTotal)
                empty.visibility = if (dbItems.isEmpty()) View.VISIBLE else View.GONE
                dbAdapter.notifyDataSetChanged(); dbState(name)
            }
        }
    }

    private fun loadMoreDouban() {
        if (dbLoadingMore || dbNoMore || dbItems.isEmpty()) return
        val id = DB_CHARTS[chartIdx].first; val name = DB_CHARTS[chartIdx].second
        val my = dbReq; val start = dbItems.size
        dbLoadingMore = true
        setStat("豆瓣 · $name · 正在加载更多…（已有 $start 部）")
        pool.execute {
            val res = dbFetch(id, start)
            main.post {
                dbLoadingMore = false
                if (my != dbReq || res == null) { dbState(name); return@post }
                if (res.second.isEmpty()) { dbNoMore = true; dbState(name); return@post }
                val seen = HashSet(dbItems.map { it.title })
                val fresh = res.second.filter { seen.add(it.title) }
                if (fresh.isNotEmpty()) {
                    dbItems.addAll(fresh); dbAdapter.notifyItemRangeInserted(start, fresh.size)
                }
                dbTotal = res.first
                dbNoMore = res.second.size < DB_PAGE || (dbTotal > 0 && dbItems.size >= dbTotal)
                dbCachePut(id, dbItems, dbTotal); dbState(name)
            }
        }
    }

    /** 返回 Pair(total, items)，失败返回 null。豆瓣图床需 iPhone UA + m.douban.com Referer */
    private fun dbFetch(id: String, start: Int): Pair<Int, List<DbItem>>? {
        val url = "https://m.douban.com/rexxar/api/v2/subject_collection/$id/items?start=$start&count=$DB_PAGE"
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15")
                .header("Referer", "https://m.douban.com/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body?.string().orEmpty())
                val total = j.optInt("total", 0)
                val arr = j.optJSONArray("subject_collection_items") ?: return null
                val out = ArrayList<DbItem>()
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val title = v.optString("title"); if (title.isBlank()) continue
                    val rating = v.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0
                    var sub = v.optString("card_subtitle").replace(Regex("\\s*/\\s*"), " · ")
                    if (sub.length > 52) sub = sub.substring(0, 52) + "…"
                    val pic = v.optJSONObject("pic")?.let { it.optString("normal").ifBlank { it.optString("large") } } ?: ""
                    out.add(DbItem(title, rating, sub, pic))
                }
                total to out
            }
        } catch (e: Exception) { Log.w(TAG, "dbFetch fail: ${e.message}"); null }
    }

    private fun dbState(name: String) {
        setStat("豆瓣 · $name · ${dbItems.size}${if (dbTotal > 0) "/$dbTotal" else ""} 部 · ${if (dbNoMore) "到底了" else "继续下滑看更多"} · 左右滑切榜 / 点剧名搜索")
    }

    private fun dbCacheGet(id: String): Pair<List<DbItem>, Int>? = try {
        val all = JSONObject(prefs.getString(KEY_DB, "{}") ?: "{}")
        val c = all.optJSONObject(id) ?: return null
        val day = SimpleDateFormat("yyyy-M-d", Locale.getDefault()).format(Date())
        if (c.optString("day") != day) return null
        val arr = c.optJSONArray("items") ?: return null
        if (arr.length() == 0) return null
        val out = ArrayList<DbItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(DbItem(o.optString("t"), o.optDouble("r"), o.optString("s"), o.optString("p")))
        }
        out to c.optInt("total")
    } catch (e: Exception) { null }

    private fun dbCachePut(id: String, items: List<DbItem>, total: Int) = try {
        val all = JSONObject(prefs.getString(KEY_DB, "{}") ?: "{}")
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.title).put("r", it.rating).put("s", it.sub).put("p", it.pic)) }
        all.put(id, JSONObject()
            .put("day", SimpleDateFormat("yyyy-M-d", Locale.getDefault()).format(Date()))
            .put("items", arr).put("total", total))
        prefs.edit().putString(KEY_DB, all.toString()).apply()
    } catch (e: Exception) { Log.w(TAG, "dbCachePut: ${e.message}") }

    // ==================== 全网搜索 ====================
    private fun setupSearchPage() {
        tabAdapter = TabAdapter { selectGroup(it) }
        srcTabs.layoutManager = LinearLayoutManager(this)
        srcTabs.adapter = tabAdapter
        vodAdapter = VodAdapter { openDetail(it) }
        srcResults.layoutManager = GridLayoutManager(this, 2)
        srcResults.adapter = vodAdapter
        findViewById<View>(R.id.nvSpBack).setOnClickListener { closeSearchPage() }
    }

    private fun doSearch(word: String) {
        if (word.isBlank()) { toast("先输入片名"); return }
        histPanel.visibility = View.GONE
        saveHist(word)
        val seq = ++searchSeq
        groups.clear(); tabSel = 0
        tabAdapter.notifyDataSetChanged(); vodAdapter.notifyDataSetChanged()
        startedAt = SystemClock.elapsedRealtime(); firstAt = 0L
        doneCnt = 0; hitCnt = 0; resultCnt = 0
        val sites = loadSites(spiderOn)
        totalCnt = sites.size
        spKw.text = word
        spStat.text = "搜索 $totalCnt 个源…"
        searchPage.visibility = View.VISIBLE
        setStat("正在全网搜索 $totalCnt 个源…${if (spiderOn) "（含🕷爬虫）" else ""}")
        Log.i(TAG, "search start w=$word sites=$totalCnt spider=$spiderOn seq=$seq")
        // 有限并发 worker（对齐 WebView 版 searchSpiderAll 的 CONC=6）：
        // 132 源一次性全 submit 会把 SpiderEngine 内部 4 线程池彻底堵死，
        // 搜索期间点详情/播放的 detailAsync/playAsync 排不上队 → 14s 超时「详情加载失败」。
        // 用共享队列 + 4 worker，同时在途不超过 4 个，给详情/播放留出线程。
        val queue = java.util.concurrent.ConcurrentLinkedQueue(sites)
        val workers = minOf(4, totalCnt.coerceAtLeast(1))
        repeat(workers) {
            pool.execute {
                while (true) {
                    if (seq != searchSeq) return@execute
                    val s = queue.poll() ?: return@execute
                    val items = searchSite(s, word)
                    main.post {
                        if (seq != searchSeq) return@post
                        doneCnt++
                        if (items.isNotEmpty()) {
                            hitCnt++
                            if (firstAt == 0L) {
                                firstAt = SystemClock.elapsedRealtime() - startedAt
                                Log.i(TAG, "FIRST RESULT ${firstAt}ms from ${s.name}")
                            }
                            resultCnt += items.size
                            groups.add(SearchGroup(s.name, s.api, items.toMutableList()))
                            // 边到边：一个源回来就刷 tab（不自动切用户已选中的 tab）
                            tabAdapter.notifyDataSetChanged()
                            if (tabSel == groups.size - 1 || tabSel >= groups.size) {
                                tabSel = groups.size - 1
                                vodAdapter.submit(groups[tabSel].items)
                            }
                            empty.visibility = View.GONE
                        }
                        spStat.text = "已搜 $doneCnt/$totalCnt · $hitCnt 源有结果"
                        setStat("正在全网搜索… $doneCnt/$totalCnt（$hitCnt 个源有结果）")
                        if (doneCnt == totalCnt) {
                            val total = SystemClock.elapsedRealtime() - startedAt
                            val msg = "全网搜索：直连 $hitCnt/$totalCnt · 共 $resultCnt 条 · 首条 ${firstAt}ms · 全部 ${total}ms"
                            spStat.text = "共 $resultCnt 条"; setStat(msg)
                            Log.i(TAG, "search DONE seq=$seq first=${firstAt}ms total=${total}ms hits=$hitCnt items=$resultCnt")
                            if (groups.isEmpty()) vodAdapter.submit(emptyList())
                        }
                    }
                }
            }
        }
    }

    private fun selectGroup(i: Int) {
        if (i !in groups.indices) return
        tabSel = i; tabAdapter.notifyDataSetChanged()
        vodAdapter.submit(groups[i].items)
        spStat.text = "${groups[i].name} · ${groups[i].items.size} 条"
    }

    private fun closeSearchPage() {
        searchPage.visibility = View.GONE
        dbState(DB_CHARTS[chartIdx].second)
    }

    private fun searchSite(s: SiteInfo, w: String): List<Vod> {
        return if (s.spider) searchSpiderSite(s, w) else searchDirectSite(s, w)
    }

    /** 直连站：ac=videolist 一次 GET */
    private fun searchDirectSite(s: SiteInfo, w: String): List<Vod> {
        val sep = if (s.api.contains("?")) "&" else "?"
        val url = s.api + sep + "ac=videolist&wd=" + URLEncoder.encode(w, "UTF-8") + "&pg=1"
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "okhttp/3.15.0").header("Accept", "*/*").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("list") ?: return emptyList()
                val out = ArrayList<Vod>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("vod_id"); val name = o.optString("vod_name")
                    if (id.isBlank() || name.isBlank()) continue
                    out.add(Vod(id, name, o.optString("vod_pic"), o.optString("vod_year"),
                        o.optString("vod_area"), o.optString("vod_type_name").ifBlank { o.optString("vod_class") },
                        o.optString("vod_remarks"), s.name, s.api,
                        cfg = s.cfg))
                }
                out
            }
        } catch (e: Exception) { Log.w(TAG, "search fail ${s.name}: ${e.message}"); emptyList() }
    }

    /** 爬虫源：SpiderEngine.searchAsync 是回调式，用 latch 转成同步结果（本方法跑在 pool 线程） */
    private fun searchSpiderSite(s: SiteInfo, w: String): List<Vod> {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOf<String?>(null)
        try {
            SpiderEngine.get(this).searchAsync(s.jar, s.api, s.ext, s.key, w) { json ->
                holder[0] = json; latch.countDown()
            }
            if (!latch.await(18, TimeUnit.SECONDS)) {
                Log.w(TAG, "spider search timeout ${s.name}")
                return emptyList()
            }
            val txt = holder[0] ?: return emptyList()
            val arr = JSONObject(txt).optJSONArray("list") ?: return emptyList()
            val out = ArrayList<Vod>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("vod_id"); val name = o.optString("vod_name")
                if (id.isBlank() || name.isBlank()) continue
                    out.add(Vod(id, name, o.optString("vod_pic"), o.optString("vod_year"),
                        o.optString("vod_area"), o.optString("vod_type_name").ifBlank { o.optString("vod_class") },
                        o.optString("vod_remarks"), "🕷${s.name}", s.api,
                        true, s.jar, s.ext, s.key, cfg = s.cfg))
            }
            return out
        } catch (e: Exception) {
            Log.w(TAG, "spider search fail ${s.name}: ${e.message}")
            return emptyList()
        }
    }

    // ==================== 详情 / 选集 ====================
    private fun setupDetail() {
        findViewById<View>(R.id.nvDClose).setOnClickListener { sheet.visibility = View.GONE }
    }

    private fun openDetail(v: Vod) {
        setStat("正在加载详情：${v.name}")
        detailPool.execute {
            val d = try {
                if (v.spider) {
                    // 爬虫源详情：detailContent 返回 JSON 数组，取第一条
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val holder = arrayOf<String?>(null)
                    SpiderEngine.get(this).detailAsync(v.spiderJar, v.api, v.spiderExt, v.siteKey, v.id) { json ->
                        holder[0] = json; latch.countDown()
                    }
                    if (!latch.await(14, TimeUnit.SECONDS)) null
                    else {
                        val arr = JSONObject(holder[0] ?: "{}").optJSONArray("list")
                            ?: runCatching { JSONArray(holder[0] ?: "[]") }.getOrNull()
                        if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
                    }
                } else {
                    val sep = if (v.api.contains("?")) "&" else "?"
                    val url = v.api + sep + "ac=detail&ids=" + URLEncoder.encode(v.id, "UTF-8")
                    val req = Request.Builder().url(url).header("User-Agent", "okhttp/3.15.0").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null else {
                            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("list")
                            if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
                        }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "detail fail: ${e.message}"); null }
            main.post { if (d == null) toast("详情加载失败") else showDetail(v, d) }
        }
    }

    private fun showDetail(v: Vod, d: JSONObject) {
        dTit.text = d.optString("vod_name").ifBlank { v.name }
        val info = listOf(d.optString("vod_year"), d.optString("vod_area"),
            d.optString("vod_type_name").ifBlank { d.optString("vod_class") }, d.optString("vod_director"))
            .filter { it.isNotBlank() }.joinToString(" · ")
        dInfo.text = info
        val desc = d.optString("vod_content").replace(Regex("<[^>]+>"), " ").trim()
        dDesc.text = desc
        NativeImg.into(dPic, v.pic, R.drawable.bg_his_pic)

        val groups = parsePlay(d)
        detailTabs.removeAllViews(); epsGrid.removeAllViews()
        if (groups.isEmpty()) {
            epsHint.text = "选集"
            addEpButton(epsGrid, "该资源没有可播放的集数", null)
            sheet.visibility = View.VISIBLE
            return
        }
        fun select(gi: Int) {
            for (i in 0 until detailTabs.childCount)
                detailTabs.getChildAt(i).alpha = if (i == gi) 1f else 0.5f
            epsGrid.removeAllViews()
            val g = groups[gi]
            epsHint.text = "${g.from} · ${g.eps.size} 集"
            g.eps.forEachIndexed { ei, ep -> addEpCell(epsGrid, g, ei, ep, v) }
        }
        groups.forEachIndexed { i, g ->
            val t = TextView(this).apply {
                text = "${g.from} (${g.eps.size})"; textSize = 13f
                setTextColor(Color.parseColor(if (i == 0) "#FFFFFF" else "#8b8f99"))
                setPadding(12, 8, 12, 8)
                setOnClickListener { select(i) }
            }
            detailTabs.addView(t)
        }
        select(0)
        sheet.visibility = View.VISIBLE
    }

    private fun addEpButton(grid: android.widget.GridLayout, text: String, click: (() -> Unit)?) {
        val t = TextView(this).apply {
            this.text = text; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#e9eaee"))
            background = epBg(false)
            setPadding(4, 12, 4, 12)
            if (click != null) setOnClickListener { click() }
        }
        grid.addView(t, android.widget.GridLayout.LayoutParams().apply {
            width = 0; height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(4, 4, 4, 4)
        })
    }

    private fun addEpCell(grid: android.widget.GridLayout, g: EpGroup, ei: Int, ep: Pair<String, String>, v: Vod) {
        val cell = FrameLayout(this)
        val btn = TextView(this).apply {
            text = ep.first.ifBlank { "第${ei + 1}集" }; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#e9eaee")); background = epBg(false)
            setPadding(4, 12, 4, 22)
            setOnClickListener { play(v, g, ei, ep); sheet.visibility = View.GONE }
        }
        cell.addView(btn, FrameLayout.LayoutParams(match, match))
        val dl = TextView(this).apply {
            text = "⬇"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ff7a3c")); background = epBg(false)
            setOnClickListener { downloadEp(v, g, ei, ep) }
        }
        cell.addView(dl, FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, 2, 2, 0) })
        grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
            width = 0; height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(4, 4, 4, 4)
        })
    }

    private fun epBg(on: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 8f
        setColor(Color.parseColor(if (on) "#F55E1A" else "#22252d"))
        setStroke(1, Color.parseColor("#2c2f38"))
    }

    private fun parsePlay(v: JSONObject): List<EpGroup> {
        val rawFrom = v.optString("vod_play_from")
        val froms = if (rawFrom.contains("\$\$\$")) rawFrom.split("\$\$\$") else rawFrom.split(",")
        val segs = v.optString("vod_play_url").split("\$\$\$")
        val out = ArrayList<EpGroup>()
        segs.forEachIndexed { i, seg ->
            val eps = ArrayList<Pair<String, String>>()
            seg.split("#").forEach { e ->
                if (e.isBlank()) return@forEach
                val k = e.lastIndexOf('$')
                val name = (if (k > 0) e.substring(0, k) else e).trim()
                val url = (if (k > 0) e.substring(k + 1) else e).trim()
                if (url.isNotBlank()) eps.add(name to url)
            }
            if (eps.isNotEmpty()) {
                val from = froms.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "线路${i + 1}"
                out.add(EpGroup(from, eps))
            }
        }
        return out
    }

    /** 对齐 WebView 版 playSpiderEp：playerContent → 直嗅 → parses → 静态嗅探 → 缓存。
     *  解析不出直链就提示换线路，绝不把加密串/非直链丢给 ExoPlayer。 */
    private fun play(v: Vod, g: EpGroup, idx: Int, ep: Pair<String, String>) {
        val rawUrl = ep.second
        setStat("正在解析播放地址：${ep.first}")
        detailPool.execute {
            val referer = refererOf(v.api)
            val r = resolveEp(v, g, ep)
            if (r == null || !resolver.isMediaUrl(r.url)) {
                Log.w(TAG, "play 解析失败 ${ep.first}: raw=${rawUrl.take(90)}")
                main.post { toast("这集解析失败，可换其他线路/源") }
                return@execute
            }
            val finalUrl = r.url
            val playHeaders = JSONObject()
            r.headers.forEach { (k, value) -> playHeaders.put(k, value) }
            Log.i(TAG, "play READY ${ep.first} by=${r.by} -> ${finalUrl.take(110)}")
            main.post {
                val eps = JSONArray().apply {
                    g.eps.forEachIndexed { i, (n, u) ->
                        put(JSONObject().put("name", n)
                            .put("url", if (i == idx) finalUrl else u)
                            .put("spider", false))
                    }
                }.toString()
                val meta = JSONObject().put("key", "${if (v.spider) "s" else "d"}:${v.api}|${v.id}")
                    .put("pic", v.pic).put("epName", ep.first).put("vodId", v.id)
                    .put("siteApi", v.api).put("from", g.from).toString()
                val json = JSONObject().put("url", finalUrl).put("title", "${v.name} · ${g.from} · ${ep.first}")
                    .put("referer", referer).put("eps", eps).put("idx", idx).put("meta", meta)
                    .put("headers", playHeaders.toString()).toString()
                val i = Intent(this, VideoActivity::class.java).apply {
                    putExtra(VideoActivity.EXTRA_NATIVE_PLAY, json)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
            }
        }
    }

    /** 播放/下载共用的解析入口（跑在 detailPool 线程，可阻塞等 jar/网络）。
     *  返回 null 表示解析不出直链，调用方必须提示换线路，绝不把加密串丢给播放器。 */
    private fun resolveEp(v: Vod, g: EpGroup, ep: Pair<String, String>): VideoResolver.Result? {
        val referer = refererOf(v.api)
        val rawUrl = ep.second
        val seed = rawUrl // 缓存种子必须在回填前取（对齐 WebView e.url 回填前 resolveCKey）
        var result: VideoResolver.Result? = null
        if (v.spider) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOf<String?>(null)
            SpiderEngine.get(this).playAsync(v.spiderJar, v.api, v.spiderExt, v.siteKey, g.from, rawUrl) { json ->
                holder[0] = json; latch.countDown()
            }
            if (latch.await(17, TimeUnit.SECONDS)) {
                val pc = runCatching { JSONObject(holder[0] ?: "{}") }.getOrNull()
                val u = (pc?.optString("url") ?: "").ifBlank { pc?.optString("playUrl") ?: "" }
                val parse = pc?.optBoolean("parse", false) ?: false
                val jx = pc?.optInt("jx", 0) ?: 0
                val h = pc?.let { it.optJSONObject("header") ?: it.optJSONObject("headers") }
                val headers = LinkedHashMap<String, String>().apply {
                    put("User-Agent", VideoResolver.SNIFF_UA)
                    put("Referer", referer)
                    if (h != null) {
                        val it = h.keys()
                        while (it.hasNext()) { val k = it.next(); put(k, h.optString(k)) }
                    }
                }
                if (u.isNotBlank()) {
                    Log.i(TAG, "spider play ${ep.first} -> ${u.take(110)} parse=$parse jx=$jx")
                    result = resolver.resolve(u, parse, jx, headers, v.cfg, referer,
                        "s", v.siteKey.ifBlank { v.api }, g.from, seed)
                } else Log.w(TAG, "spider play 空 url: ${holder[0]?.take(200)}")
            } else Log.w(TAG, "spider play timeout ${v.name}")
        } else {
            result = resolver.resolve(rawUrl, false, 0,
                mapOf("User-Agent" to VideoResolver.SNIFF_UA, "Referer" to referer),
                v.cfg, referer, "d", v.api, g.from, seed)
        }
        return result
    }

    private fun refererOf(api: String): String = try {
        val u = android.net.Uri.parse(api); "${u.scheme}://${u.host}"
    } catch (e: Exception) { "" }

    /** 借壳 VideoActivity 的原生下载器：与播放共用解析链（含爬虫源 spiderPlay/parses），再交给那边下载 */
    private fun downloadEp(v: Vod, g: EpGroup, ei: Int, ep: Pair<String, String>) {
        detailPool.execute {
            val r = resolveEp(v, g, ep)
            if (r == null || !resolver.isMediaUrl(r.url)) {
                main.post { toast("下载地址解析失败，可换其他线路/源") }
                return@execute
            }
            val headers = JSONObject()
            r.headers.forEach { (k, value) -> headers.put(k, value) }
            val json = JSONObject().put("url", r.url).put("headers", headers.toString())
                .put("title", "${v.name}-${g.from}-${ep.first.ifBlank { ei + 1 }}").toString()
            main.post {
                val i = Intent(this, VideoActivity::class.java).apply { putExtra(VideoActivity.EXTRA_NATIVE_DOWNLOAD, json) }
                startActivity(i)
                toast("已交给下载器：${ep.first.ifBlank { "第${ei + 1}集" }}")
            }
        }
    }

    // ==================== 搜索历史 ====================
    private fun loadHist(): MutableList<String> = try {
        val arr = JSONArray(prefs.getString(KEY_HIST, "[]") ?: "[]")
        (0 until arr.length()).map { arr.optString(it) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun saveHist(w: String) {
        val a = loadHist().apply { remove(w); add(0, w) }
        prefs.edit().putString(KEY_HIST, JSONArray(a.take(10)).toString()).apply()
    }

    private fun renderHist(force: Boolean = false) {
        val w = kw.text.toString().trim()
        val list = loadHist().let { if (w.isBlank()) it else it.filter { h -> h.contains(w, true) } }
        if (!force && w.isBlank() && !kw.hasFocus()) { histPanel.visibility = View.GONE; return }
        if (list.isEmpty()) { histPanel.visibility = View.GONE; return }
        histPanel.removeAllViews()
        val hd = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(14, 8, 14, 8)
        }
        hd.addView(TextView(this).apply { text = "搜索历史"; textSize = 11f; setTextColor(Color.parseColor("#8b8f99")) })
        hd.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        hd.addView(TextView(this).apply {
            text = "清空"; textSize = 12f; setTextColor(Color.parseColor("#ff5b5b"))
            setOnClickListener { prefs.edit().putString(KEY_HIST, "[]").apply(); histPanel.visibility = View.GONE }
        })
        histPanel.addView(hd)
        list.forEach { h ->
            histPanel.addView(TextView(this).apply {
                text = "🕘  $h"; textSize = 14f; setTextColor(Color.parseColor("#e9eaee"))
                setPadding(14, 11, 14, 11)
                setOnClickListener { kw.setText(h); histPanel.visibility = View.GONE; doSearch(h) }
            })
        }
        histPanel.visibility = View.VISIBLE
    }

    // ==================== 源管理 ====================
    private fun setupSources() {
        simpleAdapter = SimpleAdapter()
        findViewById<RecyclerView>(R.id.nvSrcList).apply {
            layoutManager = LinearLayoutManager(this@NativeVideoActivity)
            adapter = simpleAdapter
        }
        findViewById<View>(R.id.nvSrcMask).setOnClickListener { srcDrawer.visibility = View.GONE }
        findViewById<View>(R.id.nvSrcAdd).setOnClickListener {
            val name = findViewById<EditText>(R.id.nvSrcName).text.toString().trim()
            val url = findViewById<EditText>(R.id.nvSrcUrl).text.toString().trim()
            if (url.isBlank()) { toast("填订阅地址"); return@setOnClickListener }
            val a = loadSourceRaw().apply { put(JSONObject().put("name", name.ifBlank { "自定义源" }).put("url", url)) }
            prefs.edit().putString(KEY_SOURCES, a.toString()).apply()
            findViewById<EditText>(R.id.nvSrcName).text.clear()
            findViewById<EditText>(R.id.nvSrcUrl).text.clear()
            refreshSourceList(); toast("已添加源")
        }
        refreshSourceList()
    }

    private fun loadSourceRaw(): JSONArray = try {
        JSONArray(prefs.getString(KEY_SOURCES, null) ?: DEFAULT_SOURCES)
    } catch (e: Exception) { JSONArray(DEFAULT_SOURCES) }

    private fun refreshSourceList() {
        val a = loadSourceRaw()
        val items = (0 until a.length()).map { i ->
            val o = a.optJSONObject(i) ?: JSONObject()
            SimpleRow("☰", o.optString("name").ifBlank { "未命名" }, o.optString("url"), "删除") {
                val b = JSONArray()
                for (j in 0 until a.length()) if (j != i) b.put(a.opt(j))
                prefs.edit().putString(KEY_SOURCES, b.toString()).apply()
                refreshSourceList(); toast("已删除")
            }
        }
        simpleAdapter.submit(items)
    }

    // ==================== 下载管理 ====================
    private lateinit var dlAdapter: SimpleAdapter

    private fun setupDownloads() {
        dlAdapter = SimpleAdapter()
        dlList.layoutManager = LinearLayoutManager(this)
        dlList.adapter = dlAdapter
        findViewById<View>(R.id.nvDlBack).setOnClickListener { dlPage.visibility = View.GONE }
    }

    private fun openDownloads() {
        dlPage.visibility = View.VISIBLE
        refreshDownloads()
    }

    private fun refreshDownloads() {
        val arr = listDownloads()
        val items = (0 until arr.length()).map { arr.optJSONObject(it) ?: JSONObject() }
        dlEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        dlList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val rows = items.map { o ->
            SimpleRow("🎬", o.optString("name"), fmtSize(o.optLong("size")), "▶") {
                try {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(android.net.Uri.parse(o.optString("uri")), o.optString("mime"))
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) { toast("没有能播放的 App") }
            }
        }
        dlAdapter.submit(rows)
    }

    private fun listDownloads(): JSONArray {
        val dirName = "从前慢"
        val list = JSONArray()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val proj = arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.SIZE, android.provider.MediaStore.Video.Media._ID)
                val sel = "${android.provider.MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel,
                    arrayOf(android.os.Environment.DIRECTORY_MOVIES + "/$dirName%"),
                    "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} ASC")?.use { c ->
                    val iN = c.getColumnIndexOrThrow(proj[0]); val iS = c.getColumnIndexOrThrow(proj[1]); val iI = c.getColumnIndexOrThrow(proj[2])
                    while (c.moveToNext()) {
                        val u = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(iI))
                        list.put(JSONObject().put("name", c.getString(iN)).put("size", c.getLong(iS))
                            .put("uri", u.toString()).put("mime", dlMime(c.getString(iN))))
                    }
                }
            } else {
                val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), dirName)
                dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
                    list.put(JSONObject().put("name", it.name).put("size", it.length())
                        .put("uri", android.net.Uri.fromFile(it).toString()).put("mime", dlMime(it.name)))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "listDownloads: ${e.message}") }
        return list
    }

    private fun dlMime(n: String) = when {
        n.contains(".m3u8", true) -> "application/x-mpegURL"
        n.contains(".mp4", true) -> "video/mp4"
        else -> "video/*"
    }

    private fun fmtSize(b: Long) = when {
        b > 1024 * 1024 * 1024 -> String.format("%.1f GB", b / 1073741824.0)
        b > 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
        else -> "${b / 1024} KB"
    }

    // ==================== 公共工具 ====================
    private fun loadSites(includeSpider: Boolean = false): List<SiteInfo> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: DEFAULT_SOURCES
        val out = ArrayList<SiteInfo>(); val seen = HashSet<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                val asset = if (url.contains("摸鱼") || url.contains("moyu", true)) "tvbox/subs/moyu.json" else "tvbox/subs/box.json"
                val cfg = JSONObject(assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() })
                val subJar = cfg.optString("spider")
                val sites = cfg.optJSONArray("sites") ?: continue
                for (j in 0 until sites.length()) {
                    val s = sites.optJSONObject(j) ?: continue
                    val type = s.optInt("type", -1); val api = s.optString("api")
                    val key = s.optString("key").ifBlank { s.optString("name") }
                    val name = s.optString("name").ifBlank { key }
                    if (type in 0..1 && api.startsWith("http", true)) {
                        if (!seen.add(api)) continue
                        out.add(SiteInfo(name, api, key, cfg = cfg))
                    } else if (includeSpider && api.startsWith("csp_", true) && !deniedSpider(api)) {
                        // 爬虫源：jar 优先用站点自带的，否则用订阅级 spider
                        val ownJar = s.optString("jar")
                        val jar = if (ownJar.isNotBlank()) ownJar else subJar
                        val id = "spider:$api:$key"
                        if (!seen.add(id)) continue
                        val ext = s.opt("ext").let { v ->
                            when (v) {
                                null -> ""
                                is String -> v
                                else -> v.toString()
                            }
                        }
                        out.add(SiteInfo(name, api, key, true, jar, ext, cfg = cfg))
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "loadSites: ${e.message}") }
        return out
    }

    /** 与 WebView 版 SPIDER_DENY 同口径：纯 JS 框架源跳过（带自有 jar 的已在上游豁免） */
    private fun deniedSpider(api: String): Boolean {
        val k = api.removePrefix("csp_")
        return k in setOf("XBPQ", "AppGet", "XYQHiker")
    }

    private fun setStat(t: String) { stat.text = t }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun toast(m: String) {
        val t = findViewById<TextView>(R.id.nvToast)
        t.text = m; t.visibility = View.VISIBLE; t.alpha = 1f
        main.removeCallbacksAndMessages("toast")
        main.postAtTime({ t.animate().alpha(0f).setDuration(250).withEndAction { t.visibility = View.GONE } }, "toast", SystemClock.uptimeMillis() + 1800)
    }

    private fun TextView.textColor(hex: String) = setTextColor(Color.parseColor(hex))

    override fun onBackPressed() {
        when {
            sheet.visibility == View.VISIBLE -> sheet.visibility = View.GONE
            srcDrawer.visibility == View.VISIBLE -> srcDrawer.visibility = View.GONE
            dlPage.visibility == View.VISIBLE -> dlPage.visibility = View.GONE
            histPanel.visibility == View.VISIBLE -> histPanel.visibility = View.GONE
            searchPage.visibility == View.VISIBLE -> closeSearchPage()
            else -> super.onBackPressed()
        }
    }

    // ==================== Adapter ====================
    private inner class DbAdapter(private val onClick: (DbItem) -> Unit) : RecyclerView.Adapter<DbAdapter.VH>() {
        override fun getItemCount() = dbItems.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_nv_douban_row, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(dbItems[p], p)
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val no = v.findViewById<TextView>(R.id.nvNo)
            private val pic = v.findViewById<ImageView>(R.id.nvPoster)
            private val title = v.findViewById<TextView>(R.id.nvDbTitle)
            private val meta = v.findViewById<TextView>(R.id.nvDbMeta)
            private val score = v.findViewById<TextView>(R.id.nvScore)
            fun bind(x: DbItem, i: Int) {
                no.text = "${i + 1}"
                no.setTextColor(Color.parseColor(if (i < 3) "#F55E1A" else "#8b8f99"))
                title.text = x.title; meta.text = x.sub
                if (x.rating > 0) { score.text = String.format("%.1f", x.rating); score.visibility = View.VISIBLE }
                else { score.text = "暂无"; score.setTextColor(Color.parseColor("#8b8f99")); score.visibility = View.VISIBLE }
                NativeImg.into(pic, x.pic, 0)
                itemView.setOnClickListener { onClick(x) }
            }
        }
    }

    private inner class TabAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<TabAdapter.VH>() {
        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_nv_src_tab, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(groups[p], p)
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v.findViewById<View>(R.id.nvTabRoot)
            private val name = v.findViewById<TextView>(R.id.nvTabName)
            private val cnt = v.findViewById<TextView>(R.id.nvTabCnt)
            fun bind(g: SearchGroup, i: Int) {
                name.text = g.name; cnt.text = "${g.items.size} 条"
                val on = i == tabSel
                name.setTextColor(Color.parseColor(if (on) "#F55E1A" else "#8b8f99"))
                cnt.setTextColor(Color.parseColor(if (on) "#ff7a3c" else "#8b8f99"))
                root.setBackgroundColor(Color.parseColor(if (on) "#1a1c22" else "#22252d"))
                root.setOnClickListener { onClick(i) }
            }
        }
    }

    private inner class VodAdapter(private val onClick: (Vod) -> Unit) : RecyclerView.Adapter<VodAdapter.VH>() {
        private val data = ArrayList<Vod>()
        fun submit(l: List<Vod>) { data.clear(); data.addAll(l); notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_nv_vod, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(data[p])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v.findViewById<View>(R.id.nvVodRoot)
            private val pic = v.findViewById<ImageView>(R.id.nvVodPic)
            private val site = v.findViewById<TextView>(R.id.nvVodSite)
            private val title = v.findViewById<TextView>(R.id.nvVodTitle)
            private val meta = v.findViewById<TextView>(R.id.nvVodMeta)
            private val rm = v.findViewById<TextView>(R.id.nvVodRemarks)
            fun bind(x: Vod) {
                title.text = x.name
                meta.text = listOf(x.year, x.area, x.typeName).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "影视资源" }
                rm.text = x.remarks; rm.visibility = if (x.remarks.isBlank()) View.GONE else View.VISIBLE
                site.text = x.siteName
                NativeImg.into(pic, x.pic, R.drawable.bg_his_pic)
                root.setOnClickListener { onClick(x) }
            }
        }
    }

    private data class SimpleRow(val icon: String, val main: String, val sub: String, val action: String, val onAction: () -> Unit)
    private inner class SimpleAdapter : RecyclerView.Adapter<SimpleAdapter.VH>() {
        private val data = ArrayList<SimpleRow>()
        fun submit(l: List<SimpleRow>) { data.clear(); data.addAll(l); notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_nv_simple_row, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(data[p])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val icon = v.findViewById<TextView>(R.id.nvSimpleIcon)
            private val main = v.findViewById<TextView>(R.id.nvSimpleMain)
            private val sub = v.findViewById<TextView>(R.id.nvSimpleSub)
            private val action = v.findViewById<TextView>(R.id.nvSimpleAction)
            fun bind(x: SimpleRow) {
                icon.text = x.icon; main.text = x.main
                sub.text = x.sub; sub.visibility = if (x.sub.isBlank()) View.GONE else View.VISIBLE
                action.text = x.action; action.visibility = View.VISIBLE
                action.setOnClickListener { x.onAction() }
            }
        }
    }
}

/** 带 header 的网络图加载（豆瓣图床要 iPhone UA + m.douban.com Referer），LruCache + 二次采样 */
private object NativeImg {
    private val mem = LruCache<String, Bitmap>(8 * 1024 * 1024)
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "native-img").apply { isDaemon = true } }
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun into(iv: ImageView, url: String?, ph: Int) {
        if (ph != 0) iv.setImageResource(ph) else iv.setImageDrawable(null)
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return
        mem.get(u)?.let { iv.setImageBitmap(it); return }
        iv.tag = u
        pool.execute {
            val bm = decode(u) ?: return@execute
            mem.put(u, bm)
            iv.post { if (iv.tag == u) iv.setImageBitmap(bm) }
        }
    }

    private fun decode(u: String): Bitmap? {
        val douban = u.contains("doubanio.com", true)
        fun open(): java.io.InputStream {
            val b = Request.Builder().url(u)
                .header("User-Agent", if (douban) "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15" else "okhttp/3.15.0")
            if (douban) b.header("Referer", "https://m.douban.com/")
            val resp = client.newCall(b.build()).execute()
            if (!resp.isSuccessful) { resp.close(); throw java.io.IOException("http ${resp.code}") }
            return resp.body!!.byteStream()
        }
        val w = try {
            open().use { s -> BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeStream(s, null, this) }.outWidth }
        } catch (e: Exception) { return null }
        if (w <= 0) return null
        var sample = 1
        while (w / sample > 240) sample *= 2
        return try { open().use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = sample }) } }
        catch (e: Exception) { null }
    }
}
