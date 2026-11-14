package com.linghit.gdmusic

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log

/**
 * 自定义 Application：进程级初始化 + 给「每一个」Activity 自动挂上导入进度浮层。
 *
 * 用 ActivityLifecycleCallbacks 而不是在每个页面里写一遍，是因为需求要求
 * 「导入进度条在所有页面都显示，直到导入结束」：导入过程中用户可能跳到首页、
 * 歌词页、设置页……逐个页面改布局既改不全也容易漏，统一在这里挂钩最省事，
 * 以后新增页面自动就有了。
 */
class App : Application() {

    /**
     * 耳机/蓝牙断开广播：拔出有线耳机或关掉蓝牙音频设备时，系统会把音频路由切回扬声器
     * 并广播 [AudioManager.ACTION_AUDIO_BECOMING_NOISY]。此时主动暂停音乐与悬浮窗视频，
     * 避免外放把正在听的内容泄露出去（用户明确要求「关掉耳机就停」）。
     * 在 Application 进程级注册，保证后台/息屏状态也能收到。
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // 音乐：暂停并撤掉前台通知（保留播放器实例，下次点播可续）
            runCatching { PlayerManager.stop() }
            // 悬浮窗视频：本地播放器实例还在，直接暂停
            if (VideoFloatState.running) runCatching { VideoFloatState.player?.pause() }
        }
    }

    /**
     * 与 VideoActivity 的权限页拦截互补：部分爬虫 jar 会用 Application context 而不是
     * Activity 去拉起系统「所有文件访问」设置页，那条路径不经过 Activity.startActivity。
     * 只挡这一个 action，自有功能的页面跳转不受影响。
     */
    override fun startActivity(intent: Intent?) {
        if (intent?.action == VideoActivity.ACTION_MANAGE_ALL_FILES_ACCESS) {
            Log.w("VidSpider", "拦截爬虫 jar 的权限页(ApplicationContext): ${intent.action}")
            return
        }
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (intent?.action == VideoActivity.ACTION_MANAGE_ALL_FILES_ACCESS) {
            Log.w("VidSpider", "拦截爬虫 jar 的权限页(ApplicationContext): ${intent.action}")
            return
        }
        super.startActivity(intent, options)
    }

    override fun onCreate() {
        super.onCreate()
        // 桌面歌词控制器原来只在 MainActivity.onCreate 里 init，导致「冷启动直接进
        // QueueActivity / LyricActivity（没经过 MainActivity）」时 DesktopLyric.ctx
        // 未初始化，一读 enabled 就 UninitializedPropertyAccessException 崩。
        // Application 早于所有 Activity，放这里最稳，任何入口进来都已就绪。
        DesktopLyric.init(this)
        // 注册「耳机断开 = 停播」监听（进程级，息屏/后台也生效）
        runCatching {
            registerReceiver(
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                ImportOverlay.attach(a)
                // 多窗口（小窗/分屏）下系统会忽略此方向请求，因此小窗仍可自由 resize；
                // 全屏时保持竖屏观感。排除记账页（它本就设计为横竖屏都可用）。
                if (a !is NetAssetActivity) {
                    a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                // 兜底：页面被系统回收后重建、或 setContentView 时机特殊时，再补挂一次
                ImportOverlay.attach(a)
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                ImportOverlay.detach(a)
            }
        })
    }
}
