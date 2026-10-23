package com.linghit.gdmusic

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 把「导入进度条」挂到任意 Activity 的内容区顶部。
 *
 * 由 App 的 ActivityLifecycleCallbacks 自动给每个页面挂上 —— 这样导入过程中
 * 用户切到首页/歌词页/设置页……都能看到同一条进度，直到导入结束。
 * 浮层本身不可点击（clickable=false），触摸穿透到下面页面，不挡任何操作。
 */
object ImportOverlay {

    private const val TAG = "import_overlay"
    private val main = Handler(Looper.getMainLooper())
    /** Activity → 已注册的订阅（WeakHashMap：页面销毁后自动释放，不泄漏） */
    private val bound: MutableMap<Activity, (ImportProgress.State) -> Unit> =
        Collections.synchronizedMap(WeakHashMap())

    /** 幂等：同一页面重复调用只挂一次（onActivityCreated 与 onActivityResumed 都会调） */
    fun attach(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(TAG) != null) return

        val v = activity.layoutInflater.inflate(R.layout.overlay_import_progress, root, false)
        v.tag = TAG
        v.visibility = View.GONE
        val bar = v.findViewById<ProgressBar>(R.id.ovProgressBar)
        val txt = v.findViewById<TextView>(R.id.ovStatus)

        val listener: (ImportProgress.State) -> Unit = { s ->
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (s.active) {
                    v.visibility = View.VISIBLE
                    txt.text = s.status
                    bar.isIndeterminate = s.indeterminate
                    if (!s.indeterminate && s.total > 0) {
                        bar.max = s.total
                        bar.progress = s.done
                    }
                } else {
                    v.visibility = View.GONE
                }
            }
        }
        bound[activity] = listener
        ImportProgress.listen(listener)

        root.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP }
        )
    }

    /** 页面销毁：解绑订阅并移除浮层 */
    fun detach(activity: Activity) {
        bound.remove(activity)?.let { ImportProgress.unlisten(it) }
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }
}
