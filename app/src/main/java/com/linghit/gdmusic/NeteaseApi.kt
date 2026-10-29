package com.linghit.gdmusic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 网易云官方榜单（发现层）。
 *
 * 实测结论（2026-10-29 本机 curl / urllib）：
 *  - 免登录可用，只要 UA + Referer: https://music.163.com/
 *  - 官方榜 id：热歌 3778678 / 飙升 19723756 / 新歌 3779629 / 原创 2884035
 *  - 榜单每天更新（updateTime 是当天时间戳），歌名/歌手/封面/时长都有
 *  - ⚠️「个性化每日推荐」接口（/api/recommend/songs、/api/personalized、
 *    interface.music.163.com/api/recommend/resource）**全部 404 已下线**，用不了
 *  - ⚠️ 榜单只给元信息；网易云的歌曲播放地址要 VIP/版权（403），
 *    所以本模块只做「发现层」：点卡片走本 app 现有音乐源去搜、去播、去下载
 */
object NeteaseApi {
    data class Chart(val id: String, val label: String)

    data class ChartSong(
        val id: String,
        val name: String,
        val artist: String,
        val cover: String,
        val durationMs: Long
    )

    val CHARTS = listOf(
        Chart("3778678", "热歌"),
        Chart("19723756", "飙升"),
        Chart("3779629", "新歌"),
        Chart("2884035", "原创")
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 取某榜单前 limit 首 */
    fun fetchChart(chartId: String, limit: Int = 20): List<ChartSong> {
        val body = get("https://music.163.com/api/playlist/detail?id=$chartId")
        val root = JSONObject(body)
        if (root.optInt("code") != 200) return emptyList()
        val tracks = root.optJSONObject("result")?.optJSONArray("tracks") ?: return emptyList()
        val n = minOf(limit, tracks.length())
        val out = ArrayList<ChartSong>(n)
        for (i in 0 until n) {
            val t = tracks.optJSONObject(i) ?: continue
            val ars = t.optJSONArray("artists")
            val artist = if (ars != null && ars.length() > 0) {
                (0 until ars.length()).joinToString(" / ") {
                    ars.optJSONObject(it)?.optString("name").orEmpty()
                }
            } else ""
            var cover = t.optJSONObject("album")?.optString("picUrl").orEmpty()
            if (cover.startsWith("http://")) cover = "https://" + cover.substring(7)
            out += ChartSong(
                id = t.optString("id"),
                name = t.optString("name"),
                artist = artist,
                cover = cover,
                durationMs = t.optLong("duration")
            )
        }
        return out
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            )
            .header("Referer", "https://music.163.com/")
            .build()
        return client.newCall(req).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            it.body?.string() ?: ""
        }
    }
}
