package com.linghit.gdmusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生影视列表页专用下载器（不依赖任何 Activity / WebView）。
 *
 * 与播放器内下载（NativePlayerActivity.startDownload）同一套算法：
 * mp4 直下 / m3u8 拉索引 → 并发下 ts → ffmpeg concat 无损重封装 MP4。
 * 输出到 MediaStore 公共 Movies/从前慢（API29+），否则应用私有 Movies 目录。
 * 进度走系统通知，完成/失败 Toast。
 */
object NativeDownloader {
    private const val TAG = "VidNativeDl"
    private const val CHANNEL = "gdmusic_download"
    private const val NOTIFY_ID = 3001
    private const val DIR_NAME = "从前慢"

    private val HOP_BY_HOP = setOf(
        "host", "connection", "content-length", "transfer-encoding",
        "accept-encoding", "range", "upgrade", "proxy-connection", "keep-alive", "te"
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private data class DlOutput(
        val stream: OutputStream, val scanPath: String,
        val file: File? = null, val uri: android.net.Uri? = null
    )

    private var appCtx: Context? = null
    private var curTitle = ""
    private var lastPct = -1

    fun start(ctx: Context, rawUrl: String, headers: Map<String, String>, title: String) {
        if (rawUrl.isBlank()) { toast(ctx, "没有可用的下载地址"); return }
        appCtx = ctx.applicationContext
        val app = ctx.applicationContext
        val safeTitle = sanitizeName(title.ifBlank { "从前慢_${System.currentTimeMillis()}" })
        val hdrs = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> if (v.isNotBlank() && k.lowercase() !in HOP_BY_HOP) hdrs[k] = v }
        Thread {
            var out: DlOutput? = null
            try {
                val isM3u8 = isHlsPlaylist(rawUrl, hdrs)
                out = prepareOutput(app, safeTitle, if (isM3u8) "mp4" else guessExt(rawUrl))
                showProgress(app, safeTitle, 0, true)
                if (isM3u8) downloadM3u8(app, rawUrl, hdrs, out.stream) else downloadSingle(rawUrl, hdrs, out.stream)
                out.stream.close()
                scanDownloaded(app, out.scanPath)
                dismissProgress(app)
                toastOnMain(app, "下载完成：$safeTitle")
                Log.i(TAG, "下载完成 $safeTitle")
            } catch (e: Exception) {
                Log.w(TAG, "下载失败: ${e.message}")
                runCatching { out?.file?.delete() }
                runCatching { out?.uri?.let { app.contentResolver.delete(it, null, null) } }
                dismissProgress(app)
                toastOnMain(app, "下载失败：${e.message?.take(80)}")
            }
        }.start()
    }

    private fun prepareOutput(ctx: Context, name: String, ext: String): DlOutput {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.$ext")
                put(MediaStore.Video.Media.MIME_TYPE, if (ext == "ts") "video/mp2t" else "video/$ext")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$DIR_NAME")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw Exception("无法创建媒体库条目")
            DlOutput(ctx.contentResolver.openOutputStream(uri)!!, "", file = null, uri = uri)
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val d = File(dir, DIR_NAME); if (!d.exists()) d.mkdirs()
            val f = File(d, "$name.$ext")
            DlOutput(FileOutputStream(f), f.absolutePath, file = f, uri = null)
        }
    }

    private fun downloadSingle(url: String, headers: Map<String, String>, out: OutputStream) {
        client.newCall(headerRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message} @ ${url.take(120)}")
            val total = resp.body?.contentLength() ?: -1L
            val input = resp.body?.byteStream() ?: throw Exception("空响应 @ ${url.take(120)}")
            val buf = ByteArray(64 * 1024); var read: Int; var done: Long = 0
            while (input.read(buf).also { read = it } > 0) {
                out.write(buf, 0, read); done += read
                if (total > 0) updateProgress((done * 100 / total).toInt())
            }
        }
    }

    private fun downloadM3u8(ctx: Context, indexUrl: String, headers: Map<String, String>, out: OutputStream) {
        val idxText = httpGetText(indexUrl, headers)
        val lines = idxText.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-KEY") && it.contains("METHOD=AES-128") })
            throw Exception("该视频为 AES-128 加密流，App 暂不支持，请用 IDM+/ADM 嗅探下载")
        val variantIdx = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        val mediaUrl = if (variantIdx >= 0) {
            val uri = lines.getOrNull(variantIdx + 1)?.takeIf { !it.startsWith("#") } ?: indexUrl
            resolveUrl(indexUrl, uri)
        } else indexUrl
        val mediaText = if (mediaUrl != indexUrl) httpGetText(mediaUrl, headers) else idxText
        val segs = mediaText.lineSequence().map { it.trimEnd() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (segs.isEmpty()) throw Exception("m3u8 解析无切片")
        val total = segs.size
        val pool = Executors.newFixedThreadPool(6)
        val latch = java.util.concurrent.CountDownLatch(total)
        val failed = AtomicBoolean(false)
        val tmpDir = File(ctx.cacheDir, "m3u8_${System.currentTimeMillis()}").also { it.mkdirs() }
        segs.forEachIndexed { i, seg ->
            pool.execute {
                try {
                    val segUrl = resolveUrl(mediaUrl, seg)
                    File(tmpDir, "$i").writeBytes(httpGetBytes(segUrl, headers))
                    updateProgress(i * 100 / total)
                } catch (e: Exception) { Log.w(TAG, "ts#$i 失败: ${e.message}"); failed.set(true) }
                finally { latch.countDown() }
            }
        }
        latch.await(); pool.shutdown()
        if (failed.get()) { tmpDir.deleteRecursively(); throw Exception("部分切片下载失败，已中止") }
        val mp4 = File(ctx.cacheDir, "m3u8_${System.currentTimeMillis()}.mp4")
        try {
            val rc = ffmpegConcatRemux(ctx, tmpDir, total, mp4)
            if (rc != 0 || !mp4.exists() || mp4.length() == 0L)
                throw Exception("视频封装失败（源可能为加密/特殊编码，请用 IDM/ADM 下载）")
            mp4.inputStream().use { it.copyTo(out) }
            out.flush()
        } finally {
            tmpDir.deleteRecursively(); mp4.delete()
        }
    }

    private fun ffmpegConcatRemux(ctx: Context, tmpDir: File, total: Int, outMp4: File): Int {
        val list = File(ctx.cacheDir, "m3u8_${System.currentTimeMillis()}.txt")
        return try {
            list.bufferedWriter().use { w ->
                for (i in 0 until total) w.write("file '${File(tmpDir, "$i").absolutePath}'\n")
            }
            val cmd = "-y -fflags +genpts -f concat -safe 0 -i ${list.absolutePath} " +
                    "-c copy -movflags +faststart ${outMp4.absolutePath}"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            val rc = session.returnCode
            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(rc)) 0 else rc.value
        } catch (e: Exception) { Log.w(TAG, "ffmpeg exec fail: ${e.message}"); -1 }
        finally { list.delete() }
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("//")) return "https:$rel"
        if (rel.startsWith("http://", true) || rel.startsWith("https://", true)) return rel
        return try { java.net.URI(base).resolve(rel).toString() }
        catch (e: Exception) { rel }
    }

    private fun isHlsPlaylist(url: String, headers: Map<String, String>): Boolean {
        if (url.contains(".m3u8", true)) return true
        try {
            val head = client.newCall(headerRequest(url, headers).newBuilder().head().build()).execute()
            val ct = (head.header("Content-Type") ?: "").lowercase()
            if (ct.contains("mpegurl")) return true
            if (ct.startsWith("video/") || ct.startsWith("audio/")) return false
        } catch (_: Exception) { }
        try {
            val req = headerRequest(url, headers).newBuilder().header("Range", "bytes=0-2047").build()
            val resp = client.newCall(req).execute()
            val input = resp.body?.byteStream() ?: return false
            val buf = ByteArray(2048); var total = 0; var n = -1
            while (total < 2048 && input.read(buf, total, 2048 - total).also { n = it } > 0) total += n
            input.close()
            return String(buf, 0, total, StandardCharsets.UTF_8).trimStart().startsWith("#EXTM3U")
        } catch (_: Exception) { return false }
    }

    private fun httpGetText(url: String, headers: Map<String, String>): String {
        client.newCall(headerRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message} @ ${url.take(120)}")
            return resp.body?.string() ?: ""
        }
    }

    private fun httpGetBytes(url: String, headers: Map<String, String>): ByteArray {
        client.newCall(headerRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message} @ ${url.take(120)}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun headerRequest(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url).header("Accept", "*/*")
        if (headers.none { it.key.equals("User-Agent", true) }) b.header("User-Agent", "Mozilla/5.0")
        headers.forEach { (k, v) -> if (k.lowercase() !in HOP_BY_HOP) b.header(k, v) }
        return b.build()
    }

    private fun guessExt(url: String): String {
        val p = url.substringBefore("?").substringBefore("#")
        val e = p.substringAfterLast(".", "")
        return if (e.length in 2..4) e.lowercase() else "mp4"
    }

    private fun sanitizeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { "video" }

    private fun scanDownloaded(ctx: Context, path: String) {
        if (path.isNotBlank())
            MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf("video/*"), null)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "从前慢下载", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun showProgress(ctx: Context, title: String, pct: Int, indeterminate: Boolean) {
        curTitle = title; lastPct = -1
        runCatching {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("下载中：$title")
                .setContentText(if (indeterminate) "准备下载…" else "$pct%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, indeterminate).build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, n)
        }
    }

    private fun updateProgress(pct: Int) {
        if (pct == lastPct) return
        lastPct = pct
        val ctx = appCtx ?: return
        runCatching {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("下载中：$curTitle")
                .setContentText("$pct%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, false).build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, n)
        }
    }

    private fun dismissProgress(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_ID)
        }
    }

    private fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun toastOnMain(ctx: Context, msg: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
