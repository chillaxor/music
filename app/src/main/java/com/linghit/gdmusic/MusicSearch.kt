package com.linghit.gdmusic

import android.content.Context
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 独立音乐搜索（供语音助手调用）。
 *
 * 复用 MainActivity 那套 API 配置（设置页可换源），但不依赖 MainActivity 实例——
 * 语音页可能从悬浮球（Service 上下文）唤起，MainActivity 未必活着。
 * 搜索 URL 格式与 MainActivity.searchSource 完全一致，保证结果一致。
 */
object MusicSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 读设置里的 API 地址，并归一化成 searchSource 用的形式 */
    private fun apiBase(ctx: Context): String {
        val raw = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
            .getString(SettingsActivity.KEY_API, SettingsActivity.DEFAULT_API)
            ?: SettingsActivity.DEFAULT_API
        return normalizeApi(raw)
    }

    private fun normalizeApi(raw: String): String {
        var s = raw.trim()
        while (s.endsWith("/")) s = s.substring(0, s.length - 1)
        if (!s.endsWith("/api.php", ignoreCase = true)) s += "/api.php"
        return s
    }

    /**
     * 搜索歌曲。
     * @param source 音源，默认 netease（项目里只有它能端到端播放，见 MainActivity）
     */
    fun search(ctx: Context, kw: String, source: String = "netease", page: Int = 1): List<Song> {
        val u = "${apiBase(ctx)}?types=search&source=$source&name=${URLEncoder.encode(kw, "UTF-8")}&count=20&pages=$page"
        val r = Request.Builder().url(u).header("User-Agent", "GDMusicPlayer/3.0").build()
        val body = client.newCall(r).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            it.body?.string() ?: "[]"
        }
        val arr = JSONArray(body)
        val out = mutableListOf<Song>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ar = o.optJSONArray("artist")
            val artist = if (ar != null) (0 until ar.length()).joinToString(" / ") { ar.optString(it) }
            else o.optString("artist")
            out += Song(
                o.optString("id"), o.optString("name"), artist, o.optString("album"),
                o.optString("source", source), o.optString("pic_id"),
                o.optString("lyric_id", o.optString("id"))
            )
        }
        return out
    }
}
