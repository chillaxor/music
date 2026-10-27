package com.linghit.gdmusic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 播客电台主页（独立内容线，不耦合音乐搜索）。
 * 订阅 Tab：Apple 播客目录搜索 → 订阅 → 进集列表；电台 Tab：radio-browser 分类/搜索 → 即点即播。
 */
class PodcastActivity : AppCompatActivity() {

    private lateinit var subPanel: View
    private lateinit var radioPanel: LinearLayout
    private lateinit var subList: RecyclerView
    private lateinit var resultList: RecyclerView
    private lateinit var hotList: RecyclerView
    private lateinit var stationList: RecyclerView
    private lateinit var subEmpty: TextView
    private lateinit var stationEmpty: TextView
    private lateinit var radioChips: LinearLayout

    private lateinit var subAdapter: SubAdapter
    private lateinit var resultAdapter: ResultAdapter
    private lateinit var hotAdapter: ResultAdapter
    private lateinit var stationAdapter: StationAdapter
    private var radioLoaded = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_podcast)

        subPanel = findViewById(R.id.subPanel)
        radioPanel = findViewById(R.id.radioPanel)
        subList = findViewById(R.id.subList)
        resultList = findViewById(R.id.resultList)
        hotList = findViewById(R.id.hotList)
        stationList = findViewById(R.id.stationList)
        subEmpty = findViewById(R.id.subEmpty)
        stationEmpty = findViewById(R.id.stationEmpty)
        radioChips = findViewById(R.id.radioChips)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tabSub).setOnClickListener { switchTab(false) }
        findViewById<TextView>(R.id.tabRadio).setOnClickListener { switchTab(true) }

        subAdapter = SubAdapter { openDetail(it.rssUrl, it.title, it.coverUrl, it.author) }
        resultAdapter = ResultAdapter(onSub = { subscribeAndOpen(it) })
        hotAdapter = ResultAdapter(onSub = { subscribeAndOpen(it) }, layout = R.layout.item_podcast_hot)
        hotList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        hotList.adapter = hotAdapter
        stationAdapter = StationAdapter { playStation(it) }

        subList.layoutManager = LinearLayoutManager(this)
        resultList.layoutManager = LinearLayoutManager(this)
        stationList.layoutManager = LinearLayoutManager(this)
        subList.adapter = subAdapter
        resultList.adapter = resultAdapter
        stationList.adapter = stationAdapter

        findViewById<TextView>(R.id.podcastSearchBtn).setOnClickListener {
            val q = findViewById<EditText>(R.id.podcastSearch).text.toString().trim()
            if (q.isNotBlank()) searchPodcasts(q)
        }
        findViewById<TextView>(R.id.radioSearchBtn).setOnClickListener {
            val q = findViewById<EditText>(R.id.radioSearch).text.toString().trim()
            if (q.isNotBlank()) loadStations(null, q)
        }

        loadSubs()
        loadTags()
        loadHotPodcasts()
    }

    private fun switchTab(toRadio: Boolean) {
        subPanel.visibility = if (toRadio) View.GONE else View.VISIBLE
        radioPanel.visibility = if (toRadio) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tabSub).setTextColor(
            getColor(if (toRadio) R.color.on_surface_variant else R.color.primary)
        )
        findViewById<TextView>(R.id.tabRadio).setTextColor(
            getColor(if (toRadio) R.color.primary else R.color.on_surface_variant)
        )
        findViewById<View>(R.id.tabSubLine).visibility = if (toRadio) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tabRadioLine).visibility = if (toRadio) View.VISIBLE else View.GONE
        if (toRadio && !radioLoaded) { radioLoaded = true; loadStations() }
    }

    private fun loadSubs() {
        val subs = PodcastStore.subs(this)
        subAdapter.submit(subs)
        subEmpty.visibility = if (subs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun searchPodcasts(q: String) {
        val enc = URLEncoder.encode(q, "UTF-8")
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url("https://itunes.apple.com/search?media=podcast&term=$enc&limit=20&country=CN")
                            .header("User-Agent", "GDMusicPlayer/3.0").build()
                    ).execute().use { it.body?.string() ?: "" }
                }
                val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
                val list = mutableListOf<PodcastResult>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val feed = o.optString("feedUrl")
                    if (feed.isBlank()) continue
                    list.add(
                        PodcastResult(
                            rssUrl = feed,
                            title = o.optString("collectionName", "未命名播客"),
                            author = o.optString("artistName", ""),
                            coverUrl = o.optString("artworkUrl100", "").replace("100x100", "300x300")
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    if (list.isEmpty()) Toast.makeText(this@PodcastActivity, "没搜到相关播客", Toast.LENGTH_SHORT).show()
                    else resultAdapter.submit(list)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PodcastActivity, "搜索失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 进订阅页自动加载热门播客（Apple top feed → 批量 lookup 补出真实 feedUrl），零搜索门槛 */
    private fun loadHotPodcasts() {
        scope.launch {
            try {
                val (hots, ids) = withContext(Dispatchers.IO) {
                    val body = client.newCall(
                        Request.Builder()
                            .url("https://itunes.apple.com/cn/rss/toppodcasts/limit=20/genre=26/json")
                            .header("User-Agent", "GDMusicPlayer/3.0").build()
                    ).execute().use { it.body?.string() ?: "" }
                    val arr = JSONObject(body).optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()
                    val hots = mutableListOf<PodcastResult>()
                    val ids = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optJSONObject("im:name")?.optString("label") ?: continue
                        val author = o.optJSONObject("im:artist")?.optString("label") ?: ""
                        val imgs = o.optJSONArray("im:image")
                        val cover = if (imgs != null && imgs.length() > 0)
                            imgs.getJSONObject(imgs.length() - 1).optString("label").replace("170x170", "300x300")
                        else ""
                        val id = o.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id") ?: ""
                        hots.add(PodcastResult(rssUrl = "", title = name, author = author, coverUrl = cover))
                        ids.add(id)
                    }
                    Pair(hots, ids)
                }
                if (ids.isEmpty()) return@launch
                val feedMap = withContext(Dispatchers.IO) {
                    val lookup = client.newCall(
                        Request.Builder()
                            .url("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&country=CN")
                            .header("User-Agent", "GDMusicPlayer/3.0").build()
                    ).execute().use { it.body?.string() ?: "" }
                    val res = JSONObject(lookup).optJSONArray("results") ?: JSONArray()
                    val m = mutableMapOf<String, String>()
                    for (i in 0 until res.length()) {
                        val o = res.getJSONObject(i)
                        val cid = o.optString("collectionId", "")
                        val f = o.optString("feedUrl", "")
                        if (cid.isNotBlank() && f.isNotBlank()) m[cid] = f
                    }
                    m
                }
                val finalList = hots.mapIndexedNotNull { i, h ->
                    val f = feedMap[ids[i]] ?: return@mapIndexedNotNull null
                    h.copy(rssUrl = f)
                }
                withContext(Dispatchers.Main) { if (finalList.isNotEmpty()) hotAdapter.submit(finalList) }
            } catch (e: Exception) { /* 热门加载失败不影响其它功能 */ }
        }
    }

    private fun subscribeAndOpen(r: PodcastResult) {
        PodcastStore.addSub(this, PodcastStore.Sub(r.rssUrl, r.title, r.author, r.coverUrl, ""))
        loadSubs()
        openDetail(r.rssUrl, r.title, r.coverUrl, r.author)
    }

    private fun openDetail(rssUrl: String, title: String, coverUrl: String, author: String) {
        startActivity(Intent(this, PodcastDetailActivity::class.java).apply {
            putExtra("rss", rssUrl)
            putExtra("title", title)
            putExtra("cover", coverUrl)
            putExtra("author", author)
        })
    }

    // ---------------- 电台 ----------------

    private fun loadTags() {
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url("https://de1.api.radio-browser.info/json/tags?limit=24&order=stationcount&reverse=true")
                            .header("User-Agent", "GDMusicPlayer/3.0").build()
                    ).execute().use { it.body?.string() ?: "" }
                }
                val arr = JSONArray(body)
                val tags = mutableListOf<String>()
                for (i in 0 until arr.length()) tags.add(arr.getJSONObject(i).optString("name"))
                withContext(Dispatchers.Main) { renderChips(tags) }
            } catch (e: Exception) { /* 分类加载失败不影响电台搜索 */ }
        }
    }

    private fun renderChips(tags: List<String>) {
        radioChips.removeAllViews()
        tags.filter { it.isNotBlank() }.forEach { tag ->
            val chip = TextView(this).apply {
                text = tag
                textSize = 13f
                setTextColor(getColor(R.color.on_surface))
                setBackgroundResource(R.color.surface_variant)
                setPadding(24, 12, 24, 12)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 10, 0) }
                layoutParams = lp
                setOnClickListener { loadStations(tag, null) }
            }
            radioChips.addView(chip)
        }
    }

    private fun loadStations(tag: String? = null, nameQuery: String? = null) {
        stationEmpty.visibility = View.VISIBLE
        stationEmpty.text = "正在加载电台…"
        val url = when {
            nameQuery != null ->
                "https://de1.api.radio-browser.info/json/stations/search?name=${URLEncoder.encode(nameQuery, "UTF-8")}&limit=50&hidebroken=true&order=clickcount&reverse=true"
            tag != null ->
                "https://de1.api.radio-browser.info/json/stations/search?tag=${URLEncoder.encode(tag, "UTF-8")}&limit=50&hidebroken=true&order=clickcount&reverse=true"
            else ->
                "https://de1.api.radio-browser.info/json/stations/topclick?limit=50&hidebroken=true"
        }
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder().url(url).header("User-Agent", "GDMusicPlayer/3.0").build()
                    ).execute().use { it.body?.string() ?: "" }
                }
                val arr = JSONArray(body)
                val list = mutableListOf<Station>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.optString("url_resolved").ifBlank { o.optString("url") }
                    if (u.isBlank()) continue
                    list.add(
                        Station(
                            name = o.optString("name", "未知电台"),
                            url = u,
                            favicon = o.optString("favicon", ""),
                            country = o.optString("countrycode", ""),
                            bitrate = o.optInt("bitrate", 0)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    if (list.isEmpty()) {
                        stationEmpty.text = "没有找到电台"
                    } else {
                        stationEmpty.visibility = View.GONE
                        stationAdapter.submit(list)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stationEmpty.text = "电台加载失败：${e.message}"
                }
            }
        }
    }

    private fun playStation(st: Station) {
        val song = Song(
            id = st.url,
            name = st.name, artist = st.country, album = "电台",
            source = "", picId = "", lyricId = "", localUri = "",
            directUrl = st.url, coverUrl = st.favicon
        )
        PlayerManager.pendingSeekMs = 0L
        PlayerManager.playTemp(song, Scene.RADIO)
        Toast.makeText(this, "正在播放：${st.name}", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    data class PodcastResult(
        val rssUrl: String, val title: String, val author: String, val coverUrl: String
    )

    data class Station(
        val name: String, val url: String, val favicon: String, val country: String, val bitrate: Int
    )

    // ---------------- Adapters ----------------

    inner class SubAdapter(private val onClick: (PodcastStore.Sub) -> Unit) :
        RecyclerView.Adapter<SubAdapter.VH>() {
        private val items = mutableListOf<PodcastStore.Sub>()
        fun submit(l: List<PodcastStore.Sub>) { items.clear(); items.addAll(l); notifyDataSetChanged() }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover = v.findViewById<ImageView>(R.id.pCover)
            val title = v.findViewById<TextView>(R.id.pTitle)
            val sub = v.findViewById<TextView>(R.id.pSub)
            val action = v.findViewById<TextView>(R.id.pAction)
            fun bind(x: PodcastStore.Sub) {
                ImageLoader.load(cover, x.coverUrl)
                title.text = x.title
                sub.text = x.author.ifBlank { "播客" }
                action.text = "›"
                itemView.setOnClickListener { onClick(x) }
            }
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_podcast, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i])
    }

    inner class ResultAdapter(
        private val onSub: (PodcastResult) -> Unit,
        private val layout: Int = R.layout.item_podcast
    ) :
        RecyclerView.Adapter<ResultAdapter.VH>() {
        private val items = mutableListOf<PodcastResult>()
        fun submit(l: List<PodcastResult>) { items.clear(); items.addAll(l); notifyDataSetChanged() }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover = v.findViewById<ImageView>(R.id.pCover)
            val title = v.findViewById<TextView>(R.id.pTitle)
            val sub = v.findViewById<TextView>(R.id.pSub)
            val action = v.findViewById<TextView?>(R.id.pAction)
            fun bind(x: PodcastResult) {
                ImageLoader.load(cover, x.coverUrl)
                title.text = x.title
                sub.text = x.author.ifBlank { "播客" }
                action?.text = "订阅"
                itemView.setOnClickListener { onSub(x) }
            }
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(layout, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i])
    }

    inner class StationAdapter(private val onClick: (Station) -> Unit) :
        RecyclerView.Adapter<StationAdapter.VH>() {
        private val items = mutableListOf<Station>()
        fun submit(l: List<Station>) { items.clear(); items.addAll(l); notifyDataSetChanged() }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon = v.findViewById<ImageView>(R.id.sIcon)
            val name = v.findViewById<TextView>(R.id.sName)
            val meta = v.findViewById<TextView>(R.id.sMeta)
            fun bind(x: Station) {
                ImageLoader.load(icon, x.favicon)
                name.text = x.name
                meta.text = buildString {
                    if (x.country.isNotBlank()) append(x.country)
                    if (x.bitrate > 0) append(if (isNotEmpty()) " · " else "").append("${x.bitrate}kbps")
                    if (isEmpty()) append("电台")
                }
                itemView.setOnClickListener { onClick(x) }
            }
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_station, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i])
    }
}
