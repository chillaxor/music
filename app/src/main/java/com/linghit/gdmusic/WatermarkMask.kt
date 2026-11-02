package com.linghit.gdmusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.hypot
import kotlin.math.min

/**
 * 自加水印自动定位：按 WatermarkEngine 的网格几何，反推被水印覆盖的区域 → 生成 mask。
 * 用与加水印完全一致的旋转 + 平铺坐标，把每个文字实例近似成一个旋转矩形来标记覆盖区，
 * 再外扩几像素盖住抗锯齿/描边阴影边缘。
 *
 * 仅对「本 App 加水印并导出」的图有效（依赖 WatermarkActivity 写的 sidecar Config）。
 */
object WatermarkMask {

    fun rebuild(cfg: WatermarkEngine.Config, w: Int, h: Int): Bitmap {
        val minEdge = min(w, h)
        val textSize = (minEdge * cfg.sizeRatio).coerceAtLeast(12f)
        val paint = Paint().apply {
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val text = cfg.effectiveText()
        val tw = paint.measureText(text).coerceAtLeast(1f)
        val gapX = tw * 0.8f / cfg.density
        val stepX = tw + gapX
        val stepY = textSize * 3.2f / cfg.density

        val cx = w / 2f
        val cy = h / 2f
        val R = hypot(w.toDouble(), h.toDouble()).toFloat()

        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)
        val rp = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        val halfW = tw / 2f + 4f
        val halfH = textSize * 0.62f

        c.save()
        c.rotate(cfg.angleDeg, cx, cy)
        var row = 0
        var y = cy - R
        while (y <= cy + R) {
            val offset = if (row % 2 == 0) 0f else stepX / 2f
            var x = cx - R + offset
            while (x <= cx + R) {
                c.drawRect(x - halfW, y - halfH, x + halfW, y + halfH, rp)
                x += stepX
            }
            y += stepY
            row++
        }
        c.restore()

        return dilate(base, 3)
    }

    /** 在白色像素的 bbox 内做半径 r 的膨胀，盖住边缘半透明/描边 */
    private fun dilate(src: Bitmap, r: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (i in px.indices) {
            if (((px[i] shr 16) and 0xFF) > 127) {
                val x = i % w; val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        minX = (minX - r).coerceAtLeast(0); minY = (minY - r).coerceAtLeast(0)
        maxX = (maxX + r).coerceAtMost(w - 1); maxY = (maxY + r).coerceAtMost(h - 1)
        val out = src.copy(Bitmap.Config.ARGB_8888, false)
        val op = IntArray(w * h)
        out.getPixels(op, 0, w, 0, 0, w, h)
        for (y in minY..maxY) for (x in minX..maxX) {
            val i = y * w + x
            if (((px[i] shr 16) and 0xFF) > 127) continue
            var hit = false
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) {
                    val j = ny * w + nx
                    if (((px[j] shr 16) and 0xFF) > 127) { hit = true; break }
                }
            }
            if (hit) op[i] = Color.WHITE
        }
        out.setPixels(op, 0, w, 0, 0, w, h)
        return out
    }
}
