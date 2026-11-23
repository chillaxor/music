package com.linghit.gdmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生影视搜索页（与 WebView 版 VideoActivity 并存的速度对照版）。
 *
 * 差别只在「搜索链路」：不经过 WebView / JS 桥 / Base64 注入，OkHttp 拿到 CMS JSON
 * 直接用 org.json 解析，12 路并发，**每个站点一回来就往列表里插**（不等最慢的源），
 * 海报走 VideoImg 的内存 LruCache。详情、选集、起播仍复用现有原生播放器，
 * 通过 VideoActivity.EXTRA_NATIVE_PLAY 借壳，避免复制 3000 行播放逻辑。
 */
class NativeVideoActivity : AppCompatActivity() {

    private data class SiteInfo(val name: String, val api: String, val key: String)
    private data class Vod(
        val id: String, val name: String, val pic: String, val year: String,
        val area: String, val typeName: String, val remarks: String,
        val siteName: String, val api: String
    )
    private data class EpGroup(val from: String, val eps: List<Pair<String, String>>)

    private lateinit var kw: EditText
    private lateinit var stat: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: VodAdapter

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newFixedThreadPool(12) { r ->
        Thread(r, "native-search").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var searchSeq = 0
    private var startedAt = 0L
    private var firstAt = 0L
    private var doneCnt = 0
    private var totalCnt = 0
    private var hitCnt = 0
    private var resultCnt = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_native_video)

        kw = findViewById(R.id.nvKw)
        stat = findViewById(R.id.nvStat)
        empty = findViewById(R.id.nvEmpty)
        adapter = VodAdapter { openDetail(it) }
        findViewById<RecyclerView>(R.id.nvList).apply {
            layoutManager = GridLayoutManager(this@NativeVideoActivity, 2)
            adapter = this@NativeVideoActivity.adapter
        }
        findViewById<View>(R.id.nvBack).setOnClickListener { finish() }
        findViewById<View>(R.id.nvSearch).setOnClickListener { doSearch() }
        kw.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        findViewById<View>(R.id.nvSwitch).setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java))
            finish()
        }
        // adb 直启可带 kw，方便 A/B 计时：am start -n .../.NativeVideoActivity --es kw 庆余年
        // 只在首次创建时自动搜：Activity 重建（配置变化等）时 b != null，重复搜会白跑一轮
        if (b == null) {
            intent.getStringExtra("kw")?.takeIf { it.isNotBlank() }?.let {
                kw.setText(it)
                doSearch()
            }
        }
    }

    /** 订阅配置沿用老版那两个默认仓：从 video_sources 读用户源，映射到内置快照 JSON */
    private fun loadSites(): List<SiteInfo> {
        val prefs = getSharedPreferences("video_sources", MODE_PRIVATE)
        val raw = prefs.getString("sources", null) ?: DEFAULT_SOURCES
        val out = ArrayList<SiteInfo>()
        val seen = HashSet<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                val asset = if (url.contains("摸鱼") || url.contains("moyu", true))
                    "tvbox/subs/moyu.json" else "tvbox/subs/box.json"
                val cfg = JSONObject(assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() })
                val sites = cfg.optJSONArray("sites") ?: continue
                for (j in 0 until sites.length()) {
                    val s = sites.optJSONObject(j) ?: continue
                    val type = s.optInt("type", -1)
                    val api = s.optString("api")
                    if (type !in 0..1 || !api.startsWith("http", true)) continue
                    if (!seen.add(api)) continue
                    val name = s.optString("name").ifBlank { s.optString("key") }
                    out.add(SiteInfo(name, api, s.optString("key")))
                }
            }
        } catch (e: Exception) {
            Log.w("VidNative", "loadSites 失败: ${e.message}")
        }
        return out
    }

    private fun doSearch() {
        val w = kw.text.toString().trim()
        if (w.isBlank()) {
            Toast.makeText(this, "先输入片名", Toast.LENGTH_SHORT).show()
            return
        }
        val seq = ++searchSeq
        adapter.clear()
        empty.visibility = View.GONE
        startedAt = SystemClock.elapsedRealtime()
        firstAt = 0L; doneCnt = 0; hitCnt = 0; resultCnt = 0
        val sites = loadSites()
        totalCnt = sites.size
        stat.text = "正在并发搜索 $totalCnt 个直连站…"
        Log.i("VidNative", "search start w=$w sites=$totalCnt seq=$seq t=$startedAt")
        for (s in sites) {
            pool.execute {
                if (seq != searchSeq) return@execute
                val items = searchSite(s, w)
                main.post {
                    if (seq != searchSeq) return@post
                    doneCnt++
                    if (items.isNotEmpty()) {
                        hitCnt++
                        if (firstAt == 0L) {
                            firstAt = SystemClock.elapsedRealtime() - startedAt
                            Log.i("VidNative", "FIRST RESULT ${firstAt}ms from ${s.name}")
                        }
                        resultCnt += items.size
                        adapter.append(items)
                        empty.visibility = View.GONE
                    }
                    val first = if (firstAt > 0) " · 首条 ${firstAt}ms" else ""
                    stat.text = "已搜 $doneCnt/$totalCnt 源 · $hitCnt 源有结果 · $resultCnt 条$first"
                    Log.d("VidNative", "site ${s.name} -> ${items.size} 条 ($doneCnt/$totalCnt)")
                    if (doneCnt == totalCnt) {
                        val total = SystemClock.elapsedRealtime() - startedAt
                        stat.text = "完成 · $totalCnt 源 / $resultCnt 条 · 首条 ${firstAt}ms · 全部 ${total}ms"
                        Log.i("VidNative", "search DONE seq=$seq first=${firstAt}ms total=${total}ms hits=$hitCnt items=$resultCnt")
                        if (resultCnt == 0) empty.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun searchSite(s: SiteInfo, w: String): List<Vod> {
        val sep = if (s.api.contains("?")) "&" else "?"
        val url = s.api + sep + "ac=videolist&wd=" + URLEncoder.encode(w, "UTF-8") + "&pg=1"
        val req = Request.Builder().url(url)
            .header("User-Agent", "okhttp/3.15.0")
            .header("Accept", "*/*").build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("list")
                    ?: return emptyList()
                val out = ArrayList<Vod>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("vod_id")
                    val name = o.optString("vod_name")
                    if (id.isBlank() || name.isBlank()) continue
                    out.add(
                        Vod(
                            id, name, o.optString("vod_pic"), o.optString("vod_year"),
                            o.optString("vod_area"),
                            o.optString("vod_type_name").ifBlank { o.optString("vod_class") },
                            o.optString("vod_remarks"), s.name, s.api
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            Log.w("VidNative", "search fail ${s.name}: ${e.message}")
            emptyList()
        }
    }

    private fun openDetail(v: Vod) {
        stat.text = "正在加载详情：${v.name}"
        pool.execute {
            val d = try {
                val sep = if (v.api.contains("?")) "&" else "?"
                val url = v.api + sep + "ac=detail&ids=" + URLEncoder.encode(v.id, "UTF-8")
                val req = Request.Builder().url(url).header("User-Agent", "okhttp/3.15.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else {
                        val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("list")
                        if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
                    }
                }
            } catch (e: Exception) {
                Log.w("VidNative", "detail fail: ${e.message}")
                null
            }
            main.post {
                if (d == null) {
                    Toast.makeText(this, "详情加载失败", Toast.LENGTH_SHORT).show()
                } else {
                    showDetail(v, d)
                }
            }
        }
    }

    private fun showDetail(v: Vod, d: JSONObject) {
        val groups = parsePlay(d)
        val dialog = BottomSheetDialog(this)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(box)

        box.addView(TextView(this).apply {
            text = d.optString("vod_name").ifBlank { v.name }
            textSize = 18f
            setTextColor(attrColor(R.attr.gdTextPrimary))
        })
        val desc = d.optString("vod_content").replace(Regex("<[^>]+>"), " ").trim()
        if (desc.isNotBlank()) box.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(attrColor(R.attr.gdTextSecondary))
            setPadding(0, 8, 0, 8)
            maxLines = 3
        })

        if (groups.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "该资源没有可播放的集数"
                textSize = 14f
                setTextColor(attrColor(R.attr.gdTextSecondary))
                setPadding(0, 24, 0, 24)
            })
        } else {
            val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tabScroll = HorizontalScrollView(this).apply { addView(tabs) }
            val epsGrid = GridLayout(this).apply { columnCount = 5 }
            box.addView(tabScroll)
            box.addView(epsGrid)
            fun select(gi: Int) {
                for (i in 0 until tabs.childCount) {
                    tabs.getChildAt(i).alpha = if (i == gi) 1f else 0.5f
                }
                epsGrid.removeAllViews()
                val g = groups[gi]
                g.eps.forEachIndexed { ei, ep ->
                    val b = TextView(this).apply {
                        text = ep.first.ifBlank { "第${ei + 1}集" }
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setTextColor(attrColor(R.attr.gdTextPrimary))
                        setBackgroundResource(android.R.drawable.btn_default)
                        setPadding(6, 14, 6, 14)
                    }
                    b.setOnClickListener { play(v, g, ei, ep); dialog.dismiss() }
                    epsGrid.addView(b)
                }
            }
            groups.forEachIndexed { i, g ->
                val t = TextView(this).apply {
                    text = "${g.from} (${g.eps.size})"
                    textSize = 13f
                    setTextColor(attrColor(R.attr.gdTextPrimary))
                    setPadding(16, 10, 16, 10)
                }
                t.setOnClickListener { select(i) }
                tabs.addView(t)
            }
            select(0)
        }
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun parsePlay(v: JSONObject): List<EpGroup> {
        val rawFrom = v.optString("vod_play_from")
        val froms = if (rawFrom.contains("$$$")) rawFrom.split("$$$") else rawFrom.split(",")
        val segs = v.optString("vod_play_url").split("$$$")
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

    private fun play(v: Vod, g: EpGroup, idx: Int, ep: Pair<String, String>) {
        val eps = JSONArray().apply {
            g.eps.forEach { (n, u) -> put(JSONObject().put("name", n).put("url", u).put("spider", false)) }
        }.toString()
        val referer = try {
            val u = Uri.parse(v.api)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) { "" }
        val meta = JSONObject()
            .put("key", "d:${v.api}|${v.id}")
            .put("pic", v.pic)
            .put("epName", ep.first)
            .put("vodId", v.id)
            .put("siteApi", v.api)
            .put("from", g.from)
            .toString()
        val json = JSONObject()
            .put("url", ep.second)
            .put("title", "${v.name} · ${g.from} · ${ep.first}")
            .put("referer", referer)
            .put("eps", eps)
            .put("idx", idx)
            .put("meta", meta)
            .toString()
        val i = Intent(this, VideoActivity::class.java)
        i.putExtra(VideoActivity.EXTRA_NATIVE_PLAY, json)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }

    private fun attrColor(attr: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, android.graphics.Color.WHITE)
        ta.recycle()
        return c
    }

    private class VodAdapter(private val onClick: (Vod) -> Unit) :
        RecyclerView.Adapter<VodAdapter.VH>() {
        private val data = ArrayList<Vod>()
        fun clear() { data.clear(); notifyDataSetChanged() }
        fun append(l: List<Vod>) {
            val s = data.size
            data.addAll(l)
            notifyItemRangeInserted(s, l.size)
        }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_native_video, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(data[p])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v.findViewById<View>(R.id.nvRoot)
            private val pic = v.findViewById<ImageView>(R.id.nvPic)
            private val title = v.findViewById<TextView>(R.id.nvTitle)
            private val meta = v.findViewById<TextView>(R.id.nvMeta)
            private val site = v.findViewById<TextView>(R.id.nvSite)
            fun bind(x: Vod) {
                title.text = x.name
                meta.text = listOf(x.year, x.area, x.typeName, x.remarks)
                    .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "影视资源" }
                site.text = x.siteName
                VideoImg.into(pic, x.pic, R.drawable.bg_his_pic)
                root.setOnClickListener { onClick(x) }
            }
        }
    }

    companion object {
        private const val DEFAULT_SOURCES =
            "[{\"name\":\"盒子迷多仓\",\"url\":\"https://盒子迷.top/禁止贩卖\"}," +
                "{\"name\":\"盒子迷动漫仓\",\"url\":\"https://盒子迷.top/摸鱼\"}]"
    }
}
