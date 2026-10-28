package com.linghit.gdmusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Vosk 离线中文语音模型管理。
 *
 * 模型是一个**文件夹**（不是单文件），放内部存储 filesDir/models/vosk-model-small-cn。
 * 首次使用需下载（约 42MB，zip），下载后解压拍平到该目录。
 * 走三个下载源（官方 + 国内镜像兜底），全失败则提示用户手动放置。
 */
object VoskModel {
    private const val MODEL_DIR = "vosk-model-small-cn"
    const val APPROX_MB = 42

    private val URLS = listOf(
        // ① 官方（直连常用于国内失败，必须有兜底）
        "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.15.zip",
        // ② hf-mirror 镜像
        "https://hf-mirror.com/lovekky/vosk-model-small-cn/resolve/main/vosk-model-small-cn-0.15.zip"
    )

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "models/$MODEL_DIR")

    /** 模型是否已就位（含关键文件 conf/model.conf） */
    fun isReady(ctx: Context): Boolean {
        val d = modelDir(ctx)
        return d.isDirectory && File(d, "conf/model.conf").exists()
    }

    suspend fun download(
        ctx: Context,
        onProgress: (pct: Int, downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val d = modelDir(ctx)
        val tmp = File(ctx.cacheDir, "$MODEL_DIR.zip")
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val errors = mutableListOf<String>()
        for (url in URLS) {
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", "GDMusicPlayer/3.0").build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val total = resp.body?.contentLength() ?: -1L
                        var sum = 0L
                        resp.body?.byteStream()?.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    sum += n
                                    val pct = if (total > 0) (sum * 100 / total).toInt() else 0
                                    onProgress(pct, sum, total)
                                }
                            }
                        } ?: throw Exception("空响应")
                    }
                unzip(tmp, d)
                tmp.delete()
                return@withContext Result.success(d)
            } catch (t: Throwable) {
                errors += "${runCatching { java.net.URI(url).host }.getOrDefault(url)}: ${t.message}"
                runCatching { tmp.delete() }
            }
        }
        Result.failure(Exception("语音模型下载失败\n" + errors.joinToString("\n")))
    }

    /** 解压 zip，把压缩包根目录（vosk-model-small-cn/...）拍平到 outDir */
    private fun unzip(zip: File, outDir: File) {
        val rootMarker = "$MODEL_DIR/"
        outDir.mkdirs()
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val rel = if (name.startsWith(rootMarker)) name.substring(rootMarker.length) else name
                if (rel.isNotBlank() && !rel.endsWith("/")) {
                    val f = File(outDir, rel)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                } else if (rel.isNotBlank()) {
                    File(outDir, rel).mkdirs()
                }
                entry = zis.nextEntry
            }
        }
    }
}
