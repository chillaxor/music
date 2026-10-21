package com.linghit.gdmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 常驻悬浮按钮：一个可拖动的圆形悬浮球，浮在**所有页面之上**（App 内任意页 /
 * 其他 App / 桌面），点击执行配置好的动作（默认「记一笔」），长按修改配置。
 *
 * 与 [VideoFloatService] 的区别：那个是播放器小窗，这个是纯入口按钮。
 * 用前台服务承载，保证退到别的 App 时进程不被立刻回收、悬浮球不消失。
 *
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）；未授权时静默不显示，
 * 由设置页引导授权（和桌面歌词同一套流程）。
 */
class FloatButtonService : Service() {

    companion object {
        private const val TAG = "FloatBtn"
        private const val CH_ID = "float_button"
        private const val NOTI_ID = 9002
        const val ACTION_STOP = "com.linghit.gdmusic.FLOAT_BTN_STOP"
        const val ACTION_REFRESH = "com.linghit.gdmusic.FLOAT_BTN_REFRESH"

        fun start(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, FloatButtonService::class.java))
            }.onFailure { Log.w(TAG, "启动失败: ${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, FloatButtonService::class.java).setAction(ACTION_STOP))
            }
        }

        fun refresh(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, FloatButtonService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragged = false
    private var longPressed = false
    private var touchSlop = 0

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { removeView(); stopSelf() }
            ACTION_REFRESH -> {
                if (root != null) applyAction()      // 配置变了：只换图标/颜色，不重建窗口
                else addView()
            }
            else -> addView()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }

    // ==================== 前台通知（保活） ====================
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "常驻悬浮按钮", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = FloatButtonStore.action(this)
        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("悬浮按钮运行中")
            .setContentText("点击执行：${action.label}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
        runCatching { startForeground(NOTI_ID, n) }
            .onFailure { Log.w(TAG, "startForeground 失败: ${it.message}") }
    }

    // ==================== 悬浮球 ====================
    private fun addView() {
        if (root != null) return
        if (!FloatButtonStore.enabled(this)) { stopSelf(); return }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，不显示")
            stopSelf()
            return
        }
        val dm = resources.displayMetrics
        val size = dp(52)

        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            // 深色描边圈：任何主题色下都能和背景区分
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0x40000000)
            }
        }

        // 拖动 + 点击 + 长按。长按 500ms 进配置；位移超过 touchSlop 算拖动，
        // 拖动后松手不再触发点击（和首页 chip 拖拽同一套阈值逻辑）
        tv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    downX = params?.x ?: 0; downY = params?.y ?: 0
                    dragged = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragged = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragged) {
                        val p = params ?: return@setOnTouchListener true
                        p.x = (downX + dx.toInt())
                            .coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
                        p.y = (downY + dy.toInt())
                            .coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(root, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragged) {
                        params?.let { FloatButtonStore.setPosition(this, it.x, it.y) }
                    } else if (!longPressed && ev.actionMasked == MotionEvent.ACTION_UP) {
                        performClick()
                    }
                    true
                }
                else -> true
            }
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val saved = FloatButtonStore.position(this)
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: (dm.widthPixels - size - dp(12))
            y = saved?.second ?: (dm.heightPixels * 0.35f).toInt()
        }
        params = lp
        runCatching {
            wm.addView(tv, lp)
            root = tv
            applyAction()
            Log.d(TAG, "悬浮球已显示 (${lp.x},${lp.y})")
        }.onFailure {
            Log.w(TAG, "添加悬浮球失败: ${it.message}")
            root = null
            stopSelf()
        }
    }

    /** 按当前配置刷新图标与主题色（配置页改完 / 换主题后调） */
    private fun applyAction() {
        val tv = root as? TextView ?: return
        val a = FloatButtonStore.action(this)
        tv.text = a.emoji.ifBlank { "✏️" }
        tv.contentDescription = "悬浮按钮：${a.label}（点击进入，长按修改）"
        val pack = ThemePrefs.currentPack(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (pack.primaryEnd != null) {
                colors = intArrayOf(pack.primary, pack.primaryEnd!!)
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(pack.primary)
            }
            setStroke(dp(2), 0x40000000)
        }
        tv.background = bg
    }

    private val longPressRunnable = Runnable {
        longPressed = true
        Log.d(TAG, "长按 → 打开配置")
        runCatching {
            startActivity(
                Intent(this, FloatConfigActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "打开配置页失败: ${it.message}") }
    }

    private fun performClick() {
        val a = FloatButtonStore.action(this)
        Log.d(TAG, "点击 → ${a.label} (${a.type}/${a.target})")
        ShortcutRouter.open(this, a)
    }

    private fun removeView() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
