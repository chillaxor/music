package com.linghit.gdmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * 风格引擎：把 ThemePrefs.StylePack 的「材质契约」翻译成真正的 Drawable / 颜色。
 *
 * 为什么不全用 XML：layer-list 里的 gradient 无法引用 ?attr（项目旧注释已踩过），
 * 多层玻璃亮边、顶部高光、拟物双向明暗边、随图片亮度变化的蒙层，
 * 都只能代码生成。XML 只负责纯色 token（attrs.xml），其余交给这里。
 *
 * 背景图兼容（硬约束）：
 *  - 5 套风格全部保留壁纸，只改「加工方式」：去饱和 + 罩染 + 亮度自适应蒙层。
 *  - 深色系（玻璃/墨夜）用黑蒙层：亮图自动加压暗，白字不糊。
 *  - 浅色系（素纸/线稿/软陶）用浅色蒙层：暗图自动加提亮，深墨字不糊。
 *  - 这样卡内 / 卡外文字都可以用风格固定字色，不需要逐张图动态切深浅。
 *  - 关闭壁纸时只隐藏显示层，露该风格自带的兜底渐变，不碰 DailyImageStore。
 */
object StyleEngine {

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 当前风格（每次现读，切换后 recreate 自然生效） */
    fun pack(ctx: Context) = ThemePrefs.currentPack(ctx)

    /** 卡片背景：按材质生成。玻璃=三层折射亮边+磨砂本体+高光；其它=实底/描边/线框/浮雕 */
    fun cardDrawable(ctx: Context, p: ThemePrefs.StylePack = pack(ctx)): Drawable {
        val r = dp(ctx, p.cardRadiusDp).toFloat()
        return when (p.material) {
            ThemePrefs.Material.GLASS -> {
                val edge = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        withAlpha(p.cardEdge, 0.66f),
                        withAlpha(p.cardEdge, 0.20f),
                        withAlpha(p.cardEdge, 0.03f)
                    )
                ).apply { cornerRadius = r }
                val body = GradientDrawable().apply {
                    setColor(p.cardFill); cornerRadius = (r - dp(ctx, 1.2f)).coerceAtLeast(0f)
                }
                val highlight = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        withAlpha(p.cardHighlight, 0.17f),
                        withAlpha(p.cardHighlight, 0.04f),
                        0x00000000
                    )
                ).apply { cornerRadius = (r - dp(ctx, 1.2f)).coerceAtLeast(0f) }
                val inset = dp(ctx, 1.2f)
                LayerDrawable(arrayOf(edge, body, highlight)).apply {
                    setLayerInset(1, inset, inset, inset, inset)
                    setLayerInset(2, inset, inset, inset, inset)
                }
            }
            ThemePrefs.Material.CLAY -> {
                // 拟物软浮雕：暗边在下、亮边在上，主体居中盖住，露上下各 1.5dp 双向边
                val shadow = GradientDrawable().apply { setColor(p.cardHighlight); cornerRadius = r }
                val light = GradientDrawable().apply { setColor(p.cardEdge); cornerRadius = r }
                val body = GradientDrawable().apply { setColor(p.cardFill); cornerRadius = r }
                val d = dp(ctx, 1.5f)
                LayerDrawable(arrayOf(shadow, light, body)).apply {
                    setLayerInset(0, 0, d * 2, 0, 0)
                    setLayerInset(1, 0, 0, 0, d * 2)
                    setLayerInset(2, 0, d, 0, d)
                }
            }
            else -> GradientDrawable().apply {
                setColor(p.cardFill)
                cornerRadius = r
                if (p.cardStrokeDp > 0f) setStroke(dp(ctx, p.cardStrokeDp), p.cardStrokeColor)
            }
        }
    }

    fun card(ctx: Context, v: View, p: ThemePrefs.StylePack = pack(ctx)) {
        v.background = cardDrawable(ctx, p)
    }

    /** 外层 MaterialCardView 的圆角也要跟着风格走，否则内层方角会被外层 18dp 裁掉 */
    fun outerCardRadius(ctx: Context, c: MaterialCardView, p: ThemePrefs.StylePack = pack(ctx)) {
        c.radius = dp(ctx, p.cardRadiusDp).toFloat()
    }

    /** pill（页签 / 续听 chip / 诗卡小按钮） */
    fun pillDrawable(ctx: Context, active: Boolean, p: ThemePrefs.StylePack = pack(ctx)): Drawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, 19f).toFloat()
            setColor(if (active) p.pillActive else p.pillIdle)
            if (!active && p.material == ThemePrefs.Material.LINE) {
                setStroke(dp(ctx, 1f), p.cardStrokeColor)
            }
        }

    fun pill(ctx: Context, v: TextView, active: Boolean, p: ThemePrefs.StylePack = pack(ctx)) {
        v.background = pillDrawable(ctx, active, p)
        v.setTextColor(if (active) p.pillActiveText else p.pillIdleText)
    }

    /** 底部播放条：颜色 + 圆角 + 线稿风格的细描边 */
    fun playerBar(ctx: Context, c: MaterialCardView, p: ThemePrefs.StylePack = pack(ctx)) {
        c.setCardBackgroundColor(p.playerBarFill)
        c.radius = dp(ctx, p.playerBarRadiusDp).toFloat()
        if (p.material == ThemePrefs.Material.LINE) {
            c.strokeWidth = dp(ctx, 1f)
            c.strokeColor = p.cardStrokeColor
        } else {
            c.strokeWidth = 0
        }
    }

    /** 快捷位徽章软底：主题色 33% 透明（沿用原逻辑，只是 primary 换成风格固定色） */
    fun badgeSoft(p: ThemePrefs.StylePack): Int =
        (p.primary and 0x00FFFFFF) or 0x55000000.toInt()

    /** 兜底底色：关掉壁纸 / 没配图片文件夹时露出的、每套风格各不相同的渐变 */
    fun fallback(ctx: Context, v: View, p: ThemePrefs.StylePack = pack(ctx)) {
        v.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(p.fallbackA, p.fallbackB)
        )
    }

    /** 播放圆按钮：玻璃保持中性冷灰，其余风格用主色渐变 */
    fun playButton(ctx: Context, v: View, p: ThemePrefs.StylePack = pack(ctx)) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = if (p.material == ThemePrefs.Material.GLASS)
                intArrayOf(0xFF46525E.toInt(), 0xFF2E3842.toInt())
            else intArrayOf(p.primary, p.primaryDark)
            orientation = GradientDrawable.Orientation.TL_BR
        }
    }

    /**
     * 采样图片平均亮度（0~255）。缩小到 16×16 再遍历，避免扫全图。
     * 必须在 IO 线程调用：解码后的原图可能接近 4000px。
     */
    fun avgLuma(bmp: Bitmap): Int {
        return try {
            val tiny = Bitmap.createScaledBitmap(bmp, 16, 16, true)
            var sum = 0.0
            for (y in 0 until 16) for (x in 0 until 16) {
                val c = tiny.getPixel(x, y)
                sum += 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
            }
            if (tiny !== bmp) tiny.recycle()
            (sum / 256.0 + 0.5).toInt().coerceIn(0, 255)
        } catch (e: Exception) {
            128
        }
    }

    /**
     * 应用壁纸加工。返回最终蒙层 alpha（供日志/验收）。
     * @param bmp 已解码的当日图片；null = 没配文件夹或解码失败
     * @param enabled 设置页「显示背景图」开关
     * @param luma avgLuma() 的结果，由调用方在 IO 线程算好
     */
    fun applyWallpaper(
        ctx: Context,
        image: ImageView,
        dim: View,
        fallbackView: View,
        bmp: Bitmap?,
        enabled: Boolean,
        luma: Int,
        p: ThemePrefs.StylePack = pack(ctx)
    ): Float {
        fallback(ctx, fallbackView, p)
        if (!enabled || bmp == null) {
            image.visibility = View.GONE
            image.colorFilter = null
            // 关掉壁纸时用风格基准蒙层的三分之一，只给白字/深字垫一点底
            dim.background = ColorDrawable(withAlpha(p.wall.tint, p.wall.scrimBase / 3f))
            val why = if (!enabled) "switch-off" else "no-image"
            Log.i("WallpaperAdapt", "style=${p.name} mode=$why scrim=${p.wall.scrimBase / 3f} fallback=${Integer.toHexString(p.fallbackA)}")
            return p.wall.scrimBase / 3f
        }
        image.visibility = View.VISIBLE
        image.imageAlpha = p.wall.imageAlpha
        image.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(p.wall.saturation) })
        // 深色系：L 高 → 加大黑蒙层；浅色系：L 低 → 加大浅罩染（scrimK 为负）
        val raw = p.wall.scrimBase + p.wall.scrimK * ((luma - 128f) / 127f)
        val a = raw.coerceIn(p.wall.scrimMin, p.wall.scrimMax)
        dim.background = ColorDrawable(withAlpha(p.wall.tint, a))
        Log.i("WallpaperAdapt", "style=${p.name} mode=on L=$luma scrim=$a " +
                "sat=${p.wall.saturation} imageAlpha=${p.wall.imageAlpha} text=${Integer.toHexString(p.textPrimary)}")
        return a
    }
}
