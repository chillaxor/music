package com.linghit.gdmusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 小说抓取引擎：书源扇出搜索 + 目录 + 正文，全部走静态规则子集 */
object NovelEngine {

    private const val HEALTH_PREFS = "novel_source_health"
    /** 连续失败 3 次后临时停用该源，30 分钟后自动恢复 */
    private const val COOLDOWN_MS = 30L * 60 * 1000
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private var cachedSources: List<NovelSource>? = null

    fun sources(ctx: Context): List<NovelSource> {
        cachedSources?.let { return it }
        val list = NovelSourceParser.load(ctx)
        cachedSources = list
        return list
    }

    fun invalidate() { cachedSources = null }

    suspend fun search(ctx: Context, keyword: String, maxSources: Int = 80): NovelSearchResult = withContext(Dispatchers.IO) {
        val all = sources(ctx)
        val candidates = all.filter { healthy(ctx, it.id) }
            .sortedWith(compareByDescending<NovelSource> { it.weight }.thenBy { it.respondMs })
            .take(maxSources)
        val gate = Semaphore(8)
        val key = URLEncoder.encode(keyword.trim(), "UTF-8")
        val lists = candidates.map { src ->
            async {
                gate.acquire()
                try {
                    val html = fetch(ctx, src, src.searchUrl.replace("{{key}}", key).replace("{{page}}", "1"), src.searchBody.replace("{{key}}", key).replace("{{page}}", "1"))
                    if (html.isBlank()) { fail(ctx, src.id); emptyList() }
                    else {
                        succeed(ctx, src.id)
                        val doc = Jsoup.parse(html)
                        val nodes = NovelRule.evalList(doc, src.bookList)
                        nodes.mapNotNull { el ->
                            val name = NovelRule.eval(el, src.fieldName).trim()
                            val url = NovelRule.resolve(NovelRule.eval(el, src.fieldBookUrl).trim(), src.baseUrl)
                            if (name.isBlank() || url.isBlank() || !url.startsWith("http")) null
                            else NovelBook(
                                sourceId = src.id, sourceName = src.name, name = name,
                                author = NovelRule.eval(el, src.fieldAuthor).trim(),
                                url = url,
                                cover = NovelRule.resolve(NovelRule.eval(el, src.fieldCover).trim(), src.baseUrl),
                                intro = NovelRule.eval(el, src.fieldIntro).trim(),
                                latest = NovelRule.eval(el, src.fieldLatest).trim()
                            )
                        }
                    }
                } catch (e: Exception) {
                    fail(ctx, src.id)
                    emptyList()
                } finally {
                    gate.release()
                }
            }
        }.awaitAll()

        val seen = HashSet<String>()
        val merged = mutableListOf<NovelBook>()
        for (book in lists.flatten()) {
            val k = dedupeKey(book.name, book.author)
            if (k.isBlank() || !seen.add(k)) continue
            merged.add(book)
        }
        NovelSearchResult(
            books = merged.take(80),
            failedSources = lists.count { it.isEmpty() },
            totalSources = candidates.size,
            skippedSources = all.size - candidates.size
        )
    }

    suspend fun loadToc(ctx: Context, book: NovelBook): List<NovelChapter> = withContext(Dispatchers.IO) {
        val src = sources(ctx).firstOrNull { it.id == book.sourceId } ?: return@withContext emptyList()
        try {
            val doc = Jsoup.parse(fetch(ctx, src, book.url, ""))
            val nodes = NovelRule.evalList(doc, src.tocList)
            if (nodes.isNotEmpty()) succeed(ctx, src.id)
            val list = nodes.mapIndexedNotNull { i, el ->
                val name = NovelRule.eval(el, src.tocName).trim().ifBlank { "第${i + 1}章" }
                val url = NovelRule.resolve(NovelRule.eval(el, src.tocLink).trim(), book.url)
                if (url.isBlank()) null else NovelChapter(i, name, url)
            }
            normalizeTocOrder(list)
        } catch (e: Exception) {
            fail(ctx, src.id)
            emptyList()
        }
    }

    /**
     * 有些站点的目录是倒序的（最新章节排最前）。这里统一纠正成「第一章在最前」，
     * 否则 App 的「下一章」会变成往回翻。判定方式：比较首尾章节名里的序号。
     * 同时去掉重复章节（部分站点目录里同一章会重复出现）。
     */
    fun normalizeTocOrder(list: List<NovelChapter>): List<NovelChapter> {
        val deduped = LinkedHashMap<String, NovelChapter>()
        for (c in list) {
            val k = c.name.trim() + "@" + c.url
            if (!deduped.containsKey(k)) deduped[k] = c
        }
        var out = deduped.values.toList()
        if (out.size < 2) return out.mapIndexed { i, c -> c.copy(index = i) }
        val firstNo = chapterNo(out.first().name)
        val lastNo = chapterNo(out.last().name)
        if (firstNo != null && lastNo != null && firstNo > lastNo) {
            out = out.reversed()
        }
        return out.mapIndexed { i, c -> c.copy(index = i) }
    }

    /** 从章节名里提取数字序号，如「第1357章 光阴之外（终）」→ 1357 */
    private fun chapterNo(name: String): Int? {
        val m = Regex("第\\s*([0-9零一二三四五六七八九十百千万两]+)\\s*[章回节話话]").find(name) ?: return null
        return cnToInt(m.groupValues[1])
    }

    /** 中文数字转阿拉伯数字（支持到万位） */
    private fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
        var total = 0
        var section = 0
        var number = 0
        for (ch in s) {
            when {
                ch in digits -> number = digits.getValue(ch)
                ch in units -> {
                    val u = units.getValue(ch)
                    if (u == 10000) {
                        total += (section + number) * u
                        section = 0
                        number = 0
                    } else {
                        if (number == 0) number = 1
                        section += number * u
                        number = 0
                    }
                }
                else -> return null
            }
        }
        return total + section + number
    }

    suspend fun loadChapter(ctx: Context, sourceId: String, url: String): String = withContext(Dispatchers.IO) {
        val src = sources(ctx).firstOrNull { it.id == sourceId } ?: return@withContext ""
        try {
            val html = fetch(ctx, src, url, "")
            if (html.isBlank()) return@withContext ""
            var raw = extractText(ctx, src, html)
            if (raw.isNotBlank()) succeed(ctx, src.id)
            // 部分站点章节分页（正文尾部带「第(1/3)页」标记，续页 URL 为 xxx.html → xxx_2.html）
            raw = appendPagedChapters(ctx, src, url, raw)
            // so-novel 的 filterTxt 是竖线分隔的正则
            if (src.filterText.isNotBlank() && src.dialect == "sonovel") {
                src.filterText.split('|').filter { it.isNotBlank() }.forEach { p ->
                    runCatching { raw = Regex(p, RegexOption.DOT_MATCHES_ALL).replace(raw, "") }
                }
            }
            if (src.paragraphRule.isNotBlank()) {
                runCatching { raw = raw.split(Regex(src.paragraphRule)).joinToString("\n") }
            }
            // 通用广告串，规则站普遍存在
            listOf(
                "请记住本书首发域名", "天才一秒记住本站地址", "一秒记住", "手机用户请浏览",
                "内容更新后请重新刷新页面", "本章未完，请点击下一页"
            ).forEach { raw = raw.replace(it, "") }
            raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        } catch (e: Exception) {
            fail(ctx, src.id)
            ""
        }
    }

    /** 按规则从章节 HTML 抽正文 */
    private fun extractText(ctx: Context, src: NovelSource, html: String): String {
        val doc = Jsoup.parse(html)
        var raw = NovelRule.eval(doc, src.contentRule)
        // @html 规则拿到的是 HTML 片段，转成带段落换行的纯文本
        if (raw.contains("<") && raw.contains(">")) raw = NovelRule.htmlToText(raw)
        return raw
    }

    /** 章节分页补全：正文里出现「第(1/3)页」这类标记时，把后续分页抓下来接在后面 */
    private fun appendPagedChapters(ctx: Context, src: NovelSource, url: String, raw: String): String {
        val pageMark = Regex("第\\s*\\(?\\s*(\\d+)\\s*/\\s*(\\d+)\\s*\\)?\\s*页")
        val m = pageMark.find(raw) ?: return raw
        val cur = m.groupValues[1].toIntOrNull() ?: return raw
        val total = m.groupValues[2].toIntOrNull() ?: return raw
        if (total <= 1 || cur >= total || total > 20) return raw
        // 去掉分页标记本身（注意：Kotlin 的 StringBuilder.replace 返回新对象，必须用返回值）
        val sb = StringBuilder(pageMark.replace(raw, ""))
        for (p in (cur + 1)..total) {
            val nextUrl = nextPageUrl(url, p)
            if (nextUrl.isBlank()) break
            val html = runCatching { fetch(ctx, src, nextUrl, "") }.getOrDefault("")
            if (html.isBlank()) break
            val txt = extractText(ctx, src, html)
            if (txt.isBlank()) break
            sb.append('\n').append(pageMark.replace(txt, ""))
        }
        return sb.toString()
    }

    /** 由 xxx.html 推导 xxx_2.html / xxx_3.html；不认识的形态返回空 */
    private fun nextPageUrl(url: String, page: Int): String {
        if (page < 2) return ""
        return when {
            url.endsWith(".html") -> url.removeSuffix(".html") + "_$page.html"
            else -> ""
        }
    }

    private fun fetch(ctx: Context, src: NovelSource, rawUrl: String, bodyRaw: String): String {
        val url = NovelRule.resolve(rawUrl, src.baseUrl)
        if (!url.startsWith("http")) return ""
        val builder = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        src.headers.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        if (src.baseUrl.contains("douban")) builder.header("Referer", "https://m.douban.com/")
        if (src.searchMethod == "POST" && bodyRaw.isNotBlank()) {
            val body = bodyRaw.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
            builder.post(body)
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val contentType = resp.body?.contentType()
            val bytes = resp.body?.bytes() ?: return ""
            val charset = runCatching { contentType?.charset() ?: guessCharset(bytes) }.getOrDefault(Charsets.UTF_8)
            return String(bytes, charset)
        }
    }

    private fun guessCharset(bytes: ByteArray): Charset {
        return runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            Charsets.UTF_8
        }.getOrDefault(Charset.forName("GBK"))
    }

    private fun dedupeKey(name: String, author: String): String {
        val n = name.lowercase().replace(Regex("""[\[\]【】()（）\s·:：,，.。!！?？\-_]"""), "")
        val a = author.lowercase().replace(Regex("""[\s·:：,，.。\-_]"""), "")
        return "$n|$a"
    }

    private fun healthy(ctx: Context, id: String): Boolean {
        val prefs = ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(id, null) ?: return true
        val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val fails = o.optInt("fails", 0)
        if (fails < 3) return true
        // 连续失败超过 3 次才临时停用，且 30 分钟后自动恢复（源随时可能修好，不做永久封杀）
        val lastFail = o.optLong("lastFail", 0L)
        val cooled = System.currentTimeMillis() - lastFail > COOLDOWN_MS
        if (cooled) {
            // 冷却期已过，清零并放行，给它一次翻身机会
            prefs.edit().remove(id).apply()
            return true
        }
        return false
    }

    private fun fail(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(id, "{}") ?: "{}"
        val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val fails = o.optInt("fails", 0) + 1
        o.put("fails", fails)
        o.put("lastFail", System.currentTimeMillis())
        prefs.edit().putString(id, o.toString()).apply()
    }

    /** 源成功后清零失败计数，让偶发失败的源能自动恢复 */
    private fun succeed(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(id, null) != null) prefs.edit().remove(id).apply()
    }
}
