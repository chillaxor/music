package com.linghit.gdmusic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放历史页：列出影视仓里看过的视频（带播放进度），点一条断点续播。
 * 记录由 VideoActivity 起播/切集/退出时写入 VideoHistoryStore。
 */
class VideoHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HisAdapter
    private lateinit var emptyText: TextView
    private lateinit var tipText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_video_history)

        emptyText = findViewById(R.id.emptyText)
        tipText = findViewById(R.id.tipText)

        adapter = HisAdapter(
            onResume = { resume(it) },
            onDelete = { rec ->
                AlertDialog.Builder(this)
                    .setTitle("删除这条记录？")
                    .setMessage(rec.title.ifBlank { "未知视频" })
                    .setPositiveButton("删除") { _, _ ->
                        VideoHistoryStore.remove(this, rec.key)
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        findViewById<RecyclerView>(R.id.hisList).apply {
            layoutManager = LinearLayoutManager(this@VideoHistoryActivity)
            adapter = this@VideoHistoryActivity.adapter
        }
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.clearBtn).setOnClickListener {
            if (adapter.itemCount == 0) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("清空播放历史")
                .setMessage("所有观看记录都会删除，不影响的下载和收藏。")
                .setPositiveButton("清空") { _, _ ->
                    VideoHistoryStore.clearAll(this)
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // 播放中位置一直在变，回来刷新进度
    }

    private fun refresh() {
        val items = VideoHistoryStore.list(this)
        adapter.submit(items)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        tipText.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<RecyclerView>(R.id.hisList).visibility =
            if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 断点续播：复用已打开的影视仓实例（CLEAR_TOP|SINGLE_TOP），带上记录 JSON */
    private fun resume(rec: VideoHistoryStore.Rec) {
        val it = Intent(this, VideoActivity::class.java)
        it.putExtra("resume_json", rec.resumeJson())
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(it)
        finish()
    }

    class HisAdapter(
        private val onResume: (VideoHistoryStore.Rec) -> Unit,
        private val onDelete: (VideoHistoryStore.Rec) -> Unit
    ) : RecyclerView.Adapter<HisAdapter.VH>() {
        private val data = mutableListOf<VideoHistoryStore.Rec>()
        fun submit(l: List<VideoHistoryStore.Rec>) { data.clear(); data.addAll(l); notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, v: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_video_history, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(data[p])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v.findViewById<View>(R.id.hisRoot)
            private val pic = v.findViewById<ImageView>(R.id.hisPic)
            private val picPh = v.findViewById<TextView>(R.id.hisPicPh)
            private val title = v.findViewById<TextView>(R.id.hisTitle)
            private val meta = v.findViewById<TextView>(R.id.hisMeta)
            private val prog = v.findViewById<ProgressBar>(R.id.hisProg)
            private val progTxt = v.findViewById<TextView>(R.id.hisProgTxt)
            private val del = v.findViewById<TextView>(R.id.hisDel)
            fun bind(x: VideoHistoryStore.Rec) {
                title.text = x.title.ifBlank { "未知视频" }
                meta.text = listOf(x.epName, x.from).filter { it.isNotBlank() }
                    .joinToString(" · ").ifBlank { "影视仓" }
                VideoImg.into(pic, x.pic, R.drawable.bg_his_pic)
                picPh.visibility = if (x.pic.isBlank()) View.VISIBLE else View.GONE
                val pct = if (x.durationMs > 0)
                    (x.positionMs * 100 / x.durationMs).toInt().coerceIn(0, 100) else 0
                prog.progress = pct
                progTxt.text = when {
                    x.watched() -> "已看完 · 点此从头看"
                    x.durationMs > 0 -> "看到 ${fmt(x.positionMs)} / ${fmt(x.durationMs)}"
                    x.positionMs > 0 -> "看到 ${fmt(x.positionMs)}"
                    else -> "还没开始看"
                }
                root.setOnClickListener { onResume(x) }
                del.setOnClickListener { onDelete(x) }
            }
        }
    }

    companion object {
        /** 毫秒 -> m:ss / h:mm:ss */
        fun fmt(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
        }
    }
}
