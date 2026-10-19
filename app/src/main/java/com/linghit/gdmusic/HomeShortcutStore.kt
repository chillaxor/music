package com.linghit.gdmusic

import android.content.Context
import org.json.JSONObject

/**
 * 首页自定义资源位：不限量、可删除。
 *
 * 两种类型：
 *  - page：App 内功能，target ∈ video/netasset/add/flow/journal/links/downloads/favorites/settings
 *  - link：资产页某条链接，target=链接 id，url=链接地址（记账页链接卡片 📌 经 JS 桥写入）
 */
object HomeShortcutStore {
    private const val FILE = "home_shortcuts"
    private const val KEY = "list"

    class Item(
        val type: String,
        val target: String,
        val label: String,
        val emoji: String,
        val url: String = ""
    ) {
        fun id() = "$type|$target"
        fun toJson() = JSONObject().apply {
            put("type", type); put("target", target); put("label", label)
            put("emoji", emoji); put("url", url)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<Item> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<Item>()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        type = o.optString("type"),
                        target = o.optString("target"),
                        label = o.optString("label"),
                        emoji = o.optString("emoji"),
                        url = o.optString("url")
                    )
                )
            }
        } catch (e: Exception) { }
        return out
    }

    private fun save(ctx: Context, l: List<Item>) {
        val arr = org.json.JSONArray()
        l.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** 添加（同 id 去重）；返回是否新增 */
    fun add(ctx: Context, it: Item): Boolean {
        val l = list(ctx)
        if (l.any { x -> x.id() == it.id() }) return false
        l.add(it); save(ctx, l); return true
    }

    fun remove(ctx: Context, id: String) {
        val l = list(ctx)
        l.removeAll { it.id() == id }
        save(ctx, l)
    }

    /** 整体替换（拖动排序后按新顺序写回） */
    fun replaceAll(ctx: Context, l: List<Item>) {
        save(ctx, l)
    }
}
