package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 榜单本地缓存：每天只拉一次，跨天自动失效重取。
 * （网易云榜单每天更新，没必要每次进搜索页都请求）
 */
object NeteaseStore {
    private const val SP = "netease_store"
    private const val K_DATE = "date_"
    private const val K_DATA = "data_"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun load(ctx: Context, chartId: String): List<NeteaseApi.ChartSong> {
        val s = sp(ctx)
        if (s.getString(K_DATE + chartId, "") != today()) return emptyList()
        val raw = s.getString(K_DATA + chartId, null) ?: return emptyList()
        return runCatching { parse(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, chartId: String, songs: List<NeteaseApi.ChartSong>) {
        val arr = JSONArray()
        songs.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("n", it.name)
            o.put("a", it.artist)
            o.put("c", it.cover)
            o.put("d", it.durationMs)
            arr.put(o)
        }
        sp(ctx).edit()
            .putString(K_DATE + chartId, today())
            .putString(K_DATA + chartId, arr.toString())
            .apply()
    }

    private fun parse(arr: JSONArray): List<NeteaseApi.ChartSong> {
        val out = ArrayList<NeteaseApi.ChartSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += NeteaseApi.ChartSong(
                id = o.optString("id"),
                name = o.optString("n"),
                artist = o.optString("a"),
                cover = o.optString("c"),
                durationMs = o.optLong("d")
            )
        }
        return out
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
