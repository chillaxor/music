package com.linghit.gdmusic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐巅峰榜（搜索页「今日榜单」发现层）。
 *
 * 实测 2026-11-16（本机 curl）：
 *  - 简版 fcg_myqq_toplist.fcg 只给每个榜 3 首，信息太少；用完整榜 fcg_v8_toplist_cp.fcg
 *  - 免登录可用，无需 Referer；返回 songlist[].data.songname / data.singer[].name
 *  - 8 个精选榜全部实测返回：26 热歌 / 62 飙升 / 27 新歌 / 60 抖音 / 5 内地 /
 *    65 国风 / 58 说唱 / 28 网络歌曲
 *  - ⚠️ 榜单只做「发现层」：点卡片=用「歌名 歌手」自动搜索，播放/下载仍走本 app 现有音乐源
 */
object QQChartApi {
    data class Chart(val id: String, val label: String)

    data class ChartSong(
        val id: String,
        val name: String,
        val artist: String
    )

    val CHARTS = listOf(
        Chart("26", "热歌"),
        Chart("62", "飙升"),
        Chart("27", "新歌"),
        Chart("60", "抖音"),
        Chart("5", "内地"),
        Chart("65", "国风"),
        Chart("58", "说唱"),
        Chart("28", "网络")
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 取某榜单前 limit 首（只保留歌名 + 歌手） */
    fun fetchChart(chartId: String, limit: Int = 20): List<ChartSong> {
        val body = get("https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$chartId&format=json&g_tk=5381")
        val root = JSONObject(body)
        if (root.optInt("code") != 0) return emptyList()
        val list = root.optJSONArray("songlist") ?: return emptyList()
        val n = minOf(limit, list.length())
        val out = ArrayList<ChartSong>(n)
        for (i in 0 until n) {
            val d = list.optJSONObject(i)?.optJSONObject("data") ?: continue
            val name = d.optString("songname")
            if (name.isBlank()) continue
            val singers = d.optJSONArray("singer")
            val artist = if (singers != null && singers.length() > 0) {
                (0 until singers.length()).joinToString("/") {
                    singers.optJSONObject(it)?.optString("name").orEmpty()
                }.trim('/')
            } else ""
            out += ChartSong(
                id = d.optString("songmid").ifBlank { d.optString("songid") },
                name = name,
                artist = artist
            )
        }
        return out
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .header("Referer", "https://y.qq.com/")
            .build()
        return client.newCall(req).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            it.body?.string() ?: ""
        }
    }
}
