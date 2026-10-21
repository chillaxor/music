package com.linghit.gdmusic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 悬浮按钮自定义图标：相册图片 → 尺寸受限解码 → 圆形裁剪 → 落盘。
 * 受限解码/落盘逻辑全部委托 [ImagePrep]（与启动背景图共用）。
 */
object FloatIcon {
    private const val TAG = "FloatIcon"
    private const val SIZE = 256                 // 最终图标边长（显示 52dp，2 倍富余）
    private const val SAMPLE_TARGET = 512        // 采样阶段砍到的边长上限
    private const val FILE_NAME = "float_btn_icon.png"

    private fun iconFile(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 从相册 Uri 生成自定义图标；失败返回 null（坏图/尺寸异常/OOM 都拦在这里） */
    fun saveFromUri(ctx: Context, uri: Uri): String? {
        val raw = ImagePrep.decodeSampled(ctx, uri, SAMPLE_TARGET) ?: return null
        val circle = ImagePrep.squareCrop(raw, SIZE)
        if (circle !== raw) raw.recycle()
        val path = ImagePrep.save(ctx, circle, FILE_NAME, jpegQuality = null)
        circle.recycle()
        Log.d(TAG, "图标已生成 → ${SIZE}x${SIZE}")
        return path
    }

    /** 加载自定义图标（落盘图恒定 256px，安全）；没有或坏了返回 null */
    fun load(ctx: Context): Bitmap? = ImagePrep.loadFile(iconFile(ctx).absolutePath)

    /** 恢复默认 emoji 图标 */
    fun clear(ctx: Context) {
        runCatching { iconFile(ctx).delete() }
    }
}
