package com.linghit.gdmusic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.charset.Charset

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
        val bytes = if (uri.scheme == "file") {
            File(uri.path ?: return null).readBytes()
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        }
        decodeSmart(bytes)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 智能解码：网上下的中文歌词大量是 GBK/GB18030，直接按 UTF-8 解会出一堆
     * 替换字符（显示为乱码或干脆像没加载）。先按 UTF-8 解，若发现替换字符
     * 就改用 GB18030 再解，谁的问题少用谁。
     */
    private fun decodeSmart(bytes: ByteArray): String? {
        val badChar = '\uFFFD'
        val utf8 = bytes.decodeToString()
        val badUtf8 = utf8.count { it == badChar }
        if (badUtf8 == 0) return utf8
        val gbk = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
        return if (gbk != null && gbk.count { it == badChar } < badUtf8) gbk else utf8
    }

    /**
     * 在缓存目录找伴生 .lrc（懒缓存兜底：在线播放时 API 拉到的歌词会顺手存到这里，
     * 之后同一首歌离线播放也能找到；用当前 song 的 key 做基名，读写天然一致）
     */
    fun readCacheSidecar(ctx: Context, s: Song): String? = runCatching {
        val f = File(DownloadStore.dir(ctx), baseName(s) + ".lrc")
        if (f.exists()) decodeSmart(f.readBytes()) else null
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
