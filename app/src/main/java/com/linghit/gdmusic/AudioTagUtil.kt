package com.linghit.gdmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/** 本地音频文件的元数据 / 封面 / ID3 标签（移植自 mf_audit 的 Mp3UtilModule） */
data class AudioMeta(
    val durationMs: Long, val bitrate: Int, val artist: String,
    val album: String, val title: String, val year: String
)
data class AudioTags(val title: String, val artist: String, val album: String, val lyric: String, val comment: String)

object AudioTagUtil {

    private const val TAG = "AudioTagUtil"

    /** 读基础元数据：支持 file:// 与 content:// 路径 */
    fun readMeta(context: Context, pathOrUri: String): AudioMeta? = runCatching {
        val uri = Uri.parse(pathOrUri)
        val mmr = MediaMetadataRetriever()
        try {
            if (uri.scheme.equals("content", true)) mmr.setDataSource(context, uri)
            else mmr.setDataSource(pathOrUri)
            AudioMeta(
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty()
            )
        } finally { runCatching { mmr.release() } }
    }.getOrElse { Log.w(TAG, "readMeta fail: $pathOrUri", it); null }

    /** 取内嵌封面（带磁盘缓存，避免每次解码） */
    fun readCover(context: Context, pathOrUri: String): Bitmap? = runCatching {
        val uri = Uri.parse(pathOrUri)
        val isContent = uri.scheme.equals("content", true)
        val cacheKey = (if (isContent) uri.toString() else File(pathOrUri).absolutePath).hashCode()
        val cacheDir = File(context.cacheDir, "cover_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, "$cacheKey.jpg")
        if (cacheFile.exists()) return BitmapFactory.decodeFile(cacheFile.absolutePath)
        val mmr = MediaMetadataRetriever()
        try {
            if (isContent) mmr.setDataSource(context, uri) else mmr.setDataSource(pathOrUri)
            val bytes = mmr.embeddedPicture ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            runCatching { cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            bmp
        } finally { runCatching { mmr.release() } }
    }.getOrElse { Log.w(TAG, "readCover fail: $pathOrUri", it); null }

    /** 读 ID3 标签（仅支持真实文件路径，content:// 不可写故不支持） */
    fun readTags(path: String): AudioTags? = runCatching {
        val f = File(path)
        if (!f.exists()) return null
        val tag = AudioFileIO.read(f).tagOrCreateAndSetDefault
        AudioTags(
            title = tag.getFirst(FieldKey.TITLE),
            artist = tag.getFirst(FieldKey.ARTIST),
            album = tag.getFirst(FieldKey.ALBUM),
            lyric = tag.getFirst(FieldKey.LYRICS),
            comment = tag.getFirst(FieldKey.COMMENT)
        )
    }.getOrElse { Log.w(TAG, "readTags fail: $path", it); null }

    /** 写 ID3 标签：把歌名/歌手/专辑/歌词/备注写进文件（换设备、卸载重装都不丢） */
    fun writeTags(path: String, title: String?, artist: String?, album: String?,
                  lyric: String?, comment: String?): Boolean = runCatching {
        val f = File(path)
        if (!f.exists()) return false
        val audioFile = AudioFileIO.read(f)
        val tag = audioFile.tagOrCreateAndSetDefault
        title?.let { tag.setField(FieldKey.TITLE, it) }
        artist?.let { tag.setField(FieldKey.ARTIST, it) }
        album?.let { tag.setField(FieldKey.ALBUM, it) }
        lyric?.let { tag.setField(FieldKey.LYRICS, it) }
        comment?.let { tag.setField(FieldKey.COMMENT, it) }
        audioFile.commit()
        true
    }.getOrElse { Log.w(TAG, "writeTags fail: $path", it); false }

    /** 读文件内嵌歌词（ID3 LYRICS 字段） */
    fun readLyric(path: String): String? = readTags(path)?.lyric?.takeIf { it.isNotBlank() }
}
