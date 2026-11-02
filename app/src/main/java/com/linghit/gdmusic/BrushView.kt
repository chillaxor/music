package com.linghit.gdmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 涂抹选区画布：在源图之上用手指圈选水印区域。
 * 关键点：mask 按【原图像素坐标】绘制，与源图严格对齐，去掉水印时 1:1 对应。
 * mask 语义：不透明(白) = 需修复；透明 = 不动。擦除用 CLEAR 还原透明。
 */
class BrushView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var src: Bitmap? = null
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private var brushPx = 30
    private var erasing = false

    private val undoStack = ArrayDeque<Bitmap>()
    private val maxUndo = 10

    private val srcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 白→红染色滤镜：白(1,1,1)* (1,0,0)=红；透明保持透明 → 只把笔触染成半透明红覆盖层
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        alpha = 160
    }
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setImage(bmp: Bitmap) {
        src = bmp
        mask?.recycle()
        mask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(mask!!)
        undoStack.clear()
        computeFit()
        invalidate()
    }

    /** 自动定位时直接灌入一张与源图同尺寸的 mask */
    fun setInitialMask(m: Bitmap) {
        if (m.width != src?.width || m.height != src?.height) return
        pushUndo()
        mask?.recycle()
        mask = m.copy(Bitmap.Config.ARGB_8888, true)
        maskCanvas = Canvas(mask!!)
        invalidate()
    }

    fun setBrushRadius(px: Int) { brushPx = px.coerceAtLeast(4) }
    fun setErasing(on: Boolean) { erasing = on }

    fun clearMask() {
        val m = mask ?: return
        pushUndo()
        m.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun hasMask(): Boolean {
        val m = mask ?: return false
        val px = IntArray(m.width * m.height)
        m.getPixels(px, 0, m.width, 0, 0, m.width, m.height)
        return px.any { ((it shr 16) and 0xFF) > 127 }
    }

    fun getMask(): Bitmap? = mask

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        mask?.recycle()
        mask = prev
        maskCanvas = Canvas(mask!!)
        invalidate()
    }

    private fun pushUndo() {
        val m = mask ?: return
        undoStack.addLast(m.copy(Bitmap.Config.ARGB_8888, false))
        while (undoStack.size > maxUndo) undoStack.removeFirst()
    }

    private fun computeFit() {
        val bmp = src ?: return
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        scale = min(vw / bmp.width, vh / bmp.height)
        offX = (vw - bmp.width * scale) / 2f
        offY = (vh - bmp.height * scale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFit()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = src ?: return
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val rect = android.graphics.RectF(offX, offY, offX + dw, offY + dh)
        canvas.drawBitmap(bmp, null, rect, srcPaint)
        mask?.let { canvas.drawBitmap(it, null, rect, maskPaint) }
    }

    private var drawing = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (src == null || maskCanvas == null) return false
        val sx = (event.x - offX) / scale
        val sy = (event.y - offY) / scale
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                pushUndo()
                stroke(sx, sy)
                lastX = sx; lastY = sy
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                // 插值补点，快速滑动不留断点
                val dx = sx - lastX; val dy = sy - lastY
                val dist = kotlin.math.hypot(dx, dy)
                val steps = (dist / (brushPx / 2f)).toInt().coerceAtLeast(1)
                for (s in 1..steps) {
                    stroke(lastX + dx * s / steps, lastY + dy * s / steps)
                }
                lastX = sx; lastY = sy
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                return true
            }
        }
        return false
    }

    private fun stroke(cx: Float, cy: Float) {
        val c = maskCanvas ?: return
        drawPaint.xfermode = if (erasing) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        drawPaint.color = Color.WHITE
        c.drawCircle(cx, cy, brushPx.toFloat(), drawPaint)
    }
}
