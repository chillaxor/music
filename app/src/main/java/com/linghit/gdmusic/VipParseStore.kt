package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * VIP 链接解析的配置存取（阶段 1：数据层）。
 *
 * 与 LinksStore / HomeShortcutStore 同一范式：object + SharedPreferences + 一份 JSON。
 * 存储文件「vip_parse」，key「config」，结构：
 *   { enabled, fallbackOnFail, selectedId, sources: [{id,name,prefix,encoded,enabled}] }
 *
 * ⚠️ 这里刻意**不内置任何第三方解析接口地址**。接口全部由用户在设置页自行填写，
 * 既不把来路不明的站点写进 public 仓库的 git 历史，也避免 GPL 代码的直接复制。
 *
 * prefix 是「解析接口前缀」，原链接直接拼在它后面，例如：
 *   https://example.com/?url=  +  https://v.qq.com/x/cover/xxx.html
 * encoded=true 时对原链接做 URL 编码（现有 video.html resolveViaParses 的行为），
 * encoded=false 时原样拼接（88lin 脚本 showPlayerWindow 的行为）。两种都要能配。
 */
object VipParseStore {

    private const val FILE = "vip_parse"
    private const val KEY = "config"

    data class Source(
        val id: String,
        val name: String,
        val prefix: String,
        /** true=原链接 URL 编码后拼接；false=原样拼接 */
        val encoded: Boolean = true,
        val enabled: Boolean = true,
        /** 请求解析页时附带的 header JSON（从内置订阅 parses.ext.header 导入时保留），空串＝用默认 */
        val headerJson: String = ""
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("prefix", prefix)
            put("encoded", encoded)
            put("enabled", enabled)
            put("headerJson", headerJson)
        }

        companion object {
            fun from(o: JSONObject) = Source(
                id = o.optString("id"),
                name = o.optString("name"),
                prefix = o.optString("prefix"),
                encoded = o.optBoolean("encoded", true),
                enabled = o.optBoolean("enabled", true),
                headerJson = o.optString("headerJson", "")
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun read(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun write(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    private fun put(ctx: Context, key: String, value: Any) {
        val o = read(ctx)
        o.put(key, value)
        write(ctx, o)
    }

    // ---- 开关 ----

    /** 总开关，默认关闭：没配接口、没接线前绝不改变自定义链接的原有行为 */
    fun isEnabled(ctx: Context) = read(ctx).optBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) = put(ctx, "enabled", on)

    /** 所有解析源都失败时是否回退到原网页（内嵌浏览器），默认开 */
    fun fallbackOnFail(ctx: Context) = read(ctx).optBoolean("fallbackOnFail", true)

    fun setFallbackOnFail(ctx: Context, on: Boolean) = put(ctx, "fallbackOnFail", on)

    // ---- 解析源列表 ----

    fun sources(ctx: Context): MutableList<Source> {
        val arr = read(ctx).optJSONArray("sources") ?: return mutableListOf()
        val out = mutableListOf<Source>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Source.from(o))
        }
        return out
    }

    private fun saveSources(ctx: Context, list: List<Source>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        put(ctx, "sources", arr)
    }

    fun replaceAll(ctx: Context, list: List<Source>) = saveSources(ctx, list)

    private fun newId() = "v" + System.nanoTime().toString(36)

    /** 新增（同前缀去重）；返回是否真的新增 */
    fun add(ctx: Context, name: String, prefix: String, encoded: Boolean): Boolean {
        val p = prefix.trim()
        val list = sources(ctx)
        if (list.any { it.prefix == p }) return false
        list.add(Source(newId(), name.trim().ifBlank { "线路" }, p, encoded))
        saveSources(ctx, list)
        return true
    }

    fun remove(ctx: Context, id: String): Boolean {
        val list = sources(ctx)
        val before = list.size
        list.removeAll { it.id == id }
        if (list.size == before) return false
        saveSources(ctx, list)
        if (selectedId(ctx) == id) setSelectedId(ctx, "")
        return true
    }

    fun update(ctx: Context, src: Source): Boolean {
        val list = sources(ctx)
        val i = list.indexOfFirst { it.id == src.id }
        if (i < 0) return false
        list[i] = src
        saveSources(ctx, list)
        return true
    }

    // ---- 默认（首选）解析源 ----

    fun selectedId(ctx: Context) = read(ctx).optString("selectedId", "")

    fun setSelectedId(ctx: Context, id: String) = put(ctx, "selectedId", id)

    fun selectedSource(ctx: Context): Source? {
        val id = selectedId(ctx)
        if (id.isBlank()) return null
        return sources(ctx).firstOrNull { it.id == id && it.enabled }
    }

    /** 阶段 3 用：可用的解析源，首选排在最前面 */
    fun orderedSources(ctx: Context): List<Source> {
        val all = sources(ctx).filter { it.enabled }
        val sel = selectedSource(ctx) ?: return all
        return listOf(sel) + all.filter { it.id != sel.id }
    }

    // ---- 输入校验 / 批量导入 ----

    /** 返回错误提示；null 表示可以用 */
    fun validatePrefix(prefix: String): String? {
        val p = prefix.trim()
        return when {
            p.isBlank() -> "接口前缀不能为空"
            !p.startsWith("http://", true) && !p.startsWith("https://", true) ->
                "必须是 http:// 或 https:// 开头"
            !p.contains("?") && !p.contains("&") ->
                "前缀里要带 ?url= 或 &url=，原链接会直接拼在后面"
            else -> null
        }
    }

    /**
     * 批量导入，每行「名称|接口前缀|1或0」；# 开头是注释。
     * 与现有前缀相同的行会被跳过。@return 实际导入条数
     */
    fun importLines(ctx: Context, text: String): Int {
        var n = 0
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 2) return@forEach
                val prefix = parts[1]
                if (validatePrefix(prefix) != null) return@forEach
                val encoded = parts.getOrNull(2) != "0"
                if (add(ctx, parts[0], prefix, encoded)) n++
            }
        return n
    }

}
