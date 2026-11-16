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
            nodes.mapIndexedNotNull { i, el ->
                val name = NovelRule.eval(el, src.tocName).trim().ifBlank { "第${i + 1}章" }
                val url = NovelRule.resolve(NovelRule.eval(el, src.tocLink).trim(), book.url)
                if (url.isBlank()) null else NovelChapter(i, name, url)
            }
        } catch (e: Exception) {
            fail(ctx, src.id)
            emptyList()
        }
    }

    suspend fun loadChapter(ctx: Context, sourceId: String, url: String): String = withContext(Dispatchers.IO) {
        val src = sources(ctx).firstOrNull { it.id == sourceId } ?: return@withContext ""
        try {
            val html = fetch(ctx, src, url, "")
            if (html.isBlank()) return@withContext ""
            val doc = Jsoup.parse(html)
            var raw = NovelRule.eval(doc, src.contentRule)
            // @html 规则拿到的是 HTML 片段，转成带段落换行的纯文本
            if (raw.contains("<") && raw.contains(">")) raw = NovelRule.htmlToText(raw)
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
        return runCatching { JSONObject(raw).optInt("fails", 0) < 3 }.getOrDefault(true)
    }

    private fun fail(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(id, "{}") ?: "{}"
        val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        o.put("fails", o.optInt("fails", 0) + 1)
        o.put("lastFail", System.currentTimeMillis())
        prefs.edit().putString(id, o.toString()).apply()
    }
}
