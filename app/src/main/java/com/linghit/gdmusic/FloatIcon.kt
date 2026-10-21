package com.linghit.gdmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 悬浮按钮自定义图标：相册图片 → **尺寸受限**解码 → 圆形裁剪 → 落盘。
 *
 * ⚠️ 为什么必须限制尺寸：手机相册图常见 4000×3000 甚至 1 亿像素，
 * 直接 BitmapFactory.decodeStream 原图 = 一张 ARGB_8888 位图就要
 * 10000×7500×4 ≈ 300MB 堆内存，悬浮球进程当场 OOM。
 *
 * 三步防守：
 *  1. inJustDecodeBounds 只读尺寸不解码（几乎不耗内存），先算 inSampleSize
 *     （2 的幂）把位图边长砍到 <= SAMPLE_TARGET；
 *  2. 采样解码后再用 Matrix 精确缩放到 [SIZE]×[SIZE] 正方形（居中裁方）；
 *  3. BitmapShader + drawCircle 裁圆，PNG 压缩写 files/float_btn_icon.png。
 * 最终落盘图恒定 256×256（几十 KB），之后加载无任何内存风险。
 */
object FloatIcon {
    private const val TAG = "FloatIcon"
    private const val SIZE = 256                 // 最终图标边长（显示 52dp，2 倍富余）
    private const val SAMPLE_TARGET = 512        // 采样阶段砍到的边长上限
    private const val FILE_NAME = "float_btn_icon.png"

    private fun iconFile(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 从相册 Uri 生成自定义图标；失败返回 null（坏图/尺寸异常/OOM 都拦在这里） */
    fun saveFromUri(ctx: Context, uri: Uri): String? {
        return try {
            // ① 只读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "读不到图片尺寸（可能不是有效图片）")
                return null
            }
            // ② 采样解码：长边砍到 SAMPLE_TARGET 以内
            var sample = 1
            while (w / sample > SAMPLE_TARGET || h / sample > SAMPLE_TARGET) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            // ③ 缩放裁方 + 裁圆
            val circle = toCircle(raw)
            if (circle !== raw) raw.recycle()
            FileOutputStream(iconFile(ctx)).use { out ->
                circle.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            circle.recycle()
            Log.d(TAG, "图标已生成：原图 ${w}x${h} 采样/$sample → ${SIZE}x${SIZE}")
            iconFile(ctx).absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "生成图标失败: ${e.message}")
            null
        }
    }

    /** 居中裁方 + 缩放到 SIZE，再裁成圆形 */
    private fun toCircle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        // 短边铺满 SIZE，长边居中（= 居中裁方）
        val scale = SIZE.toFloat() / minOf(src.width, src.height)
        val dx = (SIZE - src.width * scale) / 2f
        val dy = (SIZE - src.height * scale) / 2f
        paint.shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, paint)
        return out
    }

    /** 加载自定义图标（落盘图恒定 256px，安全）；没有或坏了返回 null */
    fun load(ctx: Context): Bitmap? {
        val f = iconFile(ctx)
        if (!f.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull()?.takeIf { !it.isRecycled }
    }

    /** 恢复默认 emoji 图标 */
    fun clear(ctx: Context) {
        runCatching { iconFile(ctx).delete() }
    }
}
