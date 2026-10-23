package com.linghit.gdmusic

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 正在播放列表页：从首页「🎵 正在播放列表」入口卡进入，展示当前播放队列的全部歌曲。
 * 行点击 = 切到该首播放，右侧 ✕ = 从队列移除，当前播放项主题色高亮。
 * 数据全部来自全局 [PlayerManager]——与首页播放条、歌词页、桌面歌词、通知栏同一份队列。
 *
 * 为什么从首页拆出来：自定义资源位会随用户添加变多，挤压首页播放区；
 * 首页只保留「一个入口 + 底部播放条」，完整列表放本页，操作空间互不侵占。
 *
 * ⚠️ [PlayerManager.uiListener] 是单槽的：本页 onResume 抢过来、onPause 让回
 * （仅当槽里还是自己才清，避免误清别人的）；MainActivity.onResume 会重新注册，
 * 保证前台页面永远持有播放回调。
 */
class QueueActivity : AppCompatActivity(), PlayerUiListener {

    private lateinit var queueList: RecyclerView
    private lateinit var queueEmpty: View
    private lateinit var queueCount: TextView
    private lateinit var queueAdapter: QueueAdapter
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_queue)

        queueList = findViewById(R.id.queueList)
        queueEmpty = findViewById(R.id.queueEmpty)
        queueCount = findViewById(R.id.queueCount)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.playAllQueue).setOnClickListener {
            val q = PlayerManager.queueSnapshot()
            if (q.isEmpty()) Toast.makeText(this, "播放列表是空的", Toast.LENGTH_SHORT).show()
            else PlayerManager.playAllResults(q)
        }
        findViewById<View>(R.id.clearQueue).setOnClickListener {
            if (PlayerManager.queueSnapshot().isEmpty()) {
                Toast.makeText(this, "播放列表已经是空的", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PlayerManager.clearQueue()
            Toast.makeText(this, "播放列表已清空", Toast.LENGTH_SHORT).show()
        }
        // 空列表：跳回首页并切到搜索页签（MainActivity 读 open_tab 附加参数）
        findViewById<View>(R.id.gotoSearchBtn).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open_tab", "search"))
        }

        // 行点击 = 切到该首播放（与首页原播放列表卡同一套回调语义）
        queueAdapter = QueueAdapter(
            onPlay = { idx, _ ->
                // 界面数据可能比真实队列旧（极端情况），越界直接忽略，别崩
                val snap = PlayerManager.queueSnapshot()
                val s = snap.getOrNull(idx) ?: return@QueueAdapter
                PlayerManager.playInternal(s, snap, idx)
            },
            onRemove = { idx, _ -> PlayerManager.removeFromQueue(idx) }
        )
        queueList.apply {
            layoutManager = LinearLayoutManager(this@QueueActivity)
            adapter = queueAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        // 抢回播放回调（从首页进来时 MainActivity 还持有着，这里覆盖成自己），
        // 再同步一次当前播放态：onSongStart 里会刷新队列并定位到正在播的那首
        PlayerManager.uiListener = this
        PlayerManager.onUiAttached()
        refreshQueue()
    }

    override fun onPause() {
        super.onPause()
        if (PlayerManager.uiListener === this) PlayerManager.uiListener = null
    }

    /** 刷新队列页：计数行 + 空状态显隐。线程免疫（onSongStart 可能来自播放器回调线程） */
    private fun refreshQueue() {
        if (!::queueCount.isInitialized) return
        val block = {
            val q = PlayerManager.queueSnapshot()
            queueCount.text = when {
                // 临时播放中：明确告诉用户列表没被碰，避免以为点首歌列表就乱了
                PlayerManager.tempPlaying -> "共 ${q.size} 首 · 临时播放中（不动列表）"
                q.isEmpty() -> "播放列表是空的"
                else -> "共 ${q.size} 首"
            }
            queueAdapter.submit(q)
            queueEmpty.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
            queueList.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            scrollQueueToCurrent(q)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block() else uiHandler.post(block)
    }

    /**
     * 自动定位到正在播放的那首：进页面/切歌不用用户自己翻。
     *
     * ⚠️ 必须用 scrollToPositionWithOffset(pos, 0)，不能用 scrollToPosition：
     * 后者在目标项「已可见」时只保证完全可见、不滚动（LinearLayoutManager
     * updateAnchorFromPendingData 的可见分支），而 notifyDataSetChanged 后 RV 往往
     * 刚从 0 号位布完局、目标项恰在可见区 → 定位静默失效。
     * 带 offset=0 才会无条件把该项锚定到列表顶部。
     */
    private fun scrollQueueToCurrent(q: List<Song>) {
        // 临时播放期间列表原样没动、外面那首不在队列里，不滚
        if (PlayerManager.tempPlaying) return
        val cur = PlayerManager.current ?: return
        val pos = q.indexOfFirst { it === cur || it.key() == cur.key() }
        if (pos < 0) return
        val lm = queueList.layoutManager as? LinearLayoutManager ?: return
        runCatching { lm.scrollToPositionWithOffset(pos, 0) }
    }

    // ===== PlayerUiListener：本页只关心队列与高亮，播放态由首页播放条管 =====
    override fun onSongStart(song: Song, isLocal: Boolean, statusText: String) { refreshQueue() }
    override fun onCover(bitmap: Bitmap?) {}
    override fun onLyrics(text: String) {}
    override fun onStatus(text: String) {}
    override fun onPlayStateChanged(isPlaying: Boolean) {}
    override fun onModeChanged(mode: Int) {}
    override fun onCacheStateChanged(key: String, state: String) {}
    override fun onQueueChanged() { refreshQueue() }
}
