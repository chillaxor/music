package com.linghit.gdmusic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 今日诗词接口（今日诗词 jinrishici.com）。
 *
 * 实测结论（2026-10-29 本机 curl）：
 *  - 免费、免 key、免注册，国内可达
 *  - GET /token          → {"status":"success","data":"<token>"}
 *  - GET /one.json       → {"status":"success","data":{content,origin:{title,dynasty,author,content[],translate[]}}}
 *  - 带 X-User-Token 头请求不触发接口的「异常流量」降级；不带也能出，但同 IP 高频会被降推荐
 *  - data.translate 可能为 null（部分诗没有译文）
 */
object PoemApi {
    private const val BASE = "https://v2.jinrishici.com"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 取一个用户 token：拿到后持久化，同一 token 每日返回同一首 */
    fun fetchToken(): String? = runCatching {
        JSONObject(get("$BASE/token", null)).optString("data").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 取一首诗；token 可为空 */
    fun fetchPoem(token: String?): Poem? = runCatching {
        val root = JSONObject(get("$BASE/one.json", token))
        if (root.optString("status") != "success") return null
        val d = root.optJSONObject("data") ?: return null
        val o = d.optJSONObject("origin")
        Poem(
            id = d.optString("id"),
            quote = d.optString("content"),
            title = o?.optString("title").orEmpty(),
            dynasty = o?.optString("dynasty").orEmpty(),
            author = o?.optString("author").orEmpty(),
            lines = o?.optJSONArray("content").toStringList(),
            translate = o?.optJSONArray("translate").toStringList()
        )
    }.getOrNull()

    private fun get(url: String, token: String?): String {
        val b = Request.Builder().url(url).header("User-Agent", "GDMusicPlayer/3.0")
        if (!token.isNullOrBlank()) b.header("X-User-Token", token)
        return client.newCall(b.build()).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            it.body?.string() ?: ""
        }
    }
}
