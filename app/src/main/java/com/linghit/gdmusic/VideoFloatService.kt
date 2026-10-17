package com.linghit.gdmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Activity 与悬浮服务共享的播放器实例：切换承载 View 时不重新缓冲、不中断 */
object VideoFloatState {
    var player: ExoPlayer? = null
    var running = false
}

/**
 * 系统悬浮窗播放器：用 WindowManager 的 TYPE_APPLICATION_OVERLAY 浮在**所有页面之上**
 * （音乐页 / 记账页 / 别的 App 都能看到），并保留自己的拖动、✕ 关闭、⤢ 回全屏。
 *
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）；
 * 用前台服务承载，保证退到别的 App 时进程不被立刻回收。
 */
class VideoFloatService : Service() {

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var floatPlayerView: PlayerView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragged = false
    private var touchSlop = 0

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 由 Activity 发起的「收回浮层」：保留播放，交给 Activity 全屏继续播
            ACTION_STOP -> stopFloat(keepPlaying = true)
            else -> startFloat()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }

    // ==================== 前台通知（保活，退到别的 App 也不断） ====================
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "视频小窗", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, expandIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("视频小窗播放中")
            .setContentText("点此回到全屏播放")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .build()
        runCatching { startForeground(NOTI_ID, n) }
            .onFailure { Log.w("VidFloat", "startForeground 失败: ${it.message}") }
    }

    // ==================== 悬浮窗 ====================
    private fun startFloat() {
        if (root != null) return
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.46f).toInt().coerceAtMost(dm.heightPixels / 2)
        val h = (w * 9 / 16)
        val margin = (12 * dm.density).toInt()

        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        container.addView(
            pv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        floatPlayerView = pv

        fun button(txt: String, g: Int, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = txt
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x66000000.toInt()); cornerRadius = 20f
                }
                setOnClickListener { onClick() }
            }
            val lp = FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = g
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            container.addView(tv, lp)
            if (txt == "✕") btnClose = tv else btnExpand = tv
        }
        button("✕", Gravity.TOP or Gravity.START) { stopFloat() }
        button("⤢", Gravity.TOP or Gravity.END) { expandBack() }

        // 拖动；落在按钮上则放行给按钮（否则父层吞掉点击，✕ 会点不动）
        container.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hitButton(ev)) return@setOnTouchListener false
                    downRawX = ev.rawX; downRawY = ev.rawY
                    downX = params?.x ?: 0; downY = params?.y ?: 0
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragged && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) dragged = true
                    if (dragged) {
                        val p = params ?: return@setOnTouchListener true
                        p.x = clamp(downX + dx.toInt(), 0, dm.widthPixels - w)
                        p.y = clamp(downY + dy.toInt(), 0, dm.heightPixels - h)
                        runCatching { wm.updateViewLayout(root, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 点画面（没拖动）= 展开回全屏，与页内小窗一致
                    if (!dragged) expandBack()
                    true
                }
                else -> false
            }
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - w - margin).coerceAtLeast(0)
            y = (dm.heightPixels - h - margin * 4).coerceAtLeast(0)
        }
        params = lp
        runCatching {
            wm.addView(container, lp)
            root = container
            VideoFloatState.running = true
            // 关键：把同一个播放器实例挂到浮层的 PlayerView 上（不重新缓冲）
            pv.player = VideoFloatState.player
            Log.d("VidFloat", "悬浮窗已显示 ${w}x${h}")
        }.onFailure {
            Log.w("VidFloat", "添加悬浮窗失败: ${it.message}")
            VideoFloatState.running = false
            stopSelf()
        }
    }

    private var btnClose: View? = null
    private var btnExpand: View? = null

    private fun hitButton(ev: MotionEvent): Boolean {
        fun hit(v: View?): Boolean {
            if (v == null || v.visibility != View.VISIBLE) return false
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            return ev.rawX >= l[0] && ev.rawX <= l[0] + v.width &&
                ev.rawY >= l[1] && ev.rawY <= l[1] + v.height
        }
        return hit(btnClose) || hit(btnExpand)
    }

    /** ⤢ / 点画面 / 通知：把 Activity 拉回前台全屏播放。
     *  必须 CLEAR_TOP：此时栈顶可能是音乐页/记账页，只有 CLEAR_TOP 能把
     *  栈里已有的 VideoActivity 提到前台并交付 onNewIntent（SINGLE_TOP 仅在其位于栈顶时生效）。 */
    private fun expandBack() {
        Log.d("VidFloat", "展开回全屏")
        runCatching { startActivity(expandIntent()) }
            .onFailure { Log.w("VidFloat", "拉回播放页失败: ${it.message}") }
        stopFloat(keepPlaying = true)
    }

    private fun expandIntent() = Intent(this, VideoActivity::class.java)
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        .putExtra(EXTRA_ACTION, ACTION_EXPAND)

    /** ✕：停播并收掉悬浮窗 */
    private fun stopFloat(keepPlaying: Boolean = false) {
        Log.d("VidFloat", "收起悬浮窗 keepPlaying=$keepPlaying")
        val p = VideoFloatState.player
        if (!keepPlaying) {
            runCatching { p?.pause(); p?.stop() }
        }
        floatPlayerView?.player = null
        removeView()
        VideoFloatState.running = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun removeView() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        floatPlayerView = null
    }

    private fun stopForegroundCompat() {
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun clamp(v: Int, min: Int, max: Int) = v.coerceIn(min, maxOf(min, max))

    companion object {
        const val CH_ID = "video_float"
        const val NOTI_ID = 9001
        const val ACTION_STOP = "com.linghit.gdmusic.VIDEO_FLOAT_STOP"
        const val EXTRA_ACTION = "float_action"
        const val ACTION_EXPAND = "expand"

        /** 收掉浮层但**保留播放**（Activity 要把播放器接回全屏时用） */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, VideoFloatService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
