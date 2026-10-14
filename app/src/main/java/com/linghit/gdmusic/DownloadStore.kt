package com.linghit.gdmusic

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 离线下载存储中心（索引式）。
 * 文件可能散落在两处：
 *  - App 私有缓存目录（在线播放时静默缓存，kind=cache，uri 为 file://）
 *  - 用户用 SAF 手动指定的任意目录（kind=manual，uri 为 content://）
 * 因此不再扫文件夹，而是用 downloads_index.json 记录每首歌的 uri / 元数据 / 所在文件夹。
 */
object DownloadStore {

    /** App 私有音乐目录，仍用于静默缓存 */
    fun dir(ctx: Context): File =
        ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.apply { mkdirs() }

    private fun indexFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads_index.json")

    private fun loadIndex(ctx: Context): JSONArray {
        val f = indexFile(ctx)
        if (!f.exists()) migrateLegacy(ctx)
        return if (f.exists()) runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
        else JSONArray()
    }

    private fun saveIndex(ctx: Context, arr: JSONArray) {
        runCatching { indexFile(ctx).writeText(arr.toString()) }
    }

    /** 首次运行：把旧版平铺在 musicDir 的缓存文件迁移进索引，避免已下载的歌消失 */
    private fun migrateLegacy(ctx: Context) {
        val d = dir(ctx)
        val arr = JSONArray()
        d.listFiles()?.filter { !it.name.endsWith(".tmp") && !it.name.endsWith(".meta.json") }
            ?.forEach { audio ->
                val base = audio.name.substringBeforeLast('.')
                val meta = File(d, "$base.meta.json")
                val o = if (meta.exists()) runCatching { JSONObject(meta.readText()) }.getOrDefault(JSONObject())
                        else JSONObject()
                val song = Song(
                    id = o.optString("id", ""), name = o.optString("name", base),
                    artist = o.optString("artist", ""), album = o.optString("album", ""),
                    source = o.optString("source", ""), picId = o.optString("picId", ""),
                    lyricId = o.optString("lyricId", o.optString("id", ""))
                )
                arr.put(recordOf(song, Uri.fromFile(audio).toString(), "cache", "应用缓存", audio.length()))
            }
        saveIndex(ctx, arr)
    }

    private fun recordOf(song: Song, uri: String, kind: String, folder: String, size: Long) =
        JSONObject().apply {
            put("key", song.key()); put("id", song.id); put("name", song.name)
            put("artist", song.artist); put("album", song.album); put("source", song.source)
            put("picId", song.picId); put("lyricId", song.lyricId); put("localUri", song.localUri)
            put("uri", uri); put("kind", kind); put("folder", folder); put("size", size)
        }

    /** 记录一首已下载歌曲（同 key 覆盖更新） */
    fun add(ctx: Context, song: Song, uri: Uri, kind: String, folder: String, size: Long = 0) {
        val arr = loadIndex(ctx)
        for (i in 0 until arr.length()) {
            if (matches(arr.getJSONObject(i), song)) { arr.remove(i); break }
        }
        val finalSize = if (size > 0) size else sizeOf(ctx, uri)
        arr.put(recordOf(song, uri.toString(), kind, folder, finalSize))
        saveIndex(ctx, arr)
    }

    /** 从索引记录重建 Song（localUri 缺失时用 uri 兜底，保证导入歌 key 唯一） */
    private fun songOf(o: JSONObject): Song {
        val uriStr = o.optString("uri")
        return Song(
            o.optString("id"), o.optString("name"), o.optString("artist"),
            o.optString("album"), o.optString("source"), o.optString("picId"),
            o.optString("lyricId", o.optString("id")),
            o.optString("localUri").ifBlank { uriStr }
        )
    }

    /**
     * 一条索引记录是否就是这首歌。必须多路匹配，否则「下载页点歌 → 本地播放」必然失败：
     *  - 写入时（手动下载/静默缓存）song 是在线歌，key 存的是 "netease|id"；
     *  - 读取时 list() 用 uri 兜底填 localUri，key 变成 "local|content://..."；
     *  - 单路按 key 精确比对 → 永远匹配不上 → 掉进在线播放 → 本地歌接口报
     *    "source is not supported" 起播失败 → 上一首继续响（用户看到「固定那一首」）。
     *  - 旧索引（localUri 字段问世前写的）导入歌 key 全是 "|"，更必须靠重算 key 兜底。
     */
    private fun matches(o: JSONObject, song: Song): Boolean {
        if (o.optString("key") == song.key()) return true
        if (songOf(o).key() == song.key()) return true
        // 在线歌（本地记录重建后 key 是 local|uri）：按 source+id 认祖归宗
        if (song.source.isNotBlank() && song.id.isNotBlank() &&
            o.optString("source") == song.source && o.optString("id") == song.id) return true
        // 本地歌：直接按文件地址认
        if (song.localUri.isNotBlank() &&
            (o.optString("uri") == song.localUri || o.optString("localUri") == song.localUri)) return true
        return false
    }

    /** 列出全部已下载（按记录顺序） */
    fun list(ctx: Context): List<DownloadedItem> {
        val arr = loadIndex(ctx)
        val out = mutableListOf<DownloadedItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uriStr = o.optString("uri")
            val song = songOf(o)
            out += DownloadedItem(
                key = song.key(),
                song = song,
                uri = Uri.parse(uriStr),
                folder = o.optString("folder", "本地"),
                kind = o.optString("kind", "cache"),
                sizeBytes = o.optLong("size", 0L)
            )
        }
        return out
    }

    /** 返回某首歌的离线 Uri（file:// 缓存或 content:// 手动目录），无则 null。
     *  用 matches 多路匹配：手动下载/缓存记录的 key 是 "netease|id"，而队列里的歌
     *  localUri 已填上文件地址、key 是 "local|..."，单路按 key 比会全部落空
     *  → 本地歌被当成在线歌播（导入的本地歌接口直接报错，上一首继续响） */
    fun localUri(ctx: Context, song: Song): Uri? {
        val arr = loadIndex(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (matches(o, song)) return Uri.parse(o.optString("uri"))
        }
        return null
    }

    /** 批量添加「扫描导入」的本地文件（按 uri 去重，不覆盖已有记录） */
    fun addScanned(ctx: Context, items: List<DownloadedItem>) {
        if (items.isEmpty()) return
        val arr = loadIndex(ctx)
        val have = (0 until arr.length()).map { arr.getJSONObject(it).optString("uri") }.toMutableSet()
        for (it in items) {
            val u = it.uri.toString()
            if (u in have) continue
            arr.put(recordOf(it.song, u, it.kind, it.folder, it.sizeBytes))
            have.add(u)
        }
        saveIndex(ctx, arr)
    }

    /** 从文件名尽力解析 歌名/歌手：优先 "歌名-歌手"，否则整名当歌名 */
    fun parseAudioName(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.', fileName).trim()
        val parts = base.split(Regex("\\s*[-_－—–]\\s*")).filter { it.isNotBlank() }
        return if (parts.size >= 2) parts[0].trim() to parts[1].trim()
        else base to ""
    }

    /** 按 key 删除（同时删除底层文件）。matches 已兼容旧索引 key="|" 与重算 key 双路匹配 */
    fun delete(ctx: Context, key: String) {
        val arr = loadIndex(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uriStr = o.optString("uri")
            if (o.optString("key") == key || songOf(o).key() == key) {
                deleteUri(ctx, Uri.parse(uriStr), o.optString("kind"))
                arr.remove(i)
                break
            }
        }
        saveIndex(ctx, arr)
    }

    /** 清空全部 */
    fun clearAll(ctx: Context) {
        val arr = loadIndex(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            deleteUri(ctx, Uri.parse(o.optString("uri")), o.optString("kind"))
        }
        saveIndex(ctx, JSONArray())
    }

    private fun deleteUri(ctx: Context, uri: Uri, kind: String) {
        runCatching {
            if (kind == "cache") {
                val p = uri.path
                if (p != null) File(p).delete()
            } else {
                DocumentFile.fromSingleUri(ctx, uri)?.delete()
                    ?: DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            }
        }
    }

    /** 取得文件大小：file:// 直接读，content:// 用 DocumentFile */
    private fun sizeOf(ctx: Context, uri: Uri): Long {
        return if (uri.scheme == "file") {
            uri.path?.let { File(it).length() } ?: 0L
        } else {
            DocumentFile.fromSingleUri(ctx, uri)?.length() ?: 0L
        }
    }
}

data class DownloadedItem(
    val key: String,
    val song: Song,
    val uri: Uri,
    val folder: String,
    val kind: String,
    val sizeBytes: Long
)
