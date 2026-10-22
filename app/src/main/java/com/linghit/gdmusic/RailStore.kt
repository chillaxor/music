package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 高铁往返数据的原生侧存取。
 *
 * 与记账页共用同一份存储：SharedPreferences「networth_store」的 key「payload」整份 JSON，
 * 其中 rail 对象即高铁页可见的数据 {home,away,trips:[{id,out,back,note,train,cost}]}。
 * 这样记账页的「导出备份 / 导入备份 / 自动备份」自动覆盖高铁数据——一处备份，两头恢复。
 *
 * ⚠️ 所有写入都是「读整份 payload → 只改 rail 键 → 写回」，
 * 绝不整体覆盖，保证 assets/liabs/flows/links/events 等记账字段原样保留。
 * （LinksStore 对 links 键也是同样的读-改-写策略。）
 */
object RailStore {

    private const val FILE = "networth_store"
    private const val KEY = "payload"
    private const val OLD_FILE = "rail_store" // 老版本独立存储，仅迁移用

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readPayload(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun writePayload(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    /** 空 rail 对象（首次进入 / 数据损坏时兜底） */
    fun empty(): JSONObject = JSONObject().apply {
        put("home", "")
        put("away", "")
        put("trips", JSONArray())
    }

    /**
     * 一次性迁移：老版本高铁数据存在独立的 rail_store 里，
     * 搬进 networth_store 的 rail 键后清掉旧数据。
     * 只在 networth 里还没有 rail 字段时搬，绝不覆盖已有数据。
     */
    fun migrateIfNeeded(ctx: Context) {
        val old = ctx.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (old.isBlank()) return
        val payload = readPayload(ctx)
        if (payload.has("rail")) return
        val rail = try { JSONObject(old) } catch (e: Exception) { return }
        if (!rail.has("trips") && !rail.has("home") && !rail.has("away")) return
        payload.put("rail", rail)
        writePayload(ctx, payload)
        ctx.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    /** 读高铁数据；没有返回空对象 */
    fun readRail(ctx: Context): JSONObject {
        migrateIfNeeded(ctx)
        val rail = readPayload(ctx).optJSONObject("rail") ?: return empty()
        if (!rail.has("trips")) rail.put("trips", JSONArray())
        return rail
    }

    /** 页面保存：只合并 rail 键，记账字段原样保留 */
    fun writeRail(ctx: Context, railJson: String) {
        val rail = try { JSONObject(railJson) } catch (e: Exception) { return }
        val payload = readPayload(ctx)
        payload.put("rail", rail)
        writePayload(ctx, payload)
    }

    /** 整份 payload（高铁页「导出备份」用：全量备份，含记账数据，可在记账页导入恢复） */
    fun readPayloadString(ctx: Context): String = readPayload(ctx).toString()
}
