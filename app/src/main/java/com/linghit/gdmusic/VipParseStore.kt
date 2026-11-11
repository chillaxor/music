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
        val headerJson: String = "",
        /** "script" = 脚本订阅同步来的；"user" = 用户手填。刷新脚本时只替换前者 */
        val origin: String = "user"
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("prefix", prefix)
            put("encoded", encoded)
            put("enabled", enabled)
            put("headerJson", headerJson)
            put("origin", origin)
        }

        companion object {
            fun from(o: JSONObject) = Source(
                id = o.optString("id"),
                name = o.optString("name"),
                prefix = o.optString("prefix"),
                encoded = o.optBoolean("encoded", true),
                enabled = o.optBoolean("enabled", true),
                headerJson = o.optString("headerJson", ""),
                origin = o.optString("origin", "user")
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

    /**
     * 脚本订阅专用：用脚本里的解析前缀替换**上一批脚本来源**，
     * 用户手填的接口（origin=user）一律保留；同前缀不重复添加。
     * 已有脚本条目会复用 id / encoded，避免刷新后首选线路和修改过的拼接方式丢失。
     * 脚本 showPlayerWindow 是裸拼，所以新条目默认 encoded=false。
     * @return 实际生效的脚本条目数
     */
    fun mergeScriptSources(ctx: Context, list: List<Pair<String, String>>, encoded: Boolean): Int {
        val cur = sources(ctx)
        val keep = cur.filter { it.origin != "script" }.toMutableList()
        val oldScript = cur.filter { it.origin == "script" }.associateBy { it.prefix }
        var n = 0
        list.take(50).forEach { (name, prefix) ->
            val p = prefix.trim()
            if (p.isEmpty() || validatePrefix(p) != null) return@forEach
            if (keep.any { it.prefix == p }) return@forEach
            val old = oldScript[p]
            keep.add(
                Source(
                    id = old?.id ?: newId(),
                    name = name.trim().ifBlank { "脚本线路" },
                    prefix = p,
                    encoded = old?.encoded ?: encoded,
                    enabled = old?.enabled ?: true,
                    headerJson = old?.headerJson.orEmpty(),
                    origin = "script"
                )
            )
            n++
        }
        saveSources(ctx, keep)
        return n
    }

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

    // ---- 二期：直连搜索（片名联想 → 详情取播放列表）----
    //
    // 两条模板由用户自行填写，App 不内置任何第三方接口域名。
    // suggest 模板用 {title} 占位（会自动做 URL 编码），返回 {"list":[{"id","name"}]}；
    // detail 模板用 {ids} 占位（逗号分隔的 id），返回 {"list":[{"vod_id","vod_play_url", ...}]}。
    // 这是通用采集接口的常见形状，具体地址交给使用者，不写进 public 仓库的 git 历史。

    private fun direct(ctx: Context) = read(ctx).optJSONObject("direct") ?: JSONObject()

    fun directEnabled(ctx: Context) = direct(ctx).optBoolean("enabled", false)

    fun directSuggest(ctx: Context) = direct(ctx).optString("suggest", "")

    fun directDetail(ctx: Context) = direct(ctx).optString("detail", "")

    /** 设置页手动保存：标记为手填，之后脚本订阅不再覆盖 */
    fun setDirectConfig(ctx: Context, enabled: Boolean, suggest: String, detail: String) {
        put(
            ctx, "direct", JSONObject()
                .put("enabled", enabled)
                .put("suggest", suggest.trim())
                .put("detail", detail.trim())
                .put("fromScript", false)
        )
    }

    /**
     * 脚本订阅专用：把订阅到的 suggest / detail 模板写进直连配置。
     * - 用户手填过（fromScript=false 且模板非空）→ 不覆盖，返回 false；
     * - 首次订阅（模板为空）→ 顺手把直连开关打开，做到「订阅完就能用」；
     * - 后续刷新只换模板，不重新打开用户主动关掉的开关。
     * @return 是否真的写入了模板
     */
    fun applyScriptDirect(ctx: Context, suggest: String, detail: String): Boolean {
        val cur = direct(ctx)
        val userFilled = cur.optString("suggest", "").isNotBlank() && !cur.optBoolean("fromScript", false)
        if (userFilled) return false
        val first = cur.optString("suggest", "").isBlank()
        put(
            ctx, "direct", JSONObject()
                .put("enabled", if (first) true else cur.optBoolean("enabled", false))
                .put("suggest", suggest.trim())
                .put("detail", detail.trim())
                .put("fromScript", true)
        )
        return true
    }

    /** 模板必须带对应占位符，且是 http(s) 地址 */
    fun validateTemplate(tpl: String, placeholder: String): String? {
        val t = tpl.trim()
        return when {
            t.isBlank() -> "模板不能为空"
            !t.startsWith("http://", true) && !t.startsWith("https://", true) ->
                "必须是 http:// 或 https:// 开头"
            !t.contains(placeholder) -> "模板里要带 $placeholder 占位符"
            else -> null
        }
    }

    /** 开关打开且两条模板都填对，才启用直连路径；否则仍走一期解析接口 */
    fun directConfigured(ctx: Context): Boolean =
        directEnabled(ctx) &&
            validateTemplate(directSuggest(ctx), "{title}") == null &&
            validateTemplate(directDetail(ctx), "{ids}") == null

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
