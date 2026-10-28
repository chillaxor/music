package com.linghit.gdmusic

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 搜索引擎（端侧三段式的第一段）。
 *
 * ⚠️ **实测结论（2026-10-28）**：直接抓「目标站正文」这条路在房产/电商/新闻这类
 * 中文站点上基本走不通，各家反爬手段实测如下：
 *   - 链家 gz.ke.com      → 跳 hip.ke.com/captcha 验证码页，0 字
 *   - 房天下 gz.esf.fang.com → 跳 check.3g.fang.com 风控页，11 字
 *   - 安居客 www/mobile   → 跳 captchaxf-verify 验证码，0 字
 *   - 微信公众号正文     → 在 JS 变量里，<p> 抽不到
 *   - 搜狗微信正文        → weixin.sogou.com/antispider 拦截
 * 而**搜索结果页自己的摘要里反而带关键数据**（参考均价、房源数、单价）。
 *
 * 所以主源换成 **Bing RSS**（cn.bing.com/search?format=RSS）：
 *   - 免反爬、标准 XML、10 条 item；
 *   - description 里直接给摘要（实测「广州城市假日园…参考均价：41006元/㎡…
 *     现有二手房源38套，竣工时间2006-01-01」）；
 *   - link 是**目标站直链**（不是百度那种跳转链接），可直接展示给用户点开。
 * 搜狗/百度退为兜底（拿 h3/h4 标题 + 跳转链接），搜狗微信也一并接入。
 */
data class SearchHit(
    val title: String,
    val url: String,
    /** 搜索结果摘要——含关键数字，是端侧模型真正要读的资料 */
    val snippet: String
)

object SearchEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 多源回退：按顺序试，任一返回非空即用 */
    fun search(query: String): List<SearchHit> {
        val attempts: List<() -> List<SearchHit>> = listOf(
            { bingRss(query) },
            { sogou(query) },
            { baidu(query) }
        )
        for (a in attempts) {
            val hits = runCatching { a() }.getOrNull()
            if (!hits.isNullOrEmpty()) return hits
        }
        return emptyList()
    }

    private fun get(url: String, referer: String? = null): String? = runCatching {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (referer != null) b.header("Referer", referer)
        val req = b.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }.getOrNull()

    // ==================== Bing RSS（主源） ====================

    /**
     * Bing RSS：https://cn.bing.com/search?q=xxx&format=RSS
     * 返回标准 RSS 2.0 XML，item 里含 title / link / description。
     */
    private fun bingRss(q: String): List<SearchHit> {
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") + "&format=RSS"
        val xml = get(url) ?: return emptyList()
        if (xml.contains("<rss") == false) return emptyList()
        val out = mutableListOf<SearchHit>()
        for (m in Regex("""<item>([\s\S]*?)</item>""").findAll(xml)) {
            val body = m.groupValues[1]
            val title = xmlTag(body, "title")
            val link = xmlTag(body, "link")
            val desc = xmlTag(body, "description")
            if (title.isBlank() || link.isBlank()) continue
            out.add(SearchHit(title, link, desc))
            if (out.size >= 8) break
        }
        return out
    }

    /** 取 XML 标签内容，剥 CDATA 与 HTML 标签，反解实体 */
    private fun xmlTag(body: String, tag: String): String {
        val m = Regex("""<$tag[^>]*>([\s\S]*?)</$tag>""", RegexOption.IGNORE_CASE).find(body) ?: return ""
        var s = m.groupValues[1].trim()
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) s = s.substring(9, s.length - 3)
        s = s.replace(Regex("""<[^>]+>"""), " ")
        return decodeEntities(s).replace(Regex("""\s+"""), " ").trim()
    }

    // ==================== 搜狗 / 百度（兜底） ====================

    private fun sogou(q: String): List<SearchHit> {
        val url = "https://www.sogou.com/web?query=" + URLEncoder.encode(q, "UTF-8")
        val html = get(url) ?: return emptyList()
        if (html.contains("antispider") || html.contains("请输入验证码") || html.contains("用户验证")) return emptyList()
        return parseResultTitles(html)
    }

    private fun baidu(q: String): List<SearchHit> {
        val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(q, "UTF-8")
        val html = get(url) ?: return emptyList()
        if (html.contains("百度安全验证") || html.contains("wappass")) return emptyList()
        return parseResultTitles(html)
    }

    /**
     * 通用结果标题扫描：抓 <h3>/<h4> 块里的 <a href>。
     * 搜狗/百度的结果链接是它们自己的跳转地址（sogou.com/link?...、baidu.com/link?url=...），
     * 真实网页由 WebFetcher 跟随重定向取得，这里只需拿到标题+跳转 URL 即可。
     */
    private fun parseResultTitles(html: String): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val seen = mutableSetOf<String>()
        val blockRe = Regex("""<h[34][^>]*>([\s\S]*?)</h[34]>""")
        for (m in blockRe.findAll(html)) {
            val inner = m.groupValues[1]
            val aRe = Regex("""<a\s+[^>]*?href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
            val am = aRe.find(inner) ?: continue
            val url = am.groupValues[1].trim()
            if (!url.startsWith("http")) continue
            val title = decodeEntities(stripTags(am.groupValues[2])).trim()
            if (title.length < 4 || title.length > 80) continue
            val key = url + "|" + title
            if (seen.contains(key)) continue
            seen.add(key)
            out.add(SearchHit(title, url, ""))
            if (out.size >= 8) break
        }
        return out
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "").replace(Regex("""\s+"""), " ").trim()

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toInt(16).toChar().toString() }
}
