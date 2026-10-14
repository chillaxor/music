package com.linghit.gdmusic

import android.content.Context
import org.json.JSONObject

/** 收藏存储：把完整 Song 元数据存进 SharedPreferences（source|id -> Song JSON），
 *  这样收藏列表页能显示真实歌名/歌手，而不只是一串 key。 */
object FavoritesStore {
    private const val FILE = "music"
    private const val KEY = "favorites_map"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readMap(ctx: Context): MutableMap<String, String> {
        val raw = prefs(ctx).getString(KEY, "{}") ?: "{}"
        val out = mutableMapOf<String, String>()
        try {
            val o = JSONObject(raw)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optString(k)
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveMap(ctx: Context, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    fun contains(ctx: Context, key: String) = readMap(ctx).containsKey(key)

    fun add(ctx: Context, s: Song) {
        val m = readMap(ctx); m[s.key()] = songToJson(s); saveMap(ctx, m)
    }

    fun remove(ctx: Context, s: Song) {
        val m = readMap(ctx); m.remove(s.key()); saveMap(ctx, m)
    }

    /** 返回切换后的收藏状态：true=已收藏，false=已取消 */
    fun toggle(ctx: Context, s: Song): Boolean {
        return if (contains(ctx, s.key())) { remove(ctx, s); false } else { add(ctx, s); true }
    }

    fun list(ctx: Context): List<Song> = readMap(ctx).mapNotNull { (_, v) -> fromJsonStr(v) }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    fun songToJson(s: Song): String = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("artist", s.artist)
        put("album", s.album); put("source", s.source)
        put("picId", s.picId); put("lyricId", s.lyricId); put("localUri", s.localUri)
    }.toString()

    fun fromJsonStr(v: String): Song? = try {
        val o = JSONObject(v)
        Song(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            source = o.optString("source", ""),
            picId = o.optString("picId", ""),
            lyricId = o.optString("lyricId", o.optString("id", "")),
            localUri = o.optString("localUri", "")
        )
    } catch (e: Exception) { null }
}
