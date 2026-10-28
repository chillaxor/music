package com.linghit.gdmusic

import android.content.Context

/**
 * 本地音乐库检索：在 DownloadStore 扫描导入的曲库里按歌名/歌手模糊匹配。
 *
 * 这个 App 的音乐主要来自两处：
 *  - 本地：MusicImporter 扫进来的音频（DownloadStore，uri = file:// 或 content://）
 *  - 在线：设置里配的音乐 API（MainActivity.searchSource / MusicSearch）
 * 语音「播放」默认优先本地（离线、秒开、不依赖任何 API 配置），本地没有才退在线。
 */
object LocalMusic {
    fun search(ctx: Context, q: String): List<Song> {
        val kw = q.trim()
        if (kw.isEmpty()) return emptyList()
        return DownloadStore.list(ctx).filter { item ->
            val s = item.song
            s.name.contains(kw, ignoreCase = true) ||
                    s.artist.contains(kw, ignoreCase = true)
        }.map { it.song }
    }
}
