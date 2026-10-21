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
 * 相册图片 → **尺寸受限**解码 → 缩放落盘。悬浮球图标与启动背景图共用。
 *
 * ⚠️ 为什么必须限制尺寸：手机相册图常见 4000×3000 甚至 1 亿像素，
 * 直接 BitmapFactory.decodeStream 原图 = 一张 ARGB_8888 位图就要
 * 10000×7500×4 ≈ 300MB 堆内存，进程当场 OOM。
 *
 * 三步防守：
 *  1. inJustDecodeBounds 只读尺寸不解码（几乎不耗内存），先算 inSampleSize
 *     （2 的幂）把长边砍到采样上限；
 *  2. 采样解码后再用 Matrix 精确缩放到目标尺寸（居中裁方/裁比例）；
 *  3. 压缩写 files 目录，之后加载零风险。
 */
object ImagePrep {
    private const val TAG = "ImagePrep"

    /** 只读尺寸 → 采样解码，长边砍到 maxEdge 以内。失败/坏图返回 null */
    fun decodeSampled(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? {
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
            // ② 采样解码（2 的幂）
            var sample = 1
            while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp == null) {
                Log.w(TAG, "采样解码失败：原图 ${w}x${h}")
                null
            } else {
                Log.d(TAG, "解码：原图 ${w}x${h} 采样/$sample → ${bmp.width}x${bmp.height}")
                bmp
            }
        } catch (e: Throwable) {
            Log.w(TAG, "解码失败: ${e.message}")
            null
        }
    }

    /** 居中裁方 + 缩放到 size×size */
    fun squareCrop(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        // 短边铺满 size，长边居中（= 居中裁方）
        val scale = size.toFloat() / minOf(src.width, src.height)
        val dx = (size - src.width * scale) / 2f
        val dy = (size - src.height * scale) / 2f
        paint.shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    /** 按比例 centerCrop 到 ratioW:ratioH，再缩放到宽度 maxW，返回矩形位图（不裁圆） */
    fun scaleCrop(src: Bitmap, ratioW: Int, ratioH: Int, maxW: Int): Bitmap {
        // 目标比例下的居中裁取框
        val ratio = ratioW.toFloat() / ratioH.toFloat()
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val cropW: Int; val cropH: Int
        if (srcRatio > ratio) {          // 源更宽 → 裁两边
            cropH = src.height
            cropW = (src.height * ratio).toInt().coerceAtMost(src.width)
        } else {                          // 源更高 → 裁上下
            cropW = src.width
            cropH = (src.width / ratio).toInt().coerceAtMost(src.height)
        }
        val crop = Bitmap.createBitmap(
            src,
            (src.width - cropW) / 2,
            (src.height - cropH) / 2,
            cropW, cropH
        )
        val scale = maxW.toFloat() / cropW
        if (scale >= 1f && crop.width <= maxW) return crop   // 已经够小，不放大
        val out = Bitmap.createScaledBitmap(
            crop, (cropW * scale).toInt(), (cropH * scale).toInt(), true
        )
        if (out !== crop) crop.recycle()
        return out
    }

    /** 压缩落盘（PNG 无损 / JPEG 有损）。返回文件绝对路径，失败 null */
    fun save(ctx: Context, bmp: Bitmap, name: String, jpegQuality: Int?): String? {
        return try {
            val f = File(ctx.filesDir, name)
            FileOutputStream(f).use { out ->
                if (jpegQuality != null)
                    bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                else
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            f.absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "落盘失败 $name: ${e.message}")
            null
        }
    }

    /** 安全读落盘位图；文件不存在/坏了返回 null（调用方负责退回默认） */
    fun loadFile(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        if (!File(path).exists()) return null
        return runCatching { BitmapFactory.decodeFile(path) }
            .getOrNull()?.takeIf { !it.isRecycled }
    }

    /**
     * 原图字节级拷贝到 files 目录：不解码、不压缩、不裁切，保留相册原图的
     * 完整质量与尺寸（启动背景图用）。
     *
     * ⚠️ 与 decodeSampled+save 路线的区别：那条路会把 4800 万像素相机图压成
     * 1080 宽 JPEG 85%（用户看着糊）。这里只搬字节，内存占用约等于文件大小。
     * 显示时的清晰度/内存问题由 decodeFileSampled 解决（按屏幕采样解码）。
     */
    fun copyOriginal(ctx: Context, uri: Uri, name: String): String? {
        return try {
            val out = File(ctx.filesDir, name)
            val ins = ctx.contentResolver.openInputStream(uri) ?: return null
            ins.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
            Log.d(TAG, "原图拷贝：${out.length() / 1024}KB → ${out.name}")
            out.absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "原图拷贝失败: ${e.message}")
            null
        }
    }

    /**
     * 显示用：把落盘文件按目标边长采样解码（大图也不会 OOM）。
     * maxEdge 传屏幕长边×2 即可保证屏幕上 1:1 清晰。
     */
    fun decodeFileSampled(path: String?, maxEdge: Int): Bitmap? {
        if (path.isNullOrBlank() || !File(path).exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(path, opts)
            Log.d(TAG, "启动图显示解码：原图 ${w}x${h} 采样/$sample → ${bmp?.width}x${bmp?.height}")
            bmp
        } catch (e: Throwable) {
            Log.w(TAG, "显示解码失败: ${e.message}")
            null
        }
    }

    /** 删除文件（恢复默认时用） */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}
