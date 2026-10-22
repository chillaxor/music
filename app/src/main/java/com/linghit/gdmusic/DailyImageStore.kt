package com.linghit.gdmusic

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日一图（全屏沉浸图背景）：用户给一个图片文件夹，App 按日期自动轮换。
 *
 * 设计要点：
 *  - 文件夹用 SAF（ACTION_OPEN_DOCUMENT_TREE）选一次并持久化授权，
 *    用户之后往文件夹里扔图/删图即可，不用再进 App 配置（ Android 4.4~14 通吃）
 *  - 轮换规则：年内第几天 % 图数量 → 放 N 张就 N 天一个循环，0 点自动换
 *  - 手动切：点一下首页问候行换下一张，记到当天头上；跨天自动失效回到轮换
 *  - 图多图少都行：空文件夹/文件夹失效时返回空，首页回落到主题渐变底色
 */
object DailyImageStore {
    private const val PREF = "music"
    private const val KEY_TREE = "daily_tree_uri"
    private const val KEY_MANUAL_DATE = "daily_manual_date"
    private const val KEY_MANUAL_IDX = "daily_manual_idx"

    /** 认得出的图片扩展名（小写比较） */
    private val EXTS = listOf("jpg", "jpeg", "png", "webp", "bmp")

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun dayOfYear(): Int =
        Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    /** 已配置的图片文件夹 Uri；没配过返回 null */
    fun treeUri(ctx: Context): Uri? =
        prefs(ctx).getString(KEY_TREE, null)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    fun setTreeUri(ctx: Context, uri: Uri?) {
        prefs(ctx).edit().apply {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }.apply()
    }

    /**
     * 列出文件夹里的图片（按文件名排序，保证「第几张」稳定）。
     * 文件夹被删/SD 卡拔出/授权失效时返回空列表，首页走兜底底色，不崩。
     */
    fun listImages(ctx: Context): List<Uri> {
        val tree = treeUri(ctx) ?: return emptyList()
        return runCatching {
            val dir = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
            dir.listFiles()
                .filter { f ->
                    f.isFile && f.name?.substringAfterLast('.', "")?.lowercase() in EXTS
                }
                .sortedBy { it.name ?: "" }
                .map { it.uri }
        }.getOrDefault(emptyList())
    }

    fun imageCount(ctx: Context): Int = listImages(ctx).size

    /** 文件夹显示名（设置页/首页提示用）；读不到返回空串 */
    fun folderName(ctx: Context): String = runCatching {
        treeUri(ctx)?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: ""
    }.getOrDefault("")

    /**
     * 今天该显示第几张（0 基）。手动切过且还是今天 → 用手动值；
     * 否则 年内第几天 % 图数。没有图返回 -1。
     */
    fun todayIndex(ctx: Context): Int {
        val n = listImages(ctx).size
        if (n == 0) return -1
        val md = prefs(ctx).getString(KEY_MANUAL_DATE, null)
        val mi = prefs(ctx).getInt(KEY_MANUAL_IDX, -1)
        if (md == today() && mi in 0 until n) return mi
        return dayOfYear() % n
    }

    /** 今天该显示的图；没配文件夹或文件夹里没图返回 null */
    fun todayImage(ctx: Context): Uri? {
        val list = listImages(ctx)
        return list.getOrNull(todayIndex(ctx))
    }

    /** 手动切到下一张（记到今天头上，跨天自动失效）。返回新图，没图返回 null */
    fun nextManual(ctx: Context): Uri? {
        val list = listImages(ctx)
        if (list.isEmpty()) return null
        val next = (todayIndex(ctx) + 1) % list.size
        prefs(ctx).edit()
            .putString(KEY_MANUAL_DATE, today())
            .putInt(KEY_MANUAL_IDX, next)
            .apply()
        return list[next]
    }
}
