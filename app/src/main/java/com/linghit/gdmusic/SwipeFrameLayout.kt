package com.linghit.gdmusic

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 支持「左右滑动切页签」的内容容器（首页 首页/搜索 两个面板共用）。
 *
 * 设计要点（全是坑，改前先读）：
 * - 手势判定必须同时铺在 onInterceptTouchEvent 和 onTouchEvent 两条路上，缺一不可：
 *   ① 落在可点子 view（卡片行/按钮/列表）上的 DOWN 被子 view 消费，靠
 *      onInterceptTouchEvent 中途抢断（返回 true 后本手势剩余事件进 onTouchEvent）；
 *   ② 落在空白区（不可点 spacer 等）的 DOWN 没人消费，ViewGroup 对后续 MOVE
 *      **直接走 onTouchEvent、压根不过 onInterceptTouchEvent**（mFirstTouchTarget
 *      为空时 intercepted 恒 true）——只写拦截这一路，空白区滑动永久失灵。
 * - 只在判定为横向（位移超 touchSlop 且明显大于竖向）时才 tracking；竖向一律放行，
 *   RecyclerView 滚动、长按、子 view 点击都不受影响。
 * - 排除区（addExcludedView）：起点落在排除 view 里的手势整段不参与。首页快捷方式
 *   网格（shortcutRow）必须排除：那些格子靠横向拖动排序，被抢走就变 ACTION_CANCEL、
 *   拖动当场断裂（同拖动手势铁律）。
 * - 子 view 自己 requestDisallowInterceptTouchEvent(true) 时同样放行（标准协作协议）。
 * - onSwipe 为空时行为与普通 FrameLayout 完全一致（其他页面复用无忧）。
 *
 * 回调方向：0 = 手指右→左滑（看下一页/下一个 tab）；1 = 手指左→右滑（看上一页/上一个 tab）。
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 滑动触发回调；dir: 0=向左滑(下一页) 1=向右滑(上一页) */
    var onSwipe: ((Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDist = (72 * resources.displayMetrics.density).toInt()
    private val excluded = mutableListOf<View>()

    private var downX = 0f
    private var downY = 0f
    /** 已判定为横向滑动（onInterceptTouchEvent 路：返回 true 抢断；onTouchEvent 路：开始跟踪） */
    private var tracking = false
    /** 本段手势不参与：起点在排除区 / 判成竖向 / 子视图要求不拦截 */
    private var skip = false
    /** 已回调过，防一次手势重复触发 */
    private var fired = false

    /** 注册排除区：起点落在其中的手势整个放行给子 view */
    fun addExcludedView(v: View) {
        if (!excluded.contains(v)) excluded.add(v)
    }

    private fun inExcluded(rawX: Float, rawY: Float): Boolean {
        if (excluded.isEmpty()) return false
        val loc = IntArray(2)
        return excluded.any { v ->
            v.getLocationOnScreen(loc)
            rawX >= loc[0] && rawX <= loc[0] + v.width &&
                rawY >= loc[1] && rawY <= loc[1] + v.height
        }
    }

    /**
     * 两条触摸路共用的状态机：DOWN 记录起点/重置，MOVE 判定横向，UP/CANCEL 触发回调。
     * 幂等——同一事件被两条路各过一次也不会错乱（DOWN 只会重置，判定只认一次）。
     */
    private fun processTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                tracking = false
                skip = false
                fired = false
                skip = inExcluded(downX, downY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (skip || fired || tracking) return
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    tracking = true
                } else if (abs(dy) > touchSlop) {
                    // 判定竖向：本段手势到此为止，交还子 view（滚动/长按照常）
                    skip = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!fired && tracking) {
                    val dx = ev.rawX - downX
                    if (abs(dx) > swipeDist) {
                        fired = true
                        // dx<0：手指往左滑 → 下一页；dx>0：手指往右滑 → 上一页
                        onSwipe?.invoke(if (dx < 0) 0 else 1)
                    }
                }
                tracking = false
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (onSwipe == null) return super.onInterceptTouchEvent(ev)
        processTouch(ev)
        // 横向判定一成立就抢断：本手势后续事件（含 UP）转走 onTouchEvent，
        // 正在处理这条手势的子 view 会收到 ACTION_CANCEL
        return tracking
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (onSwipe == null) return super.onTouchEvent(ev)
        // 两条路都会到这里：被抢断的手势 + 空白区无人消费 DOWN 的手势。
        // 必须消费 DOWN（返回 true），否则这条手势的后续事件不会再来
        processTouch(ev)
        return true
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // 子 view（如拖动中的格子）显式要求不拦截时，本段手势立即放行
        if (disallowIntercept) skip = true
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}
