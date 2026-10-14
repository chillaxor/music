package com.example.gdmusic

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.SensorManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 桌面歌词悬浮窗（移植自 mf_audit 的 LyricView，去掉 React Native 依赖）。
 * 用 TYPE_APPLICATION_OVERLAY 在其他 App 之上显示一行歌词，
 * 位置（上/下、左/右）、宽度、字号、颜色均可调，并跟随屏幕旋转重算坐标。
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限，未授权时 addView 会抛异常，由调用方兜住。
 */
class LyricWindow(private val appContext: Context) {

    private var windowManager: WindowManager? = null
    private var orientationEventListener: OrientationEventListener? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var tv: TextView? = null

    // 窗口/配置状态
    private var windowWidth = 0.0
    private var windowHeight = 0.0
    private var widthPercent = 0.5
    private var leftPercent = 0.5
    private var topPercent = 0.15

    /** 当前是否已显示 */
    val isShowing: Boolean get() = tv != null

    /** 显示悬浮窗；失败会抛异常（无悬浮窗权限时） */
    fun show(initText: String?, fontSize: Float, topPercent: Double,
             leftPercent: Double, widthPercent: Double, textColor: Int, bgColor: Int) {
        if (tv != null) { setText(initText ?: ""); return }
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val out = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(out)
        windowWidth = out.widthPixels.toDouble()
        windowHeight = out.heightPixels.toDouble()

        val lp = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            else
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            this@LyricWindow.widthPercent = widthPercent
            this@LyricWindow.leftPercent = leftPercent
            this@LyricWindow.topPercent = topPercent
            width = (widthPercent * this@LyricWindow.windowWidth).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = (leftPercent * (this@LyricWindow.windowWidth - width)).toInt()
            y = (topPercent * this@LyricWindow.windowHeight).toInt()
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            format = PixelFormat.TRANSLUCENT
        }

        val view = TextView(appContext).apply {
            text = initText ?: ""
            textSize = fontSize
            setBackgroundColor(bgColor)
            setTextColor(textColor)
            setPadding(14, 8, 14, 8)
            gravity = Gravity.CENTER
            maxLines = 3
        }
        wm.addView(view, lp)
        windowManager = wm
        layoutParams = lp
        tv = view
        listenOrientationChange()
    }

    fun hide() {
        if (windowManager == null) return
        tv?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.w("LyricWindow", "removeView fail", e) }
        }
        tv = null
        windowManager = null
        layoutParams = null
        orientationEventListener?.disable()
        orientationEventListener = null
    }

    fun setText(text: String) { tv?.text = text }

    fun setTopPercent(pct: Double) {
        val p = pct.coerceIn(0.0, 1.0)
        topPercent = p
        tv?.let {
            layoutParams?.y = (p * (windowHeight - it.height)).toInt()
            runCatching { windowManager?.updateViewLayout(it, layoutParams) }
        }
    }

    fun setLeftPercent(pct: Double) {
        val p = pct.coerceIn(0.0, 1.0)
        leftPercent = p
        tv?.let {
            layoutParams?.x = (p * (windowWidth - (layoutParams?.width ?: 0))).toInt()
            runCatching { windowManager?.updateViewLayout(it, layoutParams) }
        }
    }

    /** 按百分比改宽度，保持中心不变 */
    fun setWidthPercent(pct: Double) {
        val p = pct.coerceIn(0.3, 1.0)
        tv?.let { v ->
            val lp = layoutParams ?: return@let
            val newW = (p * windowWidth).toInt()
            val oldW = lp.width
            lp.x = if (newW <= oldW) lp.x + (oldW - newW) / 2
                   else (lp.x - (newW - oldW) / 2).coerceAtLeast(0)
            lp.x = lp.x.coerceAtMost((windowWidth - newW).toInt().coerceAtLeast(0))
            lp.width = newW
            widthPercent = p
            runCatching { windowManager?.updateViewLayout(v, lp) }
        }
    }

    fun setFontSize(sp: Float) { tv?.textSize = sp }

    fun setColors(textColor: Int, bgColor: Int) {
        tv?.let {
            it.setTextColor(textColor)
            it.background = ColorDrawable(bgColor)
        }
    }

    private fun listenOrientationChange() {
        if (windowManager == null) return
        if (orientationEventListener == null) {
            orientationEventListener = object : OrientationEventListener(
                appContext, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) {
                    if (windowManager == null) return
                    val out = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager?.defaultDisplay?.getMetrics(out)
                    windowWidth = out.widthPixels.toDouble()
                    windowHeight = out.heightPixels.toDouble()
                    val v = tv ?: return
                    val lp = layoutParams ?: return
                    lp.width = (widthPercent * windowWidth).toInt()
                    lp.x = (leftPercent * (windowWidth - lp.width)).toInt()
                    lp.y = (topPercent * (windowHeight - v.height)).toInt()
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) orientationEventListener?.enable()
    }
}
