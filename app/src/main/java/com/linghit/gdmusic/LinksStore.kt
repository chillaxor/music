package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 链接页（记账页 index.html）链接数据的原生侧存取。
 *
 * 与 NetAssetActivity 的 saveData/loadData 共用同一份存储：
 * SharedPreferences「networth_store」的 key「payload」整份 JSON，
 * 其中 links 数组即链接页可见的链接 [{id,title,url,cat,note}]。
 *
 * 用途：首页「＋→自定义链接」添加的网址要同时进链接页；
 * 链接页编辑/删除时要能反向找到首页快捷位（按链接 id 匹配）。
 *
 * ⚠️ 只写 SharedPreferences，不写用户自选目录的备份 json——那份是次级兜底，
 * 链接页下次任何保存都会整份重写（含新链接）。loadData 优先读 prefs，不受影响。
 */
object LinksStore {

    private const val FILE = "networth_store"
    private const val KEY = "payload"

    data class Link(
        val id: String,
        val title: String,
        val url: String,
        val cat: String = "fav",
        val note: String = ""
    )

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

    private fun linksArray(o: JSONObject): JSONArray {
        val a = o.optJSONArray("links") ?: JSONArray()
        o.put("links", a) // 没有就建一个空数组挂上去
        return a
    }

    /** 链接页当前所有链接 */
    fun links(ctx: Context): List<Link> {
        val arr = readPayload(ctx).optJSONArray("links") ?: return emptyList()
        val out = mutableListOf<Link>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Link(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    url = o.optString("url"),
                    cat = o.optString("cat", "fav"),
                    note = o.optString("note")
                )
            )
        }
        return out
    }

    /**
     * 新增一条链接；同网址已存在则直接返回其 id（不重复加）。
     * @return 链接 id（新生成或已有的）
     */
    fun addOrGet(ctx: Context, title: String, url: String): String {
        val o = readPayload(ctx)
        val arr = linksArray(o)
        // 同网址去重：沿用已有 id，链接页不出现重复条目
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("url") == url) return it.optString("id")
        }
        val id = "h" + System.currentTimeMillis()
        arr.put(
            JSONObject().apply {
                put("id", id)
                put("title", title)
                put("url", url)
                put("cat", "fav")
                put("note", "")
            }
        )
        writePayload(ctx, o)
        return id
    }

    /** 编辑链接（标题/网址）。找不到该 id 返回 false */
    fun update(ctx: Context, id: String, title: String, url: String): Boolean {
        val o = readPayload(ctx)
        val arr = o.optJSONArray("links") ?: return false
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                it.put("title", title)
                it.put("url", url)
                writePayload(ctx, o)
                return true
            }
        }
        return false
    }

    /** 删除链接。找不到返回 false */
    fun remove(ctx: Context, id: String): Boolean {
        val o = readPayload(ctx)
        val arr = o.optJSONArray("links") ?: return false
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) { removed = true; continue }
            out.put(it)
        }
        if (removed) { o.put("links", out); writePayload(ctx, o) }
        return removed
    }
}
