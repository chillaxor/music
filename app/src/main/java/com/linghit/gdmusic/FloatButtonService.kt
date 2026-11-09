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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 常驻悬浮按钮：一个可拖动的圆形悬浮球，浮在**所有页面之上**（App 内任意页 /
 * 其他 App / 桌面）。
 *
 *  - 点击：执行配置好的主动作（默认「记一笔」，见 [FloatButtonStore.action]）
 *  - 长按 0.5s：以主球为圆心**放射展开气泡菜单**，每个气泡是一个可配入口
 *    （默认影视仓 / 首页，见 [FloatButtonStore.bubbles]）
 *  - 滑选：长按展开后**手指不要松开**，直接滑到某颗气泡上再抬手即执行；
 *    命中按「相对球心的方位角差最小」判扇区（气泡只有 40dp，矩形 contains 太容易判空），
 *    命中的气泡放大 + 换主题色 + 轻震动。菜单已展开时再按住主球也能直接滑选，
 *    此时原地抬手（没移动）仍按老行为收起菜单；点气泡也照旧可用。
 *    再点主球 / 点气泡 / 5 秒无操作自动收起
 *  - 拖动：仅在**未展开 / 未进入瞄准态**时生效，位移超过 touchSlop 算拖动，
 *    松手写回坐标（和首页 chip 拖拽同一套阈值逻辑）；
 *    拖到左右边缘附近松手会**贴边半隐藏**（整球滑出屏外只露一条边），
 *    摸一下露出的小条即滑回可见位（本手势不再触发点击，再点一下才是主动作）
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
        /** 贴边半隐藏：正常态 */
        private const val HIDDEN_NONE = 0
        /** 贴边半隐藏：藏左缘 */
        private const val HIDDEN_LEFT = 1
        /** 贴边半隐藏：藏右缘 */
        private const val HIDDEN_RIGHT = 2
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

    // ---- 长按滑选（放射菜单瞄准态） ----
    /** 手指还没抬起，MOVE 一律解释为「瞄准气泡」，不拖球、不收起菜单 */
    private var aiming = false
    /** 当前手指落在哪颗气泡的扇区；-1 = 没命中 */
    private var hoveredIndex = -1
    /** aiming 期间手指是否明显移动过（用来区分「点球收起」和「滑出去没选中」） */
    private var aimMoved = false
    /** 本次手势开始时菜单就已经展开 → 原地抬手按「点球收起」处理 */
    private var allowTapCollapse = false
    /** 展开时缓存每颗气泡的方位角（度），hitTestBubble 按角度差选扇区 */
    private val bubbleAngles = mutableListOf<Double>()
    private var aimCx = 0f
    private var aimCy = 0f
    /** 单个扇区允许的半宽，展开时按气泡数量算 */
    private var aimSectorHalf = 90.0

    // ---- 贴边半隐藏 ----
    /** 当前半隐藏边：HIDDEN_NONE / HIDDEN_LEFT / HIDDEN_RIGHT */
    private var hiddenSide = HIDDEN_NONE
    /** 半隐藏时留在屏内的可见宽度（球宽 dp52 的 ~30%，够点又不太占地） */
    private var sliverSize = 0
    /** 松手时距边缘多近算「要贴边」 */
    private var snapRange = 0
    private var animStartX = 0
    private var animTargetX = 0
    private var animStartT = 0L
    private val animDur = 260L
    private val interpolator = DecelerateInterpolator()

    private var ballHost: View? = null
    private var iconHost: ImageView? = null
    private var emojiHost: TextView? = null
    private val bubbleViews = mutableListOf<View>()
    private val bubbleLabels = mutableListOf<TextView>()
    // 每颗气泡的窗口参数：气泡窗在服务启动时就**预创建**好（常驻 GONE），
    // 长按只改坐标 + 切可见性，全程零 addView——不少 ROM 对悬浮窗 addView
    // 带系统进场动画，长按时才创建窗口会让气泡「弹进来」（用户反馈观感）
    private val bubbleParams = mutableListOf<WindowManager.LayoutParams>()
    private var ballSize = 0
    private var bubbleSize = 0
    private var fanRadius = 0

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
                    collapseBubbles()
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
            .setContentText("点击：${action.label} · 长按展开 ${n} 个气泡，滑到气泡上松手选中")
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
        sliverSize = dp(16)
        snapRange = dp(24)

        // 悬浮窗类型：气泡窗也要用，提前算好
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

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
        // 气泡一次性建齐（最多 MAX_BUBBLES 个），**服务启动时就各自挂成独立的
        // 悬浮小窗**（常驻 GONE：不可见、不接收触摸），长按时只改坐标 + 切 VISIBLE，
        // 全程零 addView / removeView。
        // 为什么不在长按时才 addView：不少 ROM 对悬浮窗 addView 带系统进场动画，
        // 长按瞬间创建窗口会让气泡「弹进来」，用户反馈观感很差；提前挂窗把这次
        // 窗口操作留在启动阶段，长按响应就只有 updateViewLayout（瞬时）。
        // 为什么不放进 rootView 跟主球共用一个窗：那样展开就得把球的窗撑大 + 移位，
        // 主球会被窗口带着「弹一下」再回位（用户反馈，且窗口边界动效各 ROM 不一样、
        // 关不掉）。现在球的窗永远是球大小、位置不动，物理上不可能再移位。
        // 每颗气泡一个 40dp 小窗也顺带解决了「气泡窗包围盒在斜向展开时盖住主球、
        // 导致展开后点球收不起来」的问题。
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
            val lp = WindowManager.LayoutParams(
                bubbleSize, bubbleSize, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 先放屏幕外（FLAG_LAYOUT_NO_LIMITS 允许负坐标）：防御个别 ROM
                // 对 GONE 悬浮窗的处理缺陷，绝不可能在 (0,0) 闪一下
                x = -bubbleSize - dp(8)
                y = -bubbleSize - dp(8)
                windowAnimations = 0 // 显式关掉窗口动效
            }
            bubbleParams.add(lp)
            runCatching { wm.addView(b, lp) }
                .onFailure { Log.w(TAG, "气泡窗预创建失败: ${it.message}") }
        }
        refreshBubbleLabels()

        // 拖动 + 点击 + 长按。长按 500ms 展开气泡；位移超过 touchSlop 算拖动，
        // 拖动后松手不再触发点击（和首页 chip 拖拽同一套阈值逻辑）
        ball.setOnTouchListener { _, ev -> onBallTouch(ev) }

        val saved = FloatButtonStore.position(this)
        // 半隐藏态要跨进程重启保留：先把球摆到贴边位置，再由 hiddenSide 决定露出哪条边
        hiddenSide = FloatButtonStore.hiddenSide(this)
        ballX = saved?.first ?: (dm.widthPixels - ballSize - dp(12))
        ballY = saved?.second ?: (dm.heightPixels * 0.35f).toInt()
        if (hiddenSide == HIDDEN_LEFT) ballX = -ballSize + sliverSize
        else if (hiddenSide == HIDDEN_RIGHT) ballX = dm.widthPixels - sliverSize

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
                mainHandler.removeCallbacks(xAnimRunnable)
                // 手势期间先摘掉自动收起，抬手时按结果重挂，避免瞄到一半菜单消失
                mainHandler.removeCallbacks(autoCollapseRunnable)
                downRawX = ev.rawX; downRawY = ev.rawY
                dragged = false
                longPressed = false
                aiming = false
                hoveredIndex = -1
                aimMoved = false
                // 半隐藏态被摸到：立刻滑回可见位，本手势不再算点击/长按
                // （否则用户点小条想唤出球，顺手就把主动作触发了）
                suppressClick = restoreFromEdge()
                downX = ballX; downY = ballY
                // 菜单在本次手势之前就展开了 → 原地抬手仍按「点球收起」处理
                allowTapCollapse = expanded && !suppressClick
                if (!suppressClick) {
                    if (expanded) {
                        // 菜单已展开：不用再等 500ms，按住主球即可直接滑选
                        aiming = true
                    } else {
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (aiming) {
                    // DOWN 定归属后，整串 MOVE 仍派给主球这个窗口，即使已滑出 52dp 边界；
                    // rawX/rawY 是屏幕绝对坐标，直接拿来算命中。此处绝不移动球、绝不收起菜单。
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) aimMoved = true
                    setHovered(hitTestBubble(ev.rawX, ev.rawY))
                    return true
                }
                if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragged = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    // 收起态下的拖动照旧展开态按到球会直接进 aiming，这里只兜底异常路径
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
                if (aiming) {
                    // 必须放在最前面：longPressed=true 会让下面原有的 UP 分支整段跳过，
                    // 而 dragged=true 会误触发写坐标 + 贴边半隐藏
                    val target = hoveredIndex
                    val moved = aimMoved
                    val tapCollapse = allowTapCollapse
                    aiming = false
                    hoveredIndex = -1
                    when {
                        ev.actionMasked == MotionEvent.ACTION_UP && target >= 0 ->
                            performBubbleClick(target) // 内部已 collapseBubbles()
                        ev.actionMasked == MotionEvent.ACTION_UP && !moved && tapCollapse ->
                            collapseBubbles()         // 菜单展开时点一下球 = 收起
                        ev.actionMasked == MotionEvent.ACTION_CANCEL -> {
                            clearHoverVisual()
                            collapseBubbles()
                        }
                        else -> {
                            clearHoverVisual()
                            scheduleAutoCollapse()    // 没命中：保持菜单等点选
                        }
                    }
                    return true
                }
                if (dragged) {
                    FloatButtonStore.setPosition(this, ballX, ballY)
                    // 贴边吸附半隐藏：松手时离左/右缘够近 → 整球滑出屏外只露一条边
                    val w = resources.displayMetrics.widthPixels
                    when {
                        ballX <= snapRange -> hideToEdge(HIDDEN_LEFT)
                        ballX >= w - ballSize - snapRange -> hideToEdge(HIDDEN_RIGHT)
                    }
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
        if (expanded) {
            collapseBubbles()
        } else {
            aiming = true
            hoveredIndex = -1
            expandBubbles()
            if (!expanded) aiming = false // 没配气泡，别留在瞄准态
        }
    }

    // ==================== 贴边半隐藏 ====================

    /** 松手贴边 → 整球滑出屏外只露 sliverSize 宽的一条边；状态落盘，重启仍藏 */
    private fun hideToEdge(side: Int) {
        hiddenSide = side
        FloatButtonStore.setHiddenSide(this, side)
        val w = resources.displayMetrics.widthPixels
        val target = if (side == HIDDEN_LEFT) -ballSize + sliverSize else w - sliverSize
        animateXTo(target)
        Log.d(TAG, "贴边半隐藏 side=$side targetX=$target")
    }

    /** 摸到露出的小条 → 滑回可见位。返回 true 表示本手势已消费（不再当点击/长按） */
    private fun restoreFromEdge(): Boolean {
        if (hiddenSide == HIDDEN_NONE) return false
        hiddenSide = HIDDEN_NONE
        FloatButtonStore.setHiddenSide(this, HIDDEN_NONE)
        val w = resources.displayMetrics.widthPixels
        val target = if (ballX < w / 2) dp(12) else w - ballSize - dp(12)
        // 手指已经摸上来了，不再播 260ms 动画，直接跳到可见位，跟手不粘
        mainHandler.removeCallbacks(xAnimRunnable)
        val p = params
        if (p != null) {
            p.x = target; ballX = target
            runCatching { wm.updateViewLayout(root, p) }
        }
        Log.d(TAG, "半隐藏滑回 visibleX=$target")
        return true
    }

    /** 横向吸附动画：只动 params.x，球纵坐标 ballY 不动；ballX 实时同步供拖动基准 */
    private val xAnimRunnable = object : Runnable {
        override fun run() {
            val p = params ?: return
            val t = ((android.os.SystemClock.uptimeMillis() - animStartT).toFloat() / animDur)
                .coerceIn(0f, 1f)
            p.x = animStartX + ((animTargetX - animStartX) * interpolator.getInterpolation(t)).toInt()
            ballX = p.x
            runCatching { wm.updateViewLayout(root, p) }
            if (t < 1f) mainHandler.post(this) else ballX = animTargetX
        }
    }

    private fun animateXTo(targetX: Int) {
        val p = params ?: return
        mainHandler.removeCallbacks(xAnimRunnable)
        if (p.x == targetX) { ballX = targetX; return }
        animStartX = p.x
        animTargetX = targetX
        animStartT = android.os.SystemClock.uptimeMillis()
        mainHandler.post(xAnimRunnable)
    }

    private val autoCollapseRunnable = Runnable { collapseBubbles() }

    /** 只在没有手势瞄准的时候挂自动收起；抬手未命中时调用重挂 */
    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (expanded) mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
    }

    /**
     * 手指 → 气泡：按「相对球心的方位角差最小」选扇区，不做矩形 contains。
     * 气泡只有 40dp、扇区之间空隙大，矩形判定会频繁判空；扇区判定滑过空隙也能选中最靠近的一颗。
     * 半径门槛：离球心太近算还在球上，太远算滑出菜单，都不选中。
     */
    private fun hitTestBubble(rawX: Float, rawY: Float): Int {
        if (!expanded || bubbleAngles.isEmpty()) return -1
        val dx = rawX - aimCx
        val dy = rawY - aimCy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < dp(24) || dist > fanRadius + bubbleSize) return -1
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        var best = -1
        var bestDiff = Double.MAX_VALUE
        for (i in bubbleAngles.indices) {
            val diff = abs(((deg - bubbleAngles[i] + 540.0) % 360.0) - 180.0)
            if (diff < bestDiff) { bestDiff = diff; best = i }
        }
        return if (bestDiff <= aimSectorHalf) best else -1
    }

    /** 命中变化才刷新高亮：主题色实心圆 + 放大，配一次轻震动 */
    private fun setHovered(i: Int) {
        if (hoveredIndex == i) return
        val prev = hoveredIndex
        hoveredIndex = i
        if (prev >= 0) bubbleViews.getOrNull(prev)?.let { restoreBubbleVisual(it) }
        if (i >= 0) bubbleViews.getOrNull(i)?.let { v ->
            v.animate().cancel()
            v.background = highlightedBubbleBg()
            v.animate().scaleX(1.22f).scaleY(1.22f).setDuration(90L).start()
            vibrate(10L)
        }
    }

    private fun restoreBubbleVisual(v: View) {
        v.animate().cancel()
        v.background = bubbleBg()
        v.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
    }

    /** 清掉所有高亮；直接赋值不用动画，避免收起时动画把 scale 又改回 1 */
    private fun clearHoverVisual() {
        bubbleViews.forEach { v ->
            if (v.visibility == View.VISIBLE) {
                v.animate().cancel()
                v.scaleX = 1f; v.scaleY = 1f
                v.background = bubbleBg()
            }
        }
    }

    private fun highlightedBubbleBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ThemePrefs.currentPack(this@FloatButtonService).primary)
        setStroke(dp(2), 0xFFFFFFFF.toInt())
    }

    /** 长按 → 放射展开气泡；扇区方向与边缘全部自适应，气泡不出屏 */
    /**
     * 展开气泡：每颗气泡各自 addView 成一个 40dp 的独立悬浮小窗。
     *
     * 主球的窗口**完全不碰**（不改 size、不改 x/y），所以主球不可能出现
     * 「长按时被带着弹一下再回位」——那是早期把气泡塞进球的窗、展开时
     * 撑大+移位窗口导致的（窗口边界动效还关不掉，各 ROM 表现不一）。
     *
     * 扇区方向照旧按剩余空间选（向上/向下，贴边偏 45°），保证气泡都在屏内。
     */
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
        val cx = ballX + ballSize / 2f
        val cy = ballY + ballSize / 2f
        // 命中判定必须用「真实屏幕坐标」做原点：WindowManager 的 lp.y 与窗口实际 frame
        // 之间可能差一个状态栏偏移（逍遥实测 +45px）。球与气泡同属 overlay、偏移一致，
        // 相对角度不受影响；但原点不对齐会让整组扇区上移，手指停在视觉气泡上会选偏。
        val loc = IntArray(2)
        root?.getLocationOnScreen(loc)
        if (loc[0] != 0 || loc[1] != 0) {
            aimCx = loc[0] + ballSize / 2f
            aimCy = loc[1] + ballSize / 2f
        } else {
            aimCx = cx; aimCy = cy
        }
        bubbleAngles.clear()
        val need = fanRadius + bubbleSize / 2 + dp(6)
        var center = if (cy < need && dm.heightPixels - cy > cy) 90.0 else -90.0
        if (cx < need) center += 45.0
        else if (dm.widthPixels - cx < need) center -= 45.0
        val half = when (n) { 1 -> 0.0; 2 -> 42.0; 3 -> 58.0; 4 -> 68.0; else -> 75.0 }
        // 扇区半宽 = 相邻气泡角距的一半 + 10° 余量，让气泡之间的空隙也能选中最靠近的一颗
        aimSectorHalf = if (n > 1) half / (n - 1) + 10.0 else 90.0

        // 以主球中心为原点算每颗气泡的圆心偏移
        for (i in 0 until n) {
            val v = bubbleViews[i]
            val deg = center + if (n > 1) half * (1.0 - 2.0 * i / (n - 1)) else 0.0
            val ang = Math.toRadians(deg)
            bubbleAngles.add(deg)
            val dx = (fanRadius * cos(ang)).toFloat()
            val dy = (fanRadius * sin(ang)).toFloat()
            // 气泡圆心 = 主球中心 + 偏移；窗口左上角 = 圆心 - 半径
            val wx = (cx + dx - bubbleSize / 2f).toInt()
            val wy = (cy + dy - bubbleSize / 2f).toInt()
            v.animate().cancel()
            v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
            v.visibility = View.VISIBLE
            // 窗口启动时已挂好，这里只挪坐标 + 确认可见：零 addView、零进场动画
            val lp = bubbleParams.getOrNull(i) ?: continue
            lp.x = wx
            lp.y = wy
            runCatching { wm.updateViewLayout(v, lp) }
                .onFailure { Log.w(TAG, "气泡窗 $i 定位失败: ${it.message}") }
        }
        // 配置变少的那几颗：直接隐掉（窗口仍挂着，下次展开再摆位）
        for (i in n until bubbleViews.size) bubbleViews[i].visibility = View.GONE

        // aiming 期间先不挂自动收起：抬手没命中时由 onBallTouch 重挂，
        // 否则慢慢瞄准到一半气泡会自己消失
        if (!aiming) scheduleAutoCollapse()
        vibrate()
        Log.d(TAG, "气泡展开 n=$n center=$center aiming=$aiming ball=(${ballX},${ballY}) aim=(${aimCx.toInt()},${aimCy.toInt()})")
    }

    /**
     * 收起：气泡窗口**不撤**，只切 GONE（窗口是启动时预挂的，见 onCreate）。
     * 主球那个窗从头到尾没被碰过，所以收起也不会有任何位移动画。
     */
    private fun collapseBubbles() {
        aiming = false
        hoveredIndex = -1
        bubbleAngles.clear()
        clearHoverVisual()
        if (!expanded) return
        expanded = false
        mainHandler.removeCallbacks(autoCollapseRunnable)
        val offX = -bubbleSize - dp(8)
        bubbleViews.forEachIndexed { i, v ->
            v.animate().cancel()
            v.visibility = View.GONE
            v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f
            // 顺手把窗口挪回屏幕外待命：万一个别 ROM 对 GONE 悬浮窗的刷新有延迟，
            // 位置先离开可见区域，视觉上绝不会留下残留圆点
            val lp = bubbleParams.getOrNull(i) ?: return@forEachIndexed
            if (lp.x != offX || lp.y != offX) {
                lp.x = offX; lp.y = offX
                runCatching { wm.updateViewLayout(v, lp) }
            }
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
        ballHost?.contentDescription = "悬浮按钮：${a.label}（点击进入；长按展开气泡，滑到气泡上松手选中）"

        bubbleViews.forEach { it.background = bubbleBg() }
    }

    /** 气泡文案/点击目标跟着配置走；配置变少时多余气泡隐藏并解绑点击 */
    private fun refreshBubbleLabels() {
        val items = FloatButtonStore.bubbles(this)
        for (i in bubbleLabels.indices) {
            if (i < items.size) {
                bubbleLabels[i].text = items[i].emoji.ifBlank { "•" }
                bubbleViews[i].contentDescription = "${items[i].label}，点击进入，或长按主球滑到这里松手选中"
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

    private fun vibrate(ms: Long = 20L) {
        runCatching {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }

    private fun removeView() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(xAnimRunnable)
        expanded = false
        hiddenSide = HIDDEN_NONE
        // 气泡是各自独立的窗口（常驻 GONE），服务销毁时统一撤掉
        bubbleViews.forEach { v -> runCatching { wm.removeView(v) } }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        bubbleViews.clear()
        bubbleLabels.clear()
        bubbleParams.clear()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
