package com.example.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多歌单（收藏夹）存储。
 * 结构：playlists_v1 -> JSONArray[{id,name,songs:[songJson,...]}]
 * 默认歌单 id="default" 名为"我的收藏"，不可删除。
 * 首启会把旧版 FavoritesStore 的扁平收藏迁移进默认歌单，旧收藏不丢。
 */
object PlaylistStore {
    private const val FILE = "music"
    private const val KEY = "playlists_v1"
    const val DEFAULT_ID = "default"

    data class Playlist(val id: String, val name: String, val songs: List<String>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): MutableList<Playlist> {
        val raw = prefs(ctx).getString(KEY, null)
        if (raw == null) {
            // 首启：从旧版 FavoritesStore 扁平收藏迁移
            val legacy = FavoritesStore.list(ctx)
            val list = mutableListOf<Playlist>()
            if (legacy.isNotEmpty()) {
                list.add(Playlist(DEFAULT_ID, "我的收藏", legacy.map { FavoritesStore.songToJson(it) }))
            }
            return list
        }
        val arr = JSONArray(raw)
        val out = mutableListOf<Playlist>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val songsArr = o.optJSONArray("songs") ?: JSONArray()
            val songs = mutableListOf<String>()
            for (j in 0 until songsArr.length()) songs.add(songsArr.optString(j))
            out.add(Playlist(o.optString("id", DEFAULT_ID), o.optString("name", "我的收藏"), songs))
        }
        return out
    }

    private fun saveAll(ctx: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name)
            val s = JSONArray(); p.songs.forEach { s.put(it) }
            o.put("songs", s)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** 取所有歌单（保证默认歌单存在且排第一） */
    fun getPlaylists(ctx: Context): List<Playlist> {
        val list = readAll(ctx).toMutableList()
        val di = list.indexOfFirst { it.id == DEFAULT_ID }
        if (di < 0) list.add(0, Playlist(DEFAULT_ID, "我的收藏", emptyList()))
        else if (di > 0) { val d = list.removeAt(di); list.add(0, d) }
        return list
    }

    fun createPlaylist(ctx: Context, name: String): String {
        val id = "pl_${System.currentTimeMillis()}"
        val list = readAll(ctx).toMutableList()
        list.add(Playlist(id, name.ifBlank { "新歌单" }, emptyList()))
        saveAll(ctx, list)
        return id
    }

    fun renamePlaylist(ctx: Context, id: String, name: String) {
        val list = readAll(ctx)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name.ifBlank { "未命名" })
            saveAll(ctx, list)
        }
    }

    fun deletePlaylist(ctx: Context, id: String) {
        if (id == DEFAULT_ID) return
        val list = readAll(ctx).toMutableList()
        list.removeAll { it.id == id }
        saveAll(ctx, list)
    }

    fun addSong(ctx: Context, playlistId: String, s: Song) {
        val list = readAll(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: run {
            list.add(0, Playlist(DEFAULT_ID, "我的收藏", emptyList()))
            list.indexOfFirst { it.id == DEFAULT_ID }
        }
        val songs = list[idx].songs.toMutableList()
        val json = FavoritesStore.songToJson(s)
        if (songs.none { songKey(it) == s.key() }) songs.add(json)
        list[idx] = list[idx].copy(songs = songs)
        saveAll(ctx, list)
    }

    fun removeSong(ctx: Context, playlistId: String, s: Song) {
        val list = readAll(ctx)
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx < 0) return
        val songs = list[idx].songs.filterNot { songKey(it) == s.key() }
        list[idx] = list[idx].copy(songs = songs)
        saveAll(ctx, list)
    }

    fun isInPlaylist(ctx: Context, playlistId: String, s: Song): Boolean =
        readAll(ctx).firstOrNull { it.id == playlistId }?.songs?.any { songKey(it) == s.key() } ?: false

    /** 该歌是否在任意歌单中（用于 ⭐ 高亮） */
    fun isFavoritedAnywhere(ctx: Context, s: Song): Boolean =
        readAll(ctx).any { it.songs.any { j -> songKey(j) == s.key() } }

    fun getSongs(ctx: Context, playlistId: String): List<Song> =
        readAll(ctx).firstOrNull { it.id == playlistId }?.songs
            ?.mapNotNull { FavoritesStore.fromJsonStr(it) } ?: emptyList()

    private fun songKey(json: String): String = try {
        val o = JSONObject(json)
        "${o.optString("source", "")}|${o.optString("id", "")}"
    } catch (e: Exception) { json }
}
