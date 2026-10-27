package com.linghit.gdmusic

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 自定义 Application：进程级初始化 + 给「每一个」Activity 自动挂上导入进度浮层。
 *
 * 用 ActivityLifecycleCallbacks 而不是在每个页面里写一遍，是因为需求要求
 * 「导入进度条在所有页面都显示，直到导入结束」：导入过程中用户可能跳到首页、
 * 歌词页、设置页……逐个页面改布局既改不全也容易漏，统一在这里挂钩最省事，
 * 以后新增页面自动就有了。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 桌面歌词控制器原来只在 MainActivity.onCreate 里 init，导致「冷启动直接进
        // QueueActivity / LyricActivity（没经过 MainActivity）」时 DesktopLyric.ctx
        // 未初始化，一读 enabled 就 UninitializedPropertyAccessException 崩。
        // Application 早于所有 Activity，放这里最稳，任何入口进来都已就绪。
        DesktopLyric.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                ImportOverlay.attach(a)
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
