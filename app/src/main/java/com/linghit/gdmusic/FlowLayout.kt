package com.linghit.gdmusic

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 流式布局：子 View 从左到右排列，一行排满后自动换到下一行。
 * 用于首页自定义资源位（不限量），避免横向滚动。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val hSpacing: Int
    private val vSpacing: Int

    init {
        val dm = context.resources.displayMetrics.density
        hSpacing = (8 * dm).toInt()
        vSpacing = (8 * dm).toInt()
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean {
        return p is MarginLayoutParams
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthSpec, heightSpec)
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x + cw > paddingLeft + width && x > paddingLeft) {
                x = paddingLeft
                y += lineHeight + vSpacing
                lineHeight = 0
            }
            x += cw + hSpacing
            if (ch > lineHeight) lineHeight = ch
        }
        val totalHeight = y + lineHeight + paddingBottom
        val measuredHeight = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            totalHeight
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x + cw > paddingLeft + width && x > paddingLeft) {
                x = paddingLeft
                y += lineHeight + vSpacing
                lineHeight = 0
            }
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += cw + hSpacing
            if (ch > lineHeight) lineHeight = ch
        }
    }
}
