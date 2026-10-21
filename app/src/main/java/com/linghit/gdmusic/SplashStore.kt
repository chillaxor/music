package com.linghit.gdmusic

import android.content.Context

/**
 * 启动背景图配置（闪屏页用）。
 *
 * 存储形态：用户选图后经 [ImagePrep] 受限解码（按屏幕比例 centerCrop、宽 1080、
 * JPEG 85）落盘 files/splash_bg.jpg，这里只记路径 + 开关。
 */
object SplashStore {
    private const val FILE = "splash_bg"
    private const val KEY_PATH = "path"
    private const val KEY_ENABLED = "enabled"
    private const val FILE_NAME = "splash_bg.jpg"

    fun file(ctx: Context) = java.io.File(ctx.filesDir, FILE_NAME)

    /** 是否启用了自定义启动背景图（默认关：用纯色兜底，行为与旧版一致） */
    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 自定义背景图路径；未设置返回 null */
    fun path(ctx: Context): String? =
        prefs(ctx).getString(KEY_PATH, null)?.takeIf { it.isNotBlank() }

    fun setPath(ctx: Context, path: String?) {
        prefs(ctx).edit().apply {
            if (path.isNullOrBlank()) remove(KEY_PATH)
            else putString(KEY_PATH, path)
        }.apply()
    }

    /** 记路径并开启（选图成功时调） */
    fun enable(ctx: Context, path: String) {
        prefs(ctx).edit().putString(KEY_PATH, path).putBoolean(KEY_ENABLED, true).apply()
    }

    /** 恢复默认：删文件 + 清记录 */
    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
        prefs(ctx).edit().remove(KEY_PATH).putBoolean(KEY_ENABLED, false).apply()
    }

    /** 当前是否有可用的自定义背景图（开关开着且文件在） */
    fun hasCustom(ctx: Context): Boolean =
        enabled(ctx) && path(ctx)?.let { java.io.File(it).exists() } == true

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
