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
        var label: String,
        val emoji: String,
        var url: String = ""
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

    /**
     * 链接页改了某条链接（标题/网址）后，同步首页对应的快捷位。
     * 匹配规则：type=link 且 target=链接 id（📌 钉上来和首页添加的都是这个约定）。
     * @return 更新了几条（0=首页没钉这条，无需同步）
     */
    fun updateLink(ctx: Context, linkId: String, label: String, url: String): Int {
        val l = list(ctx)
        var n = 0
        l.forEach { if (it.type == "link" && it.target == linkId) { it.label = label; it.url = url; n++ } }
        if (n > 0) save(ctx, l)
        return n
    }

    /** 链接页删了某条链接：首页对应的快捷位一并删掉（点进去也是空）。@return 删了几条 */
    fun removeLink(ctx: Context, linkId: String): Int {
        val l = list(ctx)
        val before = l.size
        l.removeAll { it.type == "link" && it.target == linkId }
        val removed = before - l.size
        if (removed > 0) save(ctx, l)
        return removed
    }

    /** 整体替换（拖动排序后按新顺序写回） */
    fun replaceAll(ctx: Context, l: List<Item>) {
        save(ctx, l)
    }
}
