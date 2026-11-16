package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把 so-novel / Legado 两套书源规则解析成统一的 [NovelSource]。
 *
 * 只做"静态规则子集"：CSS 选择器 + @attr/@text/@html/@textNodes + ## 正则替换。
 * 含 <js> / @js: / JSONPath 的复杂源在加载时跳过，避免半吊子执行给出错内容。
 */
object NovelSourceParser {

    fun load(ctx: Context): List<NovelSource> {
        val out = mutableListOf<NovelSource>()
        readAssets(ctx, "novel/legado_full.json").let { arr ->
            for (i in 0 until arr.length()) runCatching { parseLegado(arr.getJSONObject(i)) }
                .getOrNull()?.takeIf { supported(it) }?.let(out::add)
        }
        readAssets(ctx, "novel/sonovel_main.json").let { arr ->
            for (i in 0 until arr.length()) runCatching { parseSonovel(arr.getJSONObject(i)) }
                .getOrNull()?.takeIf { supported(it) }?.let(out::add)
        }
        // 用户导入的订阅覆盖内置：同 id 去重，自定义优先
        val custom = File(ctx.filesDir, "sources_custom.json")
        if (custom.exists()) {
            runCatching {
                val arr = JSONArray(custom.readText(Charsets.UTF_8))
                val customList = mutableListOf<NovelSource>()
                for (i in 0 until arr.length()) runCatching { parseLegado(arr.getJSONObject(i)) }
                    .getOrNull()?.takeIf { supported(it) }?.let(customList::add)
                val ids = customList.map { it.id }.toSet()
                out.removeAll { it.id in ids }
                out.addAll(0, customList)
            }
        }
        return out
    }

    /** 解析用户粘贴的订阅内容（Legado JSON 数组），返回写入自定义文件的条数 */
    fun importCustom(ctx: Context, raw: String): Int {
        val arr = JSONArray(raw.trim())
        val parsed = mutableListOf<NovelSource>()
        for (i in 0 until arr.length()) runCatching { parseLegado(arr.getJSONObject(i)) }
            .getOrNull()?.takeIf { supported(it) }?.let(parsed::add)
        if (parsed.isEmpty()) return 0
        val obj = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (parsed.any { it.id == o.optString("bookSourceUrl") }) obj.put(o)
        }
        File(ctx.filesDir, "sources_custom.json").writeText(obj.toString(), Charsets.UTF_8)
        return parsed.size
    }

    private fun readAssets(ctx: Context, path: String): JSONArray = runCatching {
        JSONArray(ctx.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.getOrDefault(JSONArray())

    private fun parseLegado(o: JSONObject): NovelSource? {
        val base = o.optString("bookSourceUrl").trim().trimEnd('/')
        val name = o.optString("bookSourceName").ifBlank { base }
        if (base.isBlank()) return null
        val searchRaw = o.optString("searchUrl")
        var searchUrl = searchRaw
        var method = "GET"
        var body = ""
        val comma = searchRaw.indexOf(",{")
        if (comma > 0) {
            val urlPart = searchRaw.substring(0, comma)
            val cfgPart = searchRaw.substring(comma + 1)
            runCatching {
                val cfg = JSONObject(cfgPart)
                method = cfg.optString("method", "GET").uppercase()
                body = cfg.optString("body")
                searchUrl = urlPart
            }.onFailure { searchUrl = searchRaw }
        }
        val rs = o.optJSONObject("ruleSearch") ?: JSONObject()
        val rt = o.optJSONObject("ruleToc") ?: JSONObject()
        val rc = o.optJSONObject("ruleContent") ?: JSONObject()
        return NovelSource(
            id = base,
            name = name,
            dialect = "legado",
            baseUrl = base,
            searchUrl = searchUrl,
            searchMethod = method,
            searchBody = body,
            headers = parseHeaders(o.optString("header")),
            bookList = rs.optString("bookList"),
            fieldName = rs.optString("name"),
            fieldAuthor = rs.optString("author"),
            fieldBookUrl = rs.optString("bookUrl"),
            fieldCover = rs.optString("coverUrl"),
            fieldIntro = rs.optString("intro"),
            fieldLatest = rs.optString("lastChapter"),
            tocUrl = rt.optString("url"),
            tocList = rt.optString("chapterList"),
            tocName = rt.optString("chapterName").ifBlank { "text" },
            tocLink = rt.optString("chapterUrl").ifBlank { "href" },
            contentRule = rc.optString("content"),
            nextContentRule = rc.optString("nextContentUrl"),
            filterText = rc.optString("replaceRegex"),
            weight = o.optInt("weight"),
            respondMs = parseRespond(o.optString("respondTime")),
            lastUpdate = o.optString("lastUpdateTime")
        )
    }

    private fun parseSonovel(o: JSONObject): NovelSource? {
        val base = o.optString("url").trim().trimEnd('/')
        val name = o.optString("name").ifBlank { base }
        val s = o.optJSONObject("search") ?: return null
        val t = o.optJSONObject("toc") ?: return null
        val c = o.optJSONObject("chapter") ?: return null
        var searchUrl = s.optString("url").replace("%s", "{{key}}")
        var body = s.optString("data").replace("%s", "{{key}}")
        if (body == "{}") body = ""
        return NovelSource(
            id = "sonovel|$base",
            name = name,
            dialect = "sonovel",
            baseUrl = base,
            searchUrl = searchUrl,
            searchMethod = s.optString("method", "GET").uppercase(),
            searchBody = body,
            bookList = s.optString("result"),
            fieldName = s.optString("bookName"),
            fieldAuthor = s.optString("author"),
            // so-novel 没有独立 bookUrl 字段，书名节点本身就是链接
            fieldBookUrl = "a@href",
            fieldCover = "",
            fieldIntro = "",
            fieldLatest = s.optString("latestChapter"),
            tocUrl = t.optString("url"),
            tocList = t.optString("item"),
            tocName = c.optString("title").ifBlank { "text" },
            tocLink = "a@href",
            contentRule = c.optString("content"),
            nextContentRule = "",
            paragraphRule = c.optString("paragraphTag"),
            filterText = c.optString("filterTxt"),
            weight = 0,
            respondMs = 0L,
            lastUpdate = ""
        )
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            o.keys().forEach { out[it] = o.optString(it) }
            out
        }.getOrDefault(emptyMap())
    }

    private fun parseRespond(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val m = Regex("""([\d.]+)\s*(s|秒|ms|毫秒)?""").find(raw) ?: return 0L
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0L
        return when (m.groupValues[2].lowercase()) {
            "s", "秒" -> (n * 1000).toLong()
            else -> n.toLong()
        }
    }

    private fun supported(s: NovelSource): Boolean {
        val blob = listOf(
            s.searchUrl, s.searchBody, s.bookList, s.fieldName, s.fieldAuthor,
            s.fieldBookUrl, s.fieldCover, s.fieldIntro, s.fieldLatest,
            s.tocUrl, s.tocList, s.tocName, s.tocLink, s.contentRule,
            s.nextContentRule, s.filterText
        ).joinToString("|")
        if (blob.contains("<js>") || blob.contains("@js:") || blob.contains("\$.", ignoreCase = false)) return false
        if (s.bookList.isBlank() || s.fieldName.isBlank() || s.fieldBookUrl.isBlank()) return false
        if (s.tocList.isBlank() || s.contentRule.isBlank()) return false
        return true
    }
}
