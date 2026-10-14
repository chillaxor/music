package com.linghit.gdmusic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 歌词伴生文件（.lrc）工具。
 * 歌词 API 只要 source+id 不要音频地址，但纯离线时拉不到——
 * 所以下载时把歌词一并落盘（几 KB 文本），播放时按「内嵌 → 伴生 → 在线」递补查找。
 * 落点两种：App 缓存目录（静默缓存下载）/ 用户 SAF 目录（手动下载，与音频同目录同名）。
 */
object LyricFiles {

    /** 缓存目录里音频/歌词共用的基名（与下载时的命名保持一致） */
    fun baseName(s: Song): String = s.key().replace(Regex("[^a-zA-Z0-9]"), "_")

    /** 写歌词到 App 缓存目录，返回文件绝对路径；失败返回 null */
    fun writeToCacheDir(ctx: Context, s: Song, lrc: String): String? = runCatching {
        val f = File(DownloadStore.dir(ctx), baseName(s) + ".lrc")
        f.writeText(lrc)
        f.absolutePath
    }.getOrNull()

    /** 写歌词到用户选的 SAF 目录（与音频同目录同名），返回 content uri；失败返回 null */
    fun writeToTree(ctx: Context, tree: DocumentFile, safeName: String, lrc: String): String? = runCatching {
        val doc = tree.createFile("text/plain", "$safeName.lrc") ?: return null
        ctx.contentResolver.openOutputStream(doc.uri)?.use { it.write(lrc.toByteArray()) }
        doc.uri.toString()
    }.getOrNull()

    /** 读一个歌词 uri 的文本（file:// 与 content:// 都支持），空内容返回 null */
    fun read(ctx: Context, uriStr: String): String? = runCatching {
        val uri = Uri.parse(uriStr)
        val text = if (uri.scheme == "file") {
            File(uri.path ?: return null).readText()
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
        text
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 在缓存目录找伴生 .lrc（懒缓存兜底：在线播放时 API 拉到的歌词会顺手存到这里，
     * 之后同一首歌离线播放也能找到；用当前 song 的 key 做基名，读写天然一致）
     */
    fun readCacheSidecar(ctx: Context, s: Song): String? = runCatching {
        val f = File(DownloadStore.dir(ctx), baseName(s) + ".lrc")
        if (f.exists()) f.readText() else null
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
