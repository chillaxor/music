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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 常驻悬浮按钮：一个可拖动的圆形悬浮球，浮在**所有页面之上**（App 内任意页 /
 * 其他 App / 桌面）。
 *
 *  - 点击：执行配置好的主动作（默认「记一笔」，见 [FloatButtonStore.action]）
 *  - 长按 0.5s：以主球为圆心**放射展开气泡菜单**，每个气泡是一个可配入口
 *    （默认影视仓 / 首页，见 [FloatButtonStore.bubbles]）；点气泡直达，
 *    再点主球 / 点气泡 / 5 秒无操作自动收起
 *  - 拖动：位移超过 touchSlop 算拖动，松手写回坐标（和首页 chip 拖拽同一套阈值逻辑）
 *
 * 配置（主动作 / 气泡 / 图标）统一在 App 设置页的「常驻悬浮按钮」卡片里改，
 * 长按小球不再弹配置框。
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
        private const val LONG_PRESS_MS = 500L
        private const val AUTO_COLLAPSE_MS = 5000L

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
    /** 展开态被滑动收起的那一下，抬手不再触发点击 */
    private var suppressClick = false
    private var touchSlop = 0

    /** 主球左上角屏幕坐标；收起态 = 窗口坐标，拖动时实时更新 */
    private var ballX = 0
    private var ballY = 0
    private var expanded = false

    private var ballHost: View? = null
    private var iconHost: ImageView? = null
    private var emojiHost: TextView? = null
    private val bubbleViews = mutableListOf<View>()
    private val bubbleLabels = mutableListOf<TextView>()
    private var ballSize = 0
    private var bubbleSize = 0
    private var fanRadius = 0
    private var rootSize = 0

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
                if (root != null) {
                    // 配置/图标变了：先收起气泡重置几何，再换内容，不重建窗口
                    collapseBubbles(animate = false)
                    refreshBubbleLabels()
                    applyStyle()
                } else addView()
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
        val n = FloatButtonStore.bubbles(this).size
        val noti = Notification.Builder(this, CH_ID)
            .setContentTitle("悬浮按钮运行中")
            .setContentText("点击：${action.label} · 长按展开 ${n} 个气泡")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
        runCatching { startForeground(NOTI_ID, noti) }
            .onFailure { Log.w(TAG, "startForeground 失败: ${it.message}") }
    }

    // ==================== 悬浮球 + 气泡 ====================
    private fun addView() {
        if (root != null) return
        if (!FloatButtonStore.enabled(this)) { stopSelf(); return }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，不显示")
            stopSelf()
            return
        }
        val dm = resources.displayMetrics
        ballSize = dp(52)
        bubbleSize = dp(40)
        fanRadius = dp(62)
        // 正方形 root：主球居中钉在某一角，放射气泡摆满其余空间；
        // 半径 + 气泡半径 + 内边距，两边正好放下最扁的扇形
        rootSize = 2 * (fanRadius + bubbleSize / 2 + dp(6))

        // 主球容器：ImageView（相册图片模式）与 TextView（emoji 模式）叠放，按配置显隐
        val ball = FrameLayout(this).apply {
            val iconView = ImageView(this@FloatButtonService).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            val emojiTv = TextView(this@FloatButtonService).apply {
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
            }
            addView(
                iconView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                emojiTv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        iconHost = ball.getChildAt(0) as ImageView
        emojiHost = ball.getChildAt(1) as TextView
        ballHost = ball

        val rootView = FrameLayout(this).apply {
            addView(ball, FrameLayout.LayoutParams(ballSize, ballSize))
        }
        // 气泡一次性建齐（最多 MAX_BUBBLES 个），常驻 GONE，展开时才摆位显示
        repeat(FloatButtonStore.MAX_BUBBLES) {
            val b = FrameLayout(this).apply {
                background = bubbleBg()
                val tv = TextView(this@FloatButtonService).apply {
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(0xFFFFFFFF.toInt())
                }
                addView(
                    tv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                visibility = View.GONE
            }
            bubbleViews.add(b)
            bubbleLabels.add(b.getChildAt(0) as TextView)
            rootView.addView(b, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        }
        refreshBubbleLabels()

        // 拖动 + 点击 + 长按。长按 500ms 展开气泡；位移超过 touchSlop 算拖动，
        // 拖动后松手不再触发点击（和首页 chip 拖拽同一套阈值逻辑）
        ball.setOnTouchListener { _, ev -> onBallTouch(ev) }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val saved = FloatButtonStore.position(this)
        ballX = saved?.first ?: (dm.widthPixels - ballSize - dp(12))
        ballY = saved?.second ?: (dm.heightPixels * 0.35f).toInt()

        // 收起态窗口就是球大小，不拦截球以外的触摸；展开时才撑成 rootSize
        val lp = WindowManager.LayoutParams(
            ballSize, ballSize, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
        params = lp
        runCatching {
            wm.addView(rootView, lp)
            root = rootView
            applyStyle()
            Log.d(TAG, "悬浮球已显示 (${lp.x},${lp.y})")
        }.onFailure {
            Log.w(TAG, "添加悬浮球失败: ${it.message}")
            root = null
            stopSelf()
        }
    }

    private fun onBallTouch(ev: MotionEvent): Boolean {
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downX = ballX; downY = ballY
                dragged = false
                longPressed = false
                suppressClick = false
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragged = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    // 展开态下划手：直接收起，本次手势不再当点击
                    if (expanded) {
                        collapseBubbles()
                        suppressClick = true
                        return true
                    }
                }
                if (dragged) {
                    val p = params ?: return true
                    p.x = (downX + dx.toInt())
                        .coerceIn(0, (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0))
                    p.y = (downY + dy.toInt())
                        .coerceIn(0, (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0))
                    ballX = p.x; ballY = p.y
                    runCatching { wm.updateViewLayout(root, p) }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (dragged) {
                    FloatButtonStore.setPosition(this, ballX, ballY)
                } else if (!longPressed && !suppressClick && ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (expanded) collapseBubbles() else performClick()
                }
                true
            }
            else -> true
        }
    }

    private val longPressRunnable = Runnable {
        longPressed = true
        if (expanded) collapseBubbles() else expandBubbles()
    }

    private val autoCollapseRunnable = Runnable { collapseBubbles() }

    /** 长按 → 放射展开气泡；扇区方向与边缘全部自适应，气泡不出屏 */
    private fun expandBubbles() {
        if (expanded || root == null) return
        val items = FloatButtonStore.bubbles(this)
        val n = items.size.coerceAtMost(FloatButtonStore.MAX_BUBBLES)
        if (n == 0) {
            Toast.makeText(this, "还没配气泡，去设置里添加", Toast.LENGTH_SHORT).show()
            return
        }
        expanded = true
        val dm = resources.displayMetrics

        // 扇区中心角：默认向上（-90°）。按球心实际剩余空间判向，不按屏幕百分比——
        // 顶部放不下整个扇形且下方更宽 → 向下；球心离左/右缘太近 → 整体偏 45°。
        // 方向选对是「球不动」的前提：气泡朝空间大的方向展开，无需再钳制窗口。
        val cx = ballX + ballSize / 2f
        val cy = ballY + ballSize / 2f
        val need = fanRadius + bubbleSize / 2 + dp(6)
        var center = if (cy < need && dm.heightPixels - cy > cy) 90.0 else -90.0
        if (cx < need) center += 45.0
        else if (dm.widthPixels - cx < need) center -= 45.0
        val half = when (n) { 1 -> 0.0; 2 -> 42.0; 3 -> 58.0; 4 -> 68.0; else -> 75.0 }

        // 以主球中心为原点算每个气泡偏移，并求内容包围盒 → 主球在 root 里的锚点
        var minX = -ballSize / 2f; var maxX = ballSize / 2f
        var minY = -ballSize / 2f; var maxY = ballSize / 2f
        val offs = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val ang = Math.toRadians(center + if (n > 1) half * (1.0 - 2.0 * i / (n - 1)) else 0.0)
            val dx = (fanRadius * cos(ang)).toFloat()
            val dy = (fanRadius * sin(ang)).toFloat()
            offs.add(dx to dy)
            minX = minOf(minX, dx - bubbleSize / 2f); maxX = maxOf(maxX, dx + bubbleSize / 2f)
            minY = minOf(minY, dy - bubbleSize / 2f); maxY = maxOf(maxY, dy + bubbleSize / 2f)
        }
        val pad = dp(6)
        val anchorX = (-minX + pad).toInt().coerceAtLeast(0)
        val anchorY = (-minY + pad).toInt().coerceAtLeast(0)

        // 主球钉到锚点（气泡以此为中心放射）
        (ballHost?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = anchorX; topMargin = anchorY
        }
        ballHost?.requestLayout()

        // 气泡摆位 + 阶梯弹入动画
        for (i in 0 until n) {
            val v = bubbleViews[i]
            val (dx, dy) = offs[i]
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = (anchorX + ballSize / 2 + dx - bubbleSize / 2).toInt()
                topMargin = (anchorY + ballSize / 2 + dy - bubbleSize / 2).toInt()
            }
            v.animate().cancel()
            v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f
            v.visibility = View.VISIBLE
            v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(30L * i).setDuration(150L).start()
        }
        for (i in n until bubbleViews.size) bubbleViews[i].visibility = View.GONE

        // 窗口撑成 rootSize，主球视觉位置**不变**：原点 = 球坐标 − 锚点，绝不做屏幕钳制。
        // ⚠️ 早期版本在这里 coerceIn(0, 屏宽-rootSize)，球贴边缘时负值被钳到 0，
        // 主球当场被推离原位；收起时又移一次（用户反馈「长按移位、关掉气泡也移位」）。
        // FLAG_LAYOUT_NO_LIMITS 已允许窗口伸出屏幕，扇区方向又按剩余空间选好，
        // 气泡在屏内；root 超屏的部分被裁掉即可。ballX/Y 不写回，收起精确回到原位。
        val p = params ?: return
        p.width = rootSize; p.height = rootSize
        p.x = ballX - anchorX
        p.y = ballY - anchorY
        runCatching { wm.updateViewLayout(root, p) }

        mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
        vibrate()
        Log.d(TAG, "气泡展开 n=$n center=$center anchor=($anchorX,$anchorY)")
    }

    /** 收起：气泡缩回，窗口缩回球大小，主球回到 ballX/ballY */
    private fun collapseBubbles(animate: Boolean = true) {
        if (!expanded) return
        expanded = false
        mainHandler.removeCallbacks(autoCollapseRunnable)
        bubbleViews.forEach { v ->
            v.animate().cancel()
            if (animate) {
                v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(120L)
                    .withEndAction { v.visibility = View.GONE }
                    .start()
            } else {
                v.visibility = View.GONE
                v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f
            }
        }
        (ballHost?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = 0; topMargin = 0
        }
        val p = params
        if (p != null) {
            p.width = ballSize; p.height = ballSize; p.x = ballX; p.y = ballY
            runCatching { wm.updateViewLayout(root, p) }
        }
        Log.d(TAG, "气泡收起")
    }

    private fun performBubbleClick(i: Int) {
        val item = FloatButtonStore.bubbles(this).getOrNull(i) ?: return
        Log.d(TAG, "气泡 $i → ${item.label} (${item.type}/${item.target})")
        ShortcutRouter.open(this, item)
        collapseBubbles()
    }

    /** 按当前配置刷新主题色、图标与气泡文案（配置页改完 / 换主题后调） */
    private fun applyStyle() {
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
        ballHost?.background = bg

        val a = FloatButtonStore.action(this)
        // 自定义相册图片优先（落盘前已缩成 256px 圆形，加载无内存风险）；
        // 没有或读坏了再退回 emoji
        val icon = FloatIcon.load(this)
        if (icon != null) {
            iconHost?.setImageBitmap(icon)
            iconHost?.visibility = View.VISIBLE
            emojiHost?.visibility = View.GONE
        } else {
            iconHost?.setImageDrawable(null)
            iconHost?.visibility = View.GONE
            emojiHost?.text = a.emoji.ifBlank { "✏️" }
            emojiHost?.visibility = View.VISIBLE
        }
        ballHost?.contentDescription = "悬浮按钮：${a.label}（点击进入，长按展开气泡）"

        bubbleViews.forEach { it.background = bubbleBg() }
    }

    /** 气泡文案/点击目标跟着配置走；配置变少时多余气泡隐藏并解绑点击 */
    private fun refreshBubbleLabels() {
        val items = FloatButtonStore.bubbles(this)
        for (i in bubbleLabels.indices) {
            if (i < items.size) {
                bubbleLabels[i].text = items[i].emoji.ifBlank { "•" }
                bubbleViews[i].contentDescription = "${items[i].label}，点击进入"
                bubbleViews[i].setOnClickListener { performBubbleClick(i) }
            } else {
                bubbleViews[i].visibility = View.GONE
                bubbleViews[i].setOnClickListener(null)
            }
        }
    }

    /** 气泡底色：近黑圆底 + 主题色描边，和彩色主球拉开层级 */
    private fun bubbleBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xF2111111.toInt())
        setStroke(dp(2), ThemePrefs.currentPack(this@FloatButtonService).primary)
    }

    private fun performClick() {
        val a = FloatButtonStore.action(this)
        Log.d(TAG, "点击 → ${a.label} (${a.type}/${a.target})")
        ShortcutRouter.open(this, a)
    }

    private fun vibrate() {
        runCatching {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(20)
            }
        }
    }

    private fun removeView() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        expanded = false
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        bubbleViews.clear()
        bubbleLabels.clear()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
