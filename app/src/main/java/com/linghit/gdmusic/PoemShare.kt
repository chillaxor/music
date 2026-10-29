package com.linghit.gdmusic

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File

/**
 * 「生成分享图」：把一首诗渲染成竖版图片（1080x1440，3:4，适合朋友圈/小红书），
 * 存进 app 缓存目录后用 FileProvider 发出——不落相册、不申请存储权限。
 */
object PoemShare {
    private const val W = 1080
    private const val H = 1440
    private const val MAX_LINES = 14

    fun share(ctx: Context, p: Poem) {
        val bmp = render(p)
        val dir = File(ctx.cacheDir, "poem_share").apply { mkdirs() }
        val name = "poem_${p.id.ifBlank { System.currentTimeMillis().toString() }}.png"
        val file = File(dir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val text = buildString {
            append(p.title.ifBlank { "无题" })
            val meta = listOf(p.dynasty, p.author).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) append(" · ").append(meta)
            append("\n")
            val lines = if (p.lines.isNotEmpty()) p.lines else listOf(p.quote)
            lines.forEach { append(it).append("\n") }
            append("—— 每日一诗 · 从前慢")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "分享诗词"))
    }

    private fun render(p: Poem): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 底：墨色 + 一层暖调微光
        c.drawColor(Color.parseColor("#1C1A18"))
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(Color.parseColor("#3A2C22"), Color.parseColor("#1C1A18")),
                null, Shader.TileMode.CLAMP
            )
        })

        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textAlign = Paint.Align.CENTER
        }

        // 诗名
        paint.color = Color.parseColor("#F5EFE6")
        paint.textSize = 68f
        c.drawText(p.title.ifBlank { "无题" }, W / 2f, 250f, paint)

        // 朝代 · 作者
        val meta = listOf(p.dynasty, p.author).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            paint.color = Color.parseColor("#B8A99A")
            paint.textSize = 34f
            c.drawText(meta, W / 2f, 322f, paint)
        }

        // 分隔短线
        paint.color = Color.parseColor("#5A4636")
        paint.strokeWidth = 2f
        c.drawLine(W / 2f - 60f, 382f, W / 2f + 60f, 382f, paint)

        // 诗身：长诗自动缩字号并截断，保证不溢出画布
        val raw = if (p.lines.isNotEmpty()) p.lines else listOf(p.quote)
        val shown = if (raw.size > MAX_LINES) raw.take(MAX_LINES) + "……" else raw
        paint.textSize = when {
            shown.size <= 8 -> 46f
            shown.size <= 12 -> 40f
            else -> 34f
        }
        paint.color = Color.parseColor("#EDE4D8")
        val spacing = paint.textSize * 1.7f
        val regionTop = 440f
        val regionBottom = H - 280f
        var y = (regionTop + regionBottom) / 2f - spacing * (shown.size - 1) / 2f + paint.textSize * 0.7f
        shown.forEach {
            c.drawText(it, W / 2f, y, paint)
            y += spacing
        }

        // 底部落款
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 30f
        paint.color = Color.parseColor("#9A8B7C")
        c.drawText("每日一诗 · 从前慢", 80f, H - 120f, paint)

        // 朱印
        c.drawRoundRect(
            W - 260f, H - 200f, W - 120f, H - 60f, 12f, 12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9E3D36") }
        )
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 42f
        paint.color = Color.parseColor("#F5EFE6")
        c.drawText("从前慢", W - 190f, H - 108f, paint)

        return bmp
    }
}
