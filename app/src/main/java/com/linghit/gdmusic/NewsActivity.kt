package com.linghit.gdmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 今日热榜页：从首页「🎵 新闻热榜」信息卡进入。
 *
 * 数据源 = 今日头条热榜公开 JSON（hot-event/hot-board），字段：
 *   data[] { Title, Url, HotValue, Label }
 * Label 取值：hot(热) / new(新) / refuteRumors(辟谣) / onSite(现场)，空串=无标签。
 * 行点击 = 系统浏览器打开原文 Url；无浏览器时 toast 提示。
 *
 * 为什么用系统浏览器而不是内嵌 WebView：热榜原文在多平台域名（toutiao.com 等），
 * 内嵌 WebView 要处理各站点的 UA/ Cookie/ 跳转，收益不抵成本； ACTION_VIEW 最稳。
 */
class NewsActivity : AppCompatActivity() {

    private lateinit var newsList: RecyclerView
    private lateinit var newsEmpty: View
    private lateinit var newsAdapter: NewsAdapter

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_news)

        newsList = findViewById(R.id.newsList)
        newsEmpty = findViewById(R.id.newsEmpty)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshBtn).setOnClickListener { load() }

        newsAdapter = NewsAdapter { item ->
            val u = item.url
            if (u.isBlank()) {
                Toast.makeText(this, "这条没有原文链接", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                    .onFailure { Toast.makeText(this, "打不开，没找到浏览器", Toast.LENGTH_SHORT).show() }
            }
        }
        newsList.apply {
            layoutManager = LinearLayoutManager(this@NewsActivity)
            adapter = newsAdapter
        }
        load()
    }

    private fun load() {
        newsEmpty.visibility = View.GONE
        scope.launch {
            val items = try {
                withContext(Dispatchers.IO) { fetch() }
            } catch (e: Exception) { emptyList() }
            if (items.isEmpty()) {
                newsEmpty.visibility = View.VISIBLE
            } else {
                newsEmpty.visibility = View.GONE
                newsAdapter.submit(items)
            }
        }
    }

    /** 拉热榜（IO 线程）。最多取 50 条——一屏装不下，滚动加载意义不大 */
    private fun fetch(): List<NewsItem> {
        val r = Request.Builder()
            .url("https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc")
            .header("User-Agent", "GDMusicPlayer/3.0")
            .build()
        val body = client.newCall(r).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            it.body?.string() ?: ""
        }
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until minOf(arr.length(), 50)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("Title", "")
            if (t.isBlank()) return@mapNotNull null
            NewsItem(
                title = t,
                url = o.optString("Url", ""),
                hot = o.optString("HotValue", ""),
                label = o.optString("Label", "")
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    data class NewsItem(val title: String, val url: String, val hot: String, val label: String)

    inner class NewsAdapter(val onClick: (NewsItem) -> Unit) :
        RecyclerView.Adapter<NewsAdapter.VH>() {

        private val items = mutableListOf<NewsItem>()

        fun submit(list: List<NewsItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        inner class VH(private val root: View) : RecyclerView.ViewHolder(root) {
            private val idx = root.findViewById<TextView>(R.id.nIndex)
            private val title = root.findViewById<TextView>(R.id.nTitle)
            private val hot = root.findViewById<TextView>(R.id.nHot)
            private val badge = root.findViewById<TextView>(R.id.nLabel)

            fun bind(x: NewsItem, position: Int) {
                val ctx = root.context
                // 前三名用主题主色加权重，其余用中性弱化色
                val top = position < 3
                idx.setTextColor(
                    if (top) ThemePrefs.currentPack(ctx).primary
                    else ContextCompat.getColor(ctx, R.color.on_surface_variant)
                )
                idx.text = (position + 1).toString()
                title.text = x.title
                hot.text = hotText(x.hot)
                val l = labelText(x.label)
                badge.text = l
                badge.visibility = if (l.isBlank()) View.GONE else View.VISIBLE
                badge.setTextColor(ThemePrefs.currentPack(ctx).primary)
                root.setOnClickListener { onClick(x) }
            }
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_news, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position], position)
    }

    /** 热度显示：HotValue 是字符串数字，>10000 换算成「x.x 万热度」 */
    private fun hotText(raw: String): String {
        val v = raw.trim().toLongOrNull() ?: return ""
        return when {
            v >= 10000 -> String.format("%.1f 万热度", v / 10000.0)
            else -> "$v 热度"
        }
    }

    /** 头条 Label → 中文徽章文案 */
    private fun labelText(raw: String): String = when (raw) {
        "hot" -> "热"
        "new" -> "新"
        "refuteRumors" -> "辟谣"
        "onSite" -> "现场"
        else -> ""
    }
}
