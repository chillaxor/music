package com.linghit.gdmusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.hypot

/**
 * 整图斜铺水印引擎（纯函数，不依赖 UI）。
 *
 * 思路：
 *  1) 新建一张与原图同尺寸的 ARGB_8888 画布，先把原图整张画上去；
 *  2) 把画布「绕中心点旋转」angleDeg 度（负值=向左斜）；
 *  3) 在旋转后的坐标系里，像铺地砖一样一行行、一列列重复写水印文字。
 *     覆盖范围取「对角线长度」√(w²+h²)：旋转后原图落在一个对角矩形内，
 *     用对角线当半径就能把四个角全盖住，不会留白边——这跟右下角单角标是两码事；
 *  4) 文字半透明 + 黑色描边阴影，压在亮处或暗处都看得清；
 *  5) 旋转复位，返回带水印的图。
 *
 * 内存：调用方负责传入「已按长边采样解码」的位图（见 ImagePrep.decodeSampled），
 * 本引擎只新建一张同尺寸位图，不重复放大原图。
 */
object WatermarkEngine {

    data class Config(
        /** 水印文字（空串时回退默认） */
        val text: String = "从前慢",
        /** 旋转角度（度），负值向左斜、正值向右斜，常用 -45 ~ 0 */
        val angleDeg: Float = -28f,
        /** 文字不透明度 0..255（约 42% ≈ 110） */
        val alpha: Int = 110,
        /** 字号 = 图片短边 × 此比例（0.02 ~ 0.10） */
        val sizeRatio: Float = 0.045f,
        /** 疏密倍数：越大字越密、间距越小（0.5 ~ 2.0） */
        val density: Float = 1f,
        /** 文字颜色（白或黑） */
        val color: Int = Color.WHITE,
        /** 是否加黑色描边阴影，提升深浅背景可读性 */
        val shadow: Boolean = true
    ) {
        fun effectiveText(): String = text.ifBlank { "从前慢" }
    }

    /**
     * 在原图 [src] 上叠加斜铺水印，返回新 Bitmap（调用方负责 recycle）。
     */
    fun apply(src: Bitmap, cfg: Config): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)

        val minEdge = minOf(w, h)
        val text = cfg.effectiveText()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = (minEdge * cfg.sizeRatio).coerceAtLeast(12f)
            color = cfg.color
            alpha = cfg.alpha.coerceIn(0, 255)
            typeface = Typeface.DEFAULT_BOLD
            if (cfg.shadow) setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // 单个文字宽度 + 列间距（列间距随疏密变化）
        val tw = paint.measureText(text).coerceAtLeast(1f)
        val gapX = tw * 0.8f / cfg.density
        val stepX = tw + gapX
        // 行距 = 字号 × 3.2，随疏密变化
        val stepY = paint.textSize * 3.2f / cfg.density

        val cx = w / 2f
        val cy = h / 2f
        // 旋转后原图落在以中心为中点、边长=对角线 的正方形内 → 双轴各覆盖 ±R 必盖满
        val R = hypot(w.toDouble(), h.toDouble()).toFloat()

        c.save()
        c.rotate(cfg.angleDeg, cx, cy)
        var row = 0
        var y = cy - R
        while (y <= cy + R) {
            // 隔行错位半格，铺得更均匀、无竖直缝隙
            val offset = if (row % 2 == 0) 0f else stepX / 2f
            var x = cx - R + offset
            while (x <= cx + R) {
                c.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        c.restore()

        return out
    }
}
