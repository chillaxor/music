package com.linghit.gdmusic

import android.content.Context
import org.json.JSONObject

/**
 * 影视仓播放历史 + 断点续播记录。
 *
 * key 由 JS 侧生成后随起播参数带回：
 *  - 直连源："d:<站点api>|<vod_id>"
 *  - 爬虫源："s:<源key>|<vod_id>"
 *
 * 记录里同时存续播所需的完整上下文（epsJson/spiderJson/siteApi/vodId/idx/url），
 * 续播时优先重新拉详情/重新解析（播放地址常带签名会过期），失败才兜底播存的直链。
 */
object VideoHistoryStore {
    private const val FILE = "video_history"
    private const val KEY = "records"
    private const val MAX = 100

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 一条历史记录 */
    class Rec(
        val key: String,
        val title: String,
        val pic: String,
        val epName: String,
        val url: String,
        val referer: String,
        val epsJson: String,
        val idx: Int,
        val spiderJson: String,
        val vodId: String,
        val siteApi: String,
        val from: String,
        var positionMs: Long,
        var durationMs: Long,
        var updatedAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key); put("title", title); put("pic", pic); put("epName", epName)
            put("url", url); put("referer", referer); put("epsJson", epsJson); put("idx", idx)
            put("spiderJson", spiderJson); put("vodId", vodId); put("siteApi", siteApi)
            put("from", from); put("positionMs", positionMs); put("durationMs", durationMs)
            put("updatedAt", updatedAt)
        }

        /** 交给 VideoActivity 续播的 JSON（epIdx 与 idx 同义，JS 侧按 epIdx 取） */
        fun resumeJson(): String = toJson().apply { put("epIdx", idx) }.toString()

        /** 播过 90% 视为看完：下次从头播 */
        fun watched() = durationMs > 0 && positionMs >= durationMs * 9 / 10
    }

    private fun readMap(ctx: Context): MutableMap<String, String> {
        val raw = prefs(ctx).getString(KEY, "{}") ?: "{}"
        val out = mutableMapOf<String, String>()
        try {
            val o = JSONObject(raw)
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); out[k] = o.optString(k) }
        } catch (e: Exception) { }
        return out
    }

    private fun saveMap(ctx: Context, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    /** 新增或更新一条记录（同 key 覆盖），保持最多 MAX 条 */
    fun upsert(ctx: Context, r: Rec) {
        val m = readMap(ctx)
        m[r.key] = r.toJson().toString()
        if (m.size > MAX) {
            // 按更新时间淘汰最旧的
            m.entries.sortedBy { it.value.let { v -> runCatching { JSONObject(v).optLong("updatedAt") }.getOrDefault(0L) } }
                .take(m.size - MAX)
                .forEach { m.remove(it.key) }
        }
        saveMap(ctx, m)
    }

    fun find(ctx: Context, key: String): Rec? {
        val v = readMap(ctx)[key] ?: return null
        return runCatching { fromJson(JSONObject(v)) }.getOrNull()
    }

    /** 只看播放位置：每 5 秒/退出时调用，不做全量重写以外的结构变更 */
    fun updatePosition(ctx: Context, key: String, pos: Long, dur: Long) {
        if (key.isBlank()) return
        val m = readMap(ctx)
        val raw = m[key] ?: return
        runCatching {
            val o = JSONObject(raw)
            o.put("positionMs", pos)
            if (dur > 0) o.put("durationMs", dur)
            o.put("updatedAt", System.currentTimeMillis())
            m[key] = o.toString()
            saveMap(ctx, m)
        }
    }

    /** 切集同步：集名/下标/直链变，播放位置从头开始 */
    fun updateEpisode(ctx: Context, key: String, epName: String, idx: Int, url: String) {
        if (key.isBlank()) return
        val m = readMap(ctx)
        val raw = m[key] ?: return
        runCatching {
            val o = JSONObject(raw)
            o.put("epName", epName); o.put("idx", idx); o.put("url", url)
            o.put("positionMs", 0)
            o.put("updatedAt", System.currentTimeMillis())
            m[key] = o.toString()
            saveMap(ctx, m)
        }
    }

    fun remove(ctx: Context, key: String) {
        val m = readMap(ctx); m.remove(key); saveMap(ctx, m)
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** 全部记录，按更新时间倒序（最近看的在前） */
    fun list(ctx: Context): List<Rec> = readMap(ctx).values
        .mapNotNull { v -> runCatching { fromJson(JSONObject(v)) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun fromJson(o: JSONObject) = Rec(
        key = o.optString("key"),
        title = o.optString("title"),
        pic = o.optString("pic"),
        epName = o.optString("epName"),
        url = o.optString("url"),
        referer = o.optString("referer"),
        epsJson = o.optString("epsJson"),
        idx = o.optInt("idx"),
        spiderJson = o.optString("spiderJson"),
        vodId = o.optString("vodId"),
        siteApi = o.optString("siteApi"),
        from = o.optString("from"),
        positionMs = o.optLong("positionMs"),
        durationMs = o.optLong("durationMs"),
        updatedAt = o.optLong("updatedAt")
    )
}
