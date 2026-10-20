package com.linghit.gdmusic

import android.content.Context
import org.json.JSONObject

/**
 * 影视仓「片头 / 片尾跳过」配置，按**影片详情**维度保存。
 *
 * key = 详情标识（"d:<站点api>|<vod_id>" 或 "s:<源key>|<vod_id>"，
 * 即播放历史 key 去掉集名后的部分，由 VideoActivity 从 metaJson 推导）：
 *  - 同一部影片下的所有剧集、所有线路共用一份配置（用户配一次，切集不用再配）；
 *  - 其他影片 key 不同，互不影响。
 *
 * startMs / endMs 为 0 表示不设置；都为 0 时删除该条记录。
 */
object VideoSkipStore {
    private const val FILE = "video_skip"
    private const val KEY = "configs"

    /** 一条跳过配置 */
    class Cfg(val startMs: Long, val endMs: Long) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("s", startMs); put("e", endMs)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    /** 取某影片的配置；没配过返回 null */
    fun get(ctx: Context, detailKey: String): Cfg? {
        if (detailKey.isBlank()) return null
        val raw = readMap(ctx)[detailKey] ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Cfg(o.optLong("s"), o.optLong("e"))
        }.getOrNull()
    }

    /** 保存；start/end 都 <=0 视为清除该影片配置 */
    fun save(ctx: Context, detailKey: String, startMs: Long, endMs: Long) {
        if (detailKey.isBlank()) return
        val m = readMap(ctx)
        if (startMs <= 0 && endMs <= 0) m.remove(detailKey)
        else m[detailKey] = Cfg(startMs.coerceAtLeast(0), endMs.coerceAtLeast(0)).toJson().toString()
        saveMap(ctx, m)
    }
}
