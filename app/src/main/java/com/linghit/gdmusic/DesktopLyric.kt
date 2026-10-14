package com.linghit.gdmusic

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 桌面歌词控制器（移植自 mf_audit 的 LyricUtilModule + 自增 LRC 时间轴同步）。
 * 负责：开关持久化、悬浮窗权限检查/跳转、LRC 解析、按播放进度同步当前行、外观（位置/宽度/字号/配色）持久化。
 */
object DesktopLyric {

    private const val TAG = "DesktopLyric"
    private lateinit var ctx: Context

    private var window: LyricWindow? = null
    private var lines: List<LrcLine> = emptyList()
    private var lastIdx = -1
    private var rawLyric: String = ""

    // ---------- 开关 ----------
    var enabled: Boolean
        get() = prefs().getBoolean(KEY_ENABLED, false)
        set(v) {
            prefs().edit().putBoolean(KEY_ENABLED, v).apply()
            if (v) show() else hide()
        }

    // ---------- 外观（持久化） ----------
    var topPercent: Float
        get() = prefs().getFloat(KEY_TOP, 0.12f)
        set(v) { prefs().edit().putFloat(KEY_TOP, v).apply(); window?.setTopPercent(v.toDouble()) }
    var leftPercent: Float
        get() = prefs().getFloat(KEY_LEFT, 0.5f)
        set(v) { prefs().edit().putFloat(KEY_LEFT, v).apply(); window?.setLeftPercent(v.toDouble()) }
    var widthPercent: Float
        get() = prefs().getFloat(KEY_WIDTH, 0.86f)
        set(v) { prefs().edit().putFloat(KEY_WIDTH, v).apply(); window?.setWidthPercent(v.toDouble()) }
    var fontSizeSp: Float
        get() = prefs().getFloat(KEY_FONT, 16f)
        set(v) { prefs().edit().putFloat(KEY_FONT, v).apply(); window?.setFontSize(v) }
    /** 0=白字半透明黑底 1=黑字白底 2=黄字透明底 */
    var colorIndex: Int
        get() = prefs().getInt(KEY_COLOR, 0)
        set(v) { prefs().edit().putInt(KEY_COLOR, v).apply(); applyColors() }

    fun init(context: Context) { ctx = context.applicationContext }

    private fun prefs() = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    private const val KEY_ENABLED = "desk_lyric_on"
    private const val KEY_TOP = "desk_lyric_top"
    private const val KEY_LEFT = "desk_lyric_left"
    private const val KEY_WIDTH = "desk_lyric_width"
    private const val KEY_FONT = "desk_lyric_font"
    private const val KEY_COLOR = "desk_lyric_color"

    // ---------- 权限 ----------
    fun hasPermission(): Boolean = Settings.canDrawOverlays(ctx)

    /** 跳系统设置让用户授予悬浮窗权限 */
    fun requestPermission() {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // ---------- 显示/隐藏 ----------
    /** 尝试显示；无权限或抛异常都返回 false（不影响播放） */
    fun show(): Boolean {
        if (!::ctx.isInitialized) return false
        if (!hasPermission()) return false
        if (window?.isShowing == true) return true
        return runCatching {
            val w = LyricWindow(ctx)
            w.show(currentText(), fontSizeSp, topPercent.toDouble(), leftPercent.toDouble(),
                widthPercent.toDouble(), textColor(), bgColor())
            window = w
            lastIdx = -1
            onProgress(currentPos)
            true
        }.getOrElse { Log.w(TAG, "show fail", it); false }
    }

    fun hide() { runCatching { window?.hide() }; window = null; lastIdx = -1 }

    fun isShowing() = window?.isShowing == true

    /** 当前应显示的文本（由 rawLyric + 播放位置决定，show 时也要用到） */
    private var currentPos = 0L
    private fun currentText(): String {
        if (lines.isEmpty()) return rawLyric.ifBlank { "♪ 桌面歌词已开启" }
        val i = indexOf(currentPos)
        return if (i in lines.indices) lines[i].text else rawLyric
    }

    // ---------- 歌词 ----------
    /** 设置歌词原文（LRC 或纯文本） */
    fun setLyrics(raw: String?) {
        rawLyric = raw ?: ""
        lines = parseLrc(rawLyric)
        lastIdx = -1
        if (window?.isShowing == true) window?.setText(currentText())
    }

    /** 播放进度回调：由主播放循环驱动，只在所在行变化时刷新 */
    fun onProgress(posMs: Long) {
        currentPos = posMs
        if (window?.isShowing != true) return
        if (lines.isEmpty()) return
        val i = indexOf(posMs)
        if (i != lastIdx) {
            lastIdx = i
            window?.setText(if (i in lines.indices) lines[i].text else "")
        }
    }

    private fun indexOf(pos: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private fun textColor(): Int = when (colorIndex) {
        1 -> Color.parseColor("#FF111111")
        2 -> Color.parseColor("#FFFFD54F")
        else -> Color.parseColor("#FFFFFFFF")
    }
    private fun bgColor(): Int = when (colorIndex) {
        1 -> Color.parseColor("#F2FFFFFF")
        2 -> Color.parseColor("#00000000")
        else -> Color.parseColor("#B0111116")
    }
    private fun applyColors() { window?.setColors(textColor(), bgColor()) }

    /** 当前配色名（设置页展示用） */
    fun colorName(): String = when (colorIndex) {
        1 -> "黑字白底"; 2 -> "黄字透明"; else -> "白字黑底"
    }
}

/** 一行 LRC 歌词 */
data class LrcLine(val timeMs: Long, val text: String)

/** 解析 LRC：支持 `[mm:ss.xx]` 多时间戳、`[mm:xx]`，忽略元信息行；无时间戳时返回空列表（按纯文本整段显示） */
fun parseLrc(raw: String): List<LrcLine> {
    if (raw.isBlank()) return emptyList()
    val out = mutableListOf<LrcLine>()
    val re = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    raw.lines().forEach { line ->
        val text = line.substringAfterLast(']', "").trim().ifBlank { return@forEach }
        val times = re.findAll(line).mapNotNull { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val fracRaw = m.groupValues[3]
            val frac = when (fracRaw.length) {
                0 -> 0L; 1 -> fracRaw.toLong() * 100; 2 -> fracRaw.toLong() * 10; else -> fracRaw.take(2).toLong() * 10 + fracRaw.substring(2).toLong()
            }
            min * 60000 + sec * 1000 + frac
        }.toList()
        if (times.isEmpty()) return@forEach
        times.forEach { t -> out.add(LrcLine(t, text)) }
    }
    return out.sortedBy { it.timeMs }
}
