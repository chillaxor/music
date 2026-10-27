package com.linghit.gdmusic

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
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
import java.util.concurrent.TimeUnit

/**
 * 播客详情页：拉取 RSS → 解析单集 → 列表展示（带已听进度）。
 * 点单集 = 临时播放（保留音乐队列，播完回到原队列）；全部播放 = 用单集列表替换队列顺序听。
 * 进度每 5 秒及退到后台时写入 PodcastStore，实现「听到哪、下次接着播」。
 */
class PodcastDetailActivity : AppCompatActivity() {

    private lateinit var rss: String
    private lateinit var podcastTitle: String
    private lateinit var coverUrl: String
    private lateinit var author: String

    private lateinit var episodeList: RecyclerView
    private lateinit var episodeEmpty: TextView
    private lateinit var adapter: EpisodeAdapter

    private var episodes: List<Episode> = emptyList()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val progHandler = Handler(Looper.getMainLooper())
    private val progRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            progHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_podcast_detail)

        rss = intent.getStringExtra("rss") ?: ""
        podcastTitle = intent.getStringExtra("title") ?: "播客"
        coverUrl = intent.getStringExtra("cover") ?: ""
        author = intent.getStringExtra("author") ?: ""

        episodeList = findViewById(R.id.episodeList)
        episodeEmpty = findViewById(R.id.episodeEmpty)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.titleBar).text = podcastTitle
        findViewById<TextView>(R.id.dTitle).text = podcastTitle
        findViewById<TextView>(R.id.dAuthor).text = author.ifBlank { "播客" }
        ImageLoader.load(findViewById(R.id.dCover), coverUrl)

        adapter = EpisodeAdapter(rss) { playEpisode(it) }
        episodeList.layoutManager = LinearLayoutManager(this)
        episodeList.adapter = adapter

        findViewById<TextView>(R.id.playAllBtn).setOnClickListener {
            if (episodes.isEmpty()) return@setOnClickListener
            val list = episodes.map { toSong(it) }
            PlayerManager.pendingSeekMs = 0L
            PlayerManager.playInternal(list[0], list, 0, Scene.PODCAST)
            Toast.makeText(this, "正在播放：$podcastTitle", Toast.LENGTH_SHORT).show()
        }

        fetch()
    }

    private fun fetch() {
        episodeEmpty.visibility = View.VISIBLE
        episodeEmpty.text = "单集加载中…"
        scope.launch {
            try {
                val xml = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder().url(rss).header("User-Agent", "GDMusicPlayer/3.0").build()
                    ).execute().use { it.body?.string() ?: "" }
                }
                if (xml.isBlank()) throw Exception("空内容")
                val meta = RssParser.parse(xml)
                episodes = meta.episodes
                withContext(Dispatchers.Main) {
                    if (episodes.isEmpty()) {
                        episodeEmpty.text = "这个播客暂时没有可播放的单集"
                    } else {
                        episodeEmpty.visibility = View.GONE
                        adapter.submit(episodes)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    episodeEmpty.visibility = View.VISIBLE
                    episodeEmpty.text = "单集加载失败：${e.message}"
                }
            }
        }
    }

    private fun toSong(ep: Episode): Song = Song(
        id = ep.guid,
        name = ep.title, artist = podcastTitle, album = podcastTitle,
        source = "", picId = "", lyricId = "", localUri = "",
        directUrl = ep.url, coverUrl = coverUrl
    )

    private fun playEpisode(ep: Episode) {
        val song = toSong(ep)
        val pos = PodcastStore.getProgress(this, rss, ep.guid)
        PlayerManager.pendingSeekMs = pos
        PlayerManager.playTemp(song, Scene.PODCAST)
        Toast.makeText(this, if (pos > 0) "接着听：${ep.title}" else "正在播放：${ep.title}", Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentProgress() {
        val cur = PlayerManager.current ?: return
        if (!cur.directUrl.startsWith("http")) return
        val ep = episodes.firstOrNull { it.url == cur.directUrl } ?: return
        val pos = runCatching { PlayerManager.getPlayer().currentPosition }.getOrNull() ?: return
        if (pos > 0) PodcastStore.setProgress(this, rss, ep.guid, pos)
    }

    override fun onResume() {
        super.onResume()
        // 回来时刷新进度条（可能刚听了一段）
        if (episodes.isNotEmpty()) adapter.notifyDataSetChanged()
        progHandler.postDelayed(progRunnable, 5000)
    }

    override fun onPause() {
        progHandler.removeCallbacks(progRunnable)
        saveCurrentProgress()
        super.onPause()
    }

    override fun onDestroy() {
        progHandler.removeCallbacks(progRunnable)
        scope.cancel()
        super.onDestroy()
    }

    inner class EpisodeAdapter(private val rss: String, private val onClick: (Episode) -> Unit) :
        RecyclerView.Adapter<EpisodeAdapter.VH>() {
        private var items: List<Episode> = emptyList()
        fun submit(l: List<Episode>) { items = l; notifyDataSetChanged() }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title = v.findViewById<TextView>(R.id.eTitle)
            val meta = v.findViewById<TextView>(R.id.eMeta)
            val state = v.findViewById<TextView>(R.id.eState)
            val progress = v.findViewById<ProgressBar>(R.id.eProgress)
            fun bind(x: Episode) {
                title.text = x.title
                val dur = formatDuration(x.durationMs)
                meta.text = buildString {
                    if (dur.isNotBlank()) append(dur)
                    if (x.pubDate.isNotBlank()) append(if (isNotEmpty()) " · " else "").append(x.pubDate)
                }
                val pos = PodcastStore.getProgress(itemView.context, rss, x.guid)
                val pct = if (x.durationMs > 0) (pos * 1000 / x.durationMs).toInt().coerceIn(0, 1000) else 0
                progress.progress = pct
                state.text = if (pos > 0) "已听 ${pct / 10}%" else ""
                itemView.setOnClickListener { onClick(x) }
            }
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i])
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
