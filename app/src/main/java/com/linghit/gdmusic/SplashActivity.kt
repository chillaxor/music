package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动闪屏页（Launcher 入口）。
 *
 * 为什么需要它：启动窗口背景在 App 进程创建前就由系统按主题 windowBackground
 * 渲染，那时读不到用户选的图 → 自定义启动背景图只能由本页承载：
 *   1. 冷启动瞬间：系统画 bg_splash_window（与首页同色，无白灰闪烁）
 *   2. 进程起来进本页：有自定义图 → 全屏 centerCrop 显示 + 底部应用名；
 *      没有 → 主题色 + 中央 logo + 进度条
 *   3. 约 600ms 后无动画切到 MainActivity
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Splash"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var jumped = false

    private val jump = Runnable {
        if (jumped || isFinishing) return@Runnable
        jumped = true
        runCatching {
            startActivity(Intent(this, MainActivity::class.java))
            // 无过渡动画：闪屏和首页之间不做淡入淡出，避免"闪一下"的割裂感
            overridePendingTransition(0, 0)
        }.onFailure { Log.w(TAG, "跳首页失败: ${it.message}") }
        finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        ThemePrefs.applyTheme(this)   // 进度条/主题色跟随当前套件
        super.onCreate(b)
        setContentView(R.layout.activity_splash)

        val bg = findViewById<ImageView>(R.id.splashBg)
        val center = findViewById<LinearLayout>(R.id.splashCenter)
        val caption = findViewById<TextView>(R.id.splashCaption)

        val customPath = if (SplashStore.enabled(this)) SplashStore.path(this) else null
        // 原图保留全质量，这里只按屏幕长边×2 采样解码：屏幕上 1:1 清晰，
        // 又不会把 4800 万像素原图整个 decode 进内存（那要 ~300MB，必 OOM）
        val edge = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        val custom = ImagePrep.decodeFileSampled(customPath, edge)
        if (custom != null) {
            bg.setImageBitmap(custom)
            bg.visibility = View.VISIBLE
            center.visibility = View.GONE
            caption.visibility = View.VISIBLE
            Log.d(TAG, "自定义启动背景图：$customPath")
        } else {
            if (customPath != null) Log.w(TAG, "自定义图读取失败，退回默认启动样式")
            bg.visibility = View.GONE
            center.visibility = View.VISIBLE
            caption.visibility = View.GONE
        }

        handler.postDelayed(jump, SplashStore.durationMs(this))
        // 点击任意处跳过：不想等就直接进 App
        findViewById<View>(R.id.splashRoot)?.setOnClickListener { jump.run() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(jump)
        super.onDestroy()
    }

    /** 闪屏页不进多任务历史：按返回键直接回桌面而不是回到闪屏 */
    override fun onBackPressed() {
        finish()
        super.onBackPressed()
    }
}
