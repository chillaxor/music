package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QQ 音乐榜单本地缓存：每天只拉一次，跨天自动失效。
 */
object QQChartStore {
    private const val SP = "qq_chart_store"
    private const val K_DATE = "date_"
    private const val K_DATA = "data_"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun load(ctx: Context, chartId: String): List<QQChartApi.ChartSong> {
        val s = sp(ctx)
        if (s.getString(K_DATE + chartId, "") != today()) return emptyList()
        val raw = s.getString(K_DATA + chartId, null) ?: return emptyList()
        return runCatching { parse(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, chartId: String, songs: List<QQChartApi.ChartSong>) {
        val arr = JSONArray()
        songs.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("n", it.name)
            o.put("a", it.artist)
            arr.put(o)
        }
        sp(ctx).edit()
            .putString(K_DATE + chartId, today())
            .putString(K_DATA + chartId, arr.toString())
            .apply()
    }

    private fun parse(arr: JSONArray): List<QQChartApi.ChartSong> {
        val out = ArrayList<QQChartApi.ChartSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += QQChartApi.ChartSong(
                id = o.optString("id"),
                name = o.optString("n"),
                artist = o.optString("a")
            )
        }
        return out
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
