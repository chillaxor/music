package com.linghit.gdmusic

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // 底部播放条（与首页播放条同款控制：上一首/播放暂停/下一首/播放模式）
    private lateinit var queuePlayerBar: View
    private lateinit var qPrevBtn: ImageButton
    private lateinit var qPlayPauseBtn: ImageButton
    private lateinit var qNextBtn: ImageButton
    private lateinit var qModeBtn: ImageButton
    private lateinit var qLyricFloatBtn: ImageButton
    private lateinit var qNowPlaying: TextView
    private lateinit var qNowArtist: TextView

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

        // ===== 底部播放条：与首页播放条同一套 PlayerManager 控制 =====
        queuePlayerBar = findViewById(R.id.queuePlayerBar)
        qPrevBtn = findViewById(R.id.qPrevBtn)
        qPlayPauseBtn = findViewById(R.id.qPlayPauseBtn)
        qNextBtn = findViewById(R.id.qNextBtn)
        qModeBtn = findViewById(R.id.qModeBtn)
        qLyricFloatBtn = findViewById(R.id.qLyricFloatBtn)
        qNowPlaying = findViewById(R.id.qNowPlaying)
        qNowArtist = findViewById(R.id.qNowArtist)
        qPrevBtn.setOnClickListener { PlayerManager.skipPrev() }
        qNextBtn.setOnClickListener { PlayerManager.skipNext() }
        // 播放/暂停统一走 togglePlayPause：冷启动恢复的「当前歌」可能还没加载进播放器，
        // 直接 player.play() 是空操作，里面会兜底重新起播（同首页播放条）
        qPlayPauseBtn.setOnClickListener { PlayerManager.togglePlayPause() }
        // 悬浮（桌面歌词）开关：与首页原播放条同款，开/关后按钮颜色随状态变
        qLyricFloatBtn.setOnClickListener { toggleDesktopLyric() }
        // 点播放条「空白区」（歌名/歌手/卡片底，不含控制按钮）→ 进歌词页看大字号歌词。
        // 各控制按钮有独立监听、会消费点击不冒泡，所以只有真正点到空白才会走到这里。
        queuePlayerBar.setOnClickListener {
            if (PlayerManager.current == null) {
                Toast.makeText(this, "先播放一首歌", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, LyricActivity::class.java))
        }
        qModeBtn.setOnClickListener {
            val m = (PlayerManager.playMode + 1) % 3
            PlayerManager.setMode(m)
            val tip = when (m) { 1 -> "列表循环"; 2 -> "随机播放"; else -> "顺序播放（播完停）" }
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        }
        // 播放键圆圈按当前主题重画（drawable 无法用 ?attr 上色，同首页 applyThemeColors）
        val pack = ThemePrefs.currentPack(this)
        qPlayPauseBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (pack.primaryEnd != null) {
                colors = intArrayOf(pack.primary, pack.primaryEnd)
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(pack.primary)
            }
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
        refreshQLyricBtn() // 进页面时同步桌面歌词按钮颜色（从首页开着进来的话已是开启态）
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
            syncQueueBar()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block() else uiHandler.post(block)
    }

    /**
     * 同步底部播放条：有正在播放的歌才显示（无则整条隐藏），歌名/歌手/播放态/模式图标全部对齐。
     * 队列被清空时若仍在放当前歌，条子照旧显示（clearQueue 不中断当前曲目）。
     */
    private fun syncQueueBar() {
        if (!::queuePlayerBar.isInitialized) return
        val cur = PlayerManager.current
        queuePlayerBar.visibility = if (cur != null) View.VISIBLE else View.GONE
        if (cur == null) return
        qNowPlaying.text = cur.name
        qNowArtist.text = if (cur.artist.isBlank()) cur.album else "${cur.artist} · ${cur.album}"
        // 播放器理论上是随 PlayerManager.init 就建好的，兜一层防极端情况下未初始化抛异常
        val playing = runCatching { PlayerManager.getPlayer().isPlaying }.getOrDefault(false)
        qPlayPauseBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        applyQModeIcon()
    }

    /** 播放模式图标：顺序/列表循环/随机（与首页 modeBtn 同款图标） */
    private fun applyQModeIcon() {
        qModeBtn.setImageResource(
            when (PlayerManager.playMode) {
                1 -> R.drawable.ic_repeat
                2 -> R.drawable.ic_shuffle
                else -> R.drawable.ic_sequence
            }
        )
    }

    /** 桌面歌词（悬浮）按钮随开关状态变色：开=主题色，关=灰（star_off 资源与首页一致） */
    private fun refreshQLyricBtn() {
        if (!::qLyricFloatBtn.isInitialized) return
        val on = DesktopLyric.enabled
        qLyricFloatBtn.setColorFilter(
            ContextCompat.getColor(this, if (on) R.color.primary else R.color.star_off))
    }

    /** 桌面歌词（悬浮）开关：无悬浮窗权限时先引导授权；开关后 Toast 反馈并刷新按钮颜色 */
    private fun toggleDesktopLyric() {
        if (DesktopLyric.enabled) {
            DesktopLyric.enabled = false
            refreshQLyricBtn()
            Toast.makeText(this, "桌面歌词已关闭", Toast.LENGTH_SHORT).show()
            return
        }
        if (!DesktopLyric.hasPermission()) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("桌面歌词要浮在其他应用上方显示，请在系统设置里允许「显示在其他应用上层」。")
                .setPositiveButton("去授权") { _, _ -> DesktopLyric.requestPermission() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        DesktopLyric.enabled = true // setter 内部会尝试 show
        val ok = DesktopLyric.isShowing()
        refreshQLyricBtn()
        Toast.makeText(this,
            if (ok) "桌面歌词已开启" else "桌面歌词开启失败（可到设置里调位置/字号）",
            Toast.LENGTH_SHORT).show()
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

    // ===== PlayerUiListener：本页只关心队列/高亮/底部播放条，歌词与封面交给歌词页 =====
    override fun onSongStart(song: Song, isLocal: Boolean, statusText: String) { refreshQueue() }
    override fun onCover(bitmap: Bitmap?) {}
    override fun onLyrics(text: String) {}
    override fun onStatus(text: String) {}
    override fun onPlayStateChanged(isPlaying: Boolean) {
        // 播放/暂停图标跟着播放器状态走（onResume 时 onUiAttached 也会推一次，保证进来就是对的）
        if (::qPlayPauseBtn.isInitialized) {
            qPlayPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }
    override fun onModeChanged(mode: Int) { if (::qModeBtn.isInitialized) applyQModeIcon() }
    override fun onCacheStateChanged(key: String, state: String) {}
    override fun onQueueChanged() { refreshQueue() }
}
