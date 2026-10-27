package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播客/电台订阅与播放进度存储（SharedPreferences JSON，沿用 FavoritesStore 范式）。
 * 与音乐曲库/收藏完全隔离：这里只存播客订阅和单集进度，不写入音乐相关存储。
 */
object PodcastStore {

    data class Sub(
        val rssUrl: String,
        val title: String,
        val author: String,
        val coverUrl: String,
        val desc: String
    ) {
        fun toJson() = JSONObject().apply {
            put("rss", rssUrl); put("title", title); put("author", author)
            put("cover", coverUrl); put("desc", desc)
        }
    }

    private const val FILE = "podcast"
    private const val KEY_SUBS = "subs"
    private const val KEY_PROG = "progress"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun subs(ctx: Context): List<Sub> {
        val raw = prefs(ctx).getString(KEY_SUBS, "[]") ?: "[]"
        val out = mutableListOf<Sub>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Sub(
                        rssUrl = o.optString("rss"),
                        title = o.optString("title"),
                        author = o.optString("author"),
                        coverUrl = o.optString("cover"),
                        desc = o.optString("desc")
                    )
                )
            }
        }
        return out
    }

    fun addSub(ctx: Context, s: Sub) {
        val l = subs(ctx).toMutableList()
        if (l.any { it.rssUrl == s.rssUrl }) return
        l.add(0, s)
        saveSubs(ctx, l)
    }

    fun removeSub(ctx: Context, rssUrl: String) {
        saveSubs(ctx, subs(ctx).filter { it.rssUrl != rssUrl })
    }

    fun isSub(ctx: Context, rssUrl: String) = subs(ctx).any { it.rssUrl == rssUrl }

    private fun saveSubs(ctx: Context, l: List<Sub>) {
        val arr = JSONArray()
        l.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    /** 单集进度 key：rssUrl + guid 唯一标识一集 */
    private fun progKey(rssUrl: String, guid: String) = "$rssUrl|$guid"

    /** 返回某集已听进度（毫秒），无记录为 0 */
    fun getProgress(ctx: Context, rssUrl: String, guid: String): Long {
        val raw = prefs(ctx).getString(KEY_PROG, "{}") ?: "{}"
        return runCatching { JSONObject(raw).optLong(progKey(rssUrl, guid), 0L) }.getOrDefault(0L)
    }

    /** 写入某集进度（毫秒） */
    fun setProgress(ctx: Context, rssUrl: String, guid: String, posMs: Long) {
        val k = progKey(rssUrl, guid)
        runCatching {
            val o = JSONObject(prefs(ctx).getString(KEY_PROG, "{}") ?: "{}")
            o.put(k, posMs)
            prefs(ctx).edit().putString(KEY_PROG, o.toString()).apply()
        }
    }
}
