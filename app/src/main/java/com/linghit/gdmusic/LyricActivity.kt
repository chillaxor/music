package com.linghit.gdmusic

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 全屏歌词页。
 * 歌词数据唯一来源是 PlayerManager（内嵌 → 伴生 .lrc → 在线 API 三层查找的结果），
 * 本页只负责展示与交互：当前行高亮、自动滚动、点行跳转播放、无歌词空态重试。
 */
class LyricActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var retryView: TextView
    private lateinit var backBtn: ImageButton
    private lateinit var list: RecyclerView
    private lateinit var plainScroll: ScrollView
    private lateinit var plainText: TextView
    private lateinit var emptyView: LinearLayout
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var playPauseBtn: ImageButton

    private lateinit var adapter: LineAdapter
    private val handler = Handler(Looper.getMainLooper())

    private var lastSongKey = ""      // 当前展示的是哪首歌（变了才重载歌词）
    private var curIdx = -1           // 当前高亮行
    private var autoScroll = true     // 用户手动滑列表后暂停自动滚动 3 秒
    private var reloadTried = false   // 每首歌只自动重试一次，避免无网时反复打接口

    private val tick = object : Runnable {
        override fun run() {
            syncSong()
            syncIndex()
            syncPlayIcon()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_lyric)

        titleView = findViewById(R.id.lyricTitle)
        artistView = findViewById(R.id.lyricArtist)
        retryView = findViewById(R.id.lyricRetry)
        backBtn = findViewById(R.id.lyricBack)
        list = findViewById(R.id.lyricList)
        plainScroll = findViewById(R.id.lyricPlainScroll)
        plainText = findViewById(R.id.lyricPlain)
        emptyView = findViewById(R.id.lyricEmpty)
        prevBtn = findViewById(R.id.lyricPrev)
        nextBtn = findViewById(R.id.lyricNext)
        playPauseBtn = findViewById(R.id.lyricPlayPause)

        adapter = LineAdapter { timeMs ->
            PlayerManager.seekTo(timeMs)
            autoScroll = true
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // 用户手动滑列表：暂停自动滚动，松手 3 秒后恢复
        list.setOnTouchListener { _, _ ->
            autoScroll = false
            handler.removeCallbacks(resumeAuto)
            handler.postDelayed(resumeAuto, 3000)
            false
        }

        backBtn.setOnClickListener { finish() }
        retryView.setOnClickListener {
            reloadTried = false
            PlayerManager.reloadLyrics()
            Toast.makeText(this, "正在重新获取歌词", Toast.LENGTH_SHORT).show()
        }
        prevBtn.setOnClickListener { PlayerManager.skipPrev() }
        nextBtn.setOnClickListener { PlayerManager.skipNext() }
        playPauseBtn.setOnClickListener { PlayerManager.togglePlayPause() }

        if (PlayerManager.current == null) {
            Toast.makeText(this, "还没有在播放的歌曲", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val resumeAuto = Runnable { autoScroll = true }

    override fun onResume() {
        super.onResume()
        lastSongKey = ""
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(resumeAuto)
    }

    /** 切歌检测 + 歌词内容装载 */
    private fun syncSong() {
        val song = PlayerManager.current ?: return
        val key = song.key()
        if (key == lastSongKey) return
        lastSongKey = key
        reloadTried = false
        titleView.text = song.name
        artistView.text = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
        curIdx = -1
        val lines = PlayerManager.lyricLines
        val raw = PlayerManager.lyricRaw
        adapter.submit(lines)
        when {
            lines.isNotEmpty() -> {
                list.visibility = View.VISIBLE
                plainScroll.visibility = View.GONE
                emptyView.visibility = View.GONE
                retryView.visibility = View.GONE
            }
            raw.isNotBlank() -> {
                // 纯文本歌词（无时间戳）：整段展示，不做高亮
                list.visibility = View.GONE
                plainScroll.visibility = View.VISIBLE
                emptyView.visibility = View.GONE
                retryView.visibility = View.GONE
                plainText.text = raw
            }
            else -> {
                list.visibility = View.GONE
                plainScroll.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                retryView.visibility = View.VISIBLE
                // 队列恢复进来的场景可能还没加载过歌词：自动拉一次
                if (!reloadTried) { reloadTried = true; PlayerManager.reloadLyrics() }
            }
        }
    }

    /** 当前行高亮 + 自动滚动到视线黄金位（屏高 1/3 处） */
    private fun syncIndex() {
        if (adapter.itemCount == 0) return
        val idx = PlayerManager.lyricIndexAt(PlayerManager.getPlayer().currentPosition)
        if (idx == curIdx) return
        curIdx = idx
        adapter.setCurrent(idx)
        if (autoScroll && idx >= 0) {
            val lm = list.layoutManager as? LinearLayoutManager ?: return
            lm.scrollToPositionWithOffset(idx, list.height / 3)
        }
    }

    private fun syncPlayIcon() {
        playPauseBtn.setImageResource(
            if (PlayerManager.getPlayer().isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private class LineAdapter(private val onLineClick: (Long) -> Unit) :
        RecyclerView.Adapter<LineAdapter.VH>() {
        private val data = mutableListOf<LrcLine>()
        private var cur = -1

        fun submit(l: List<LrcLine>) { data.clear(); data.addAll(l); cur = -1; notifyDataSetChanged() }
        fun setCurrent(i: Int) {
            val old = cur
            cur = i
            if (old in data.indices) notifyItemChanged(old)
            if (i in data.indices) notifyItemChanged(i)
        }

        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, v: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_lyric_line, p, false))

        override fun onBindViewHolder(h: VH, p: Int) = h.bind(data[p], p == cur, onLineClick)

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tv = v.findViewById<TextView>(R.id.lyricLineText)
            fun bind(line: LrcLine, isCur: Boolean, onClick: (Long) -> Unit) {
                tv.text = line.text
                tv.setTextColor(
                    ContextCompat.getColor(
                        tv.context,
                        if (isCur) R.color.primary else R.color.on_surface_variant
                    )
                )
                tv.textSize = if (isCur) 18f else 15f
                tv.typeface = android.graphics.Typeface.defaultFromStyle(
                    if (isCur) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                tv.setOnClickListener { onClick(line.timeMs) }
            }
        }
    }
}
