package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页「每日一诗」卡片的数据模型 + 本地存储。
 *
 * prefs：poem_store
 *  - enabled           是否在首页显示这张卡片（设置页开关）
 *  - default_expanded  卡片默认展开整首(true) 还是折叠成一行(false)（设置页开关）
 *  - date / poem       当天缓存（跨天自动失效，避免重复请求被接口限频）
 *  - token             今日诗词的用户 token（同一 token 每日返回同一首）
 *  - favs              收藏的诗词 id 集合
 */
data class Poem(
    val id: String,
    val quote: String,          // 名句：折叠态那一行
    val title: String,          // 诗名
    val dynasty: String,        // 朝代
    val author: String,         // 作者
    val lines: List<String>,    // 全诗（接口已按展示行切好）
    val translate: List<String> // 逐句译文，接口可能不给（空列表）
)

object PoemStore {
    private const val SP = "poem_store"
    private const val K_ENABLED = "enabled"
    private const val K_EXPANDED = "default_expanded"
    private const val K_DATE = "date"
    private const val K_POEM = "poem"
    private const val K_TOKEN = "token"
    private const val K_FAVS = "favs"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 是否在首页显示每日一诗卡片 */
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_ENABLED, on).apply()
    }

    /** 卡片默认展开整首（true）还是折叠成一行（false） */
    fun isDefaultExpanded(ctx: Context): Boolean = sp(ctx).getBoolean(K_EXPANDED, false)

    fun setDefaultExpanded(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_EXPANDED, on).apply()
    }

    fun token(ctx: Context): String? = sp(ctx).getString(K_TOKEN, null)

    fun setToken(ctx: Context, token: String?) {
        sp(ctx).edit().putString(K_TOKEN, token).apply()
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 写入当天缓存（date 一并刷新） */
    fun save(ctx: Context, p: Poem) {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("quote", p.quote)
        o.put("title", p.title)
        o.put("dynasty", p.dynasty)
        o.put("author", p.author)
        o.put("lines", JSONArray(p.lines))
        o.put("translate", JSONArray(p.translate))
        sp(ctx).edit().putString(K_DATE, today()).putString(K_POEM, o.toString()).apply()
    }

    /** 只认今天的缓存；跨天返回 null，调用方再去联网取 */
    fun load(ctx: Context): Poem? {
        val s = sp(ctx)
        if (s.getString(K_DATE, "") != today()) return null
        val raw = s.getString(K_POEM, null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    private fun parse(o: JSONObject) = Poem(
        id = o.optString("id"),
        quote = o.optString("quote"),
        title = o.optString("title"),
        dynasty = o.optString("dynasty"),
        author = o.optString("author"),
        lines = o.optJSONArray("lines").toStringList(),
        translate = o.optJSONArray("translate").toStringList()
    )

    fun favIds(ctx: Context): Set<String> =
        sp(ctx).getStringSet(K_FAVS, emptySet())?.toSet() ?: emptySet()

    /** 收藏/取消收藏，返回操作后的状态（true = 已收藏） */
    fun toggleFav(ctx: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val s = favIds(ctx).toMutableSet()
        val on = !s.contains(id)
        if (on) s.add(id) else s.remove(id)
        sp(ctx).edit().putStringSet(K_FAVS, s).apply()
        return on
    }
}

/** JSONArray -> List&lt;String&gt;，null 安全 */
internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}
