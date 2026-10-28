package com.linghit.gdmusic

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 网页抓取 + 正文提取。
 *
 * 提取优先级：
 *   1. 36kr（快讯/文章）：数据在 window.initialState 的 widgetTitle/widgetContent，
 *      直接按 JSON 字段抠出来（已实测可用，约 186 字快讯 / 数千字长文）；
 *   2. 通用：抽 <p> 文本；太少则退化为去标签全文（截断保护）。
 * 非 HTML（图片/PDF 等）直接返回 null，交给上层提示。
 */
data class Article(val title: String, val url: String, val text: String)

object WebFetcher {
    private val client = OkHttpClient.Builder()
        // 超时收紧：反爬站跳验证码时很快返回，但有些站会挂住；别让问答长时间卡在抓取
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 抓取单条链接，返回正文；失败/非文本/空正文返回 null */
    fun fetch(url: String): Article? {
        val html = runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val ct = resp.header("Content-Type") ?: ""
                val isHtml = ct.contains("text/html", ignoreCase = true) ||
                        ct.contains("application/xhtml", ignoreCase = true) || ct.isBlank()
                if (!isHtml) return@use null
                decodeBody(resp.header("Content-Type"), resp.body?.bytes() ?: return@use null)
            }
        }.getOrNull() ?: return null
        return extractArticle(html, url)
    }

    /**
     * 按声明的字符集解码字节。
     *
     * ⚠️ 国内大量站点是 GB2312/GBK（实测 detail.zol.com.cn 返回 `Content-Type: text/html; Charset=gb2312`），
     * 且有些**只在 `<meta charset>` 里声明、响应头不写**。一律按 UTF-8 解会得到满屏「����」，
     * 资料直接废掉。所以：先看响应头，再回退到 HTML 前 2KB 里的 meta 声明。
     */
    private fun decodeBody(contentType: String?, bytes: ByteArray): String {
        var cs: String? = null
        if (contentType != null) {
            cs = Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.getOrNull(1)
        }
        if (cs.isNullOrBlank()) {
            val head = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
            cs = Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.getOrNull(1)
        }
        val charset = when (cs?.lowercase()) {
            "gb2312", "gbk" -> Charset.forName("GBK")
            "gb18030" -> Charset.forName("GB18030")
            "big5", "big5-hkscs" -> Charset.forName("Big5")
            else -> Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    /**
     * 批量抓取，最多返回前 3 条有效资料。
     *
     * ⚠️ 实测（2026-10-28）：中文房产/电商站几乎都会被反爬拦掉（跳验证码/风控页/JS 渲染），
     * 所以这里**不再用「抓不到就丢弃」**——每条命中即使正文抓不到，也用它的搜索摘要兜底，
     * 保证模型手里有料（摘要里通常就带参考均价这类关键数字）。
     * 正文和摘要**合并**使用：正文更全，摘要更准（数字常在摘要里）。
     */
    fun fetchTop(hits: List<SearchHit>): List<Article> {
        val out = mutableListOf<Article>()
        for (h in hits) {
            val raw = fetch(h.url)
            // 正文质量闸门：导航菜单墙（京东首页那种「品牌地图 京东智联云 …」）直接丢掉，
            // 宁可只用摘要——摘要里的参考均价/单价比一堆菜单文案有用得多
            val body = raw?.text?.takeIf { looksLikeContent(it) }
            val snippet = h.snippet.trim()
            val text = when {
                body != null && snippet.isNotEmpty() ->
                    // 摘要已被正文包含时不再重复（省预算）
                    if (body.contains(snippet.take(24))) body else "$body\n$snippet"
                body != null -> body
                // 只用摘要即可：标题由 SnippetBuilder 以「[N] 标题」形式单独拼，不重复塞
                snippet.isNotEmpty() -> snippet
                else -> continue
            }
            out.add(Article(h.title.ifBlank { h.url }, h.url, text))
            if (out.size >= 3) break
        }
        return out
    }

    /**
     * 判断抓到的文本像不像「资料」而不是「导航菜单墙」。
     *
     * 判据分两路：
     *   1. 含明确数据（￥5299 / 16GB / 12.3寸 / 5500元…）→ **一律采信**。
     *      报价表里大量是数字和英文单位，中文占比天然很低，不能用中文比例卡它；
     *   2. 否则看「中文占比高 + 中文标点密度够」→ 判定为散文正文。
     */
    private fun looksLikeContent(text: String): Boolean {
        if (text.length < 60) return false
        if (Regex("""[¥￥]\s*\d|\d[\d,]*\.?\d*\s*(元|万|GB|TB|寸|英寸|ml|mL|马力|kW)""")
                .containsMatchIn(text)) return true
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        if (cjk.toDouble() / text.length < 0.35) return false
        val punct = text.count { it == '，' || it == '。' || it == '、' || it == '；' || it == '！' || it == '？' }
        return punct >= 3
    }

    private fun extractArticle(html: String, url: String): Article? {
        val title = extractTitle(html)
        val text = extractText(html, url)
        if (text.isBlank()) return null
        return Article(title, url, text)
    }

    private fun extractTitle(html: String): String {
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { return decodeEntities(it.trim()) }
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { return decodeEntities(it.trim()) }
        return ""
    }

    private fun extractText(html: String, url: String): String {
        if (url.contains("36kr")) {
            parse36kr(html)?.let { if (it.length > 50) return it }
        }
        // 微信公众号：正文在 <div id="js_content" class="rich_media_content"> 里，不是 <p>
        extractBlockById(html, "js_content")?.let { if (it.length > 80) return it }

        // ⚠️ 报价/参数表：数据在 <td> 里，必须**优先于 <p>**。
        // 实测 detail.zol.com.cn（小米15 Pro 报价页）同一页里既有 <p> 的论坛旧帖、
        // 又有 <td> 的价格表；只抽 <p> 会拿到 2012 年的论坛回复，把真价格丢掉。
        // 判据：表格里出现价格/关键参数（￥、元、GB、寸…）才采信，避免把导航表格当正文。
        val tableText = extractTableCells(html)
        if (tableText != null && tableText.length > 40 && looksLikePriceTable(tableText)) {
            return tableText
        }

        // 通用：抽 <p> 文本
        val paras = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { decodeEntities(stripTags(it.groupValues[1])).trim() }
            .filter { it.length > 10 }
            .toList()
        val joined = paras.joinToString("\n")
        if (joined.length > 120) return collapse(joined)

        // 兜底：抽 <div> 块（很多站点正文用 div 而非 p）
        val divs = Regex("""<div[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { decodeEntities(stripTags(it.groupValues[1])).trim() }
            .filter { it.length > 60 }
            .maxByOrNull { it.length }
        if (divs != null && divs.length > 120) return collapse(divs)

        // 末兜底：去标签后的全文（截断保护）
        return collapse(decodeEntities(stripTags(html))).take(4000)
    }

    /** 抽表格单元格文本（用 | 分隔，保留行内关系） */
    private fun extractTableCells(html: String): String? {
        val cells = Regex("""<td[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map {
                decodeEntities(stripTags(it.groupValues[1]))
                    .replace(Regex("""\s+"""), " ").trim()
            }
            .filter { it.isNotEmpty() && it.length < 40 }
            .toList()
        if (cells.size < 4) return null
        return collapse(cells.joinToString(" | "))
    }

    /** 表格里是否含价格/关键参数（用来区分「报价表」和「导航表格」） */
    private fun looksLikePriceTable(text: String): Boolean =
        Regex("""[¥￥]\s*\d|\d[\d,]*\.?\d*\s*(元|万|GB|TB|寸|英寸|ml|mL|马力|kW)""")
            .containsMatchIn(text)

    /** 按 id 取某个容器的内容（微信正文等） */
    private fun extractBlockById(html: String, id: String): String? {
        val m = Regex("""<div[^>]*id=["']$id["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        val paras = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
            .findAll(m.groupValues[1])
            .map { decodeEntities(stripTags(it.groupValues[1])).trim() }
            .filter { it.length > 5 }
            .toList()
        val joined = paras.joinToString("\n")
        if (joined.length > 60) return collapse(joined)
        return collapse(decodeEntities(stripTags(m.groupValues[1])))
    }

    private fun parse36kr(html: String): String? {
        val title = Regex(""""widgetTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.getOrNull(1)
        val content = Regex(""""widgetContent"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.getOrNull(1)
        if (content == null) return null
        val t = title?.let { unescapeJson(it) } ?: ""
        val c = unescapeJson(content)
        return if (t.isBlank()) c else "$t\n\n$c"
    }

    private fun unescapeJson(s: String): String = s
        .replace("""\\n""", "\n").replace("""\\t""", "\t")
        .replace("""\\r""", "").replace("""\\""""", "\"").replace("""\\/""", "/")
        .replace("""\\\\""", "\\")

    private fun stripTags(s: String): String {
        var x = s.replace(Regex("""(?is)<script[\s\S]*?</script>"""), " ")
        x = x.replace(Regex("""(?is)<style[\s\S]*?</style>"""), " ")
        x = x.replace(Regex("""(?is)<noscript[\s\S]*?</noscript>"""), " ")
        // 去掉导航/页眉页脚/侧栏等页面框架，避免兜底全文被菜单文案淹没
        x = x.replace(Regex("""(?is)<(nav|header|footer|aside|form)[^>]*>[\s\S]*?</\1>"""), " ")
        x = x.replace(Regex("""<[^>]+>"""), " ")
        return x
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun collapse(s: String): String = s
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
