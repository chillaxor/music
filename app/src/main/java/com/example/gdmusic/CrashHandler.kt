package com.example.gdmusic

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获器：把未捕获异常的堆栈写入 App 私有文件（crash_log.txt）。
 * 用途：App 闪退后无需连电脑 adb，重开 App 进「设置 → 崩溃日志」即可查看/复制报错。
 * 只保留最近若干条，避免文件无限增长。
 */
object CrashHandler {

    private const val FILE = "crash_log.txt"
    private const val MAX_KEEP = 5 // 最多保留最近 5 次崩溃

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { save(context, thread, throwable) }
            // 交给系统默认处理（弹「已停止运行」并结束进程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun save(context: Context, thread: Thread, e: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("===== 崩溃 $time =====\n")
        sb.append("构建: ${BuildConfig.BUILD_TIME} · 版本 ${BuildConfig.VERSION_NAME}\n")
        sb.append("线程: ${thread.name}\n")
        sb.append("异常: ${e.javaClass.name}\n")
        sb.append("信息: ${e.message}\n\n")
        e.stackTrace.forEach { sb.append("    at $it\n") }
        var cause = e.cause
        while (cause != null) {
            sb.append("\nCaused by: ${cause.javaClass.name}: ${cause.message}\n")
            cause.stackTrace.forEach { sb.append("    at $it\n") }
            cause = cause.cause
        }
        sb.append("\n")

        val file = File(context.filesDir, FILE)
        val old = if (file.exists()) file.readText() else ""
        val merged = old + sb.toString()
        // 按 "===== 崩溃" 分块，只保留最近 MAX_KEEP 块
        val blocks = merged.split("===== 崩溃").filter { it.isNotBlank() }
        val kept = blocks.takeLast(MAX_KEEP)
        file.writeText(kept.joinToString("===== 崩溃") { if (it.startsWith(" ")) it else " " + it }
            .let { if (kept.isNotEmpty()) "===== 崩溃" + it.removePrefix("===== 崩溃") else "" })
    }

    /** 读取崩溃日志内容（无崩溃则返回空串） */
    fun read(context: Context): String = runCatching {
        val f = File(context.filesDir, FILE)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    fun clear(context: Context) = runCatching { File(context.filesDir, FILE).delete() }
}
