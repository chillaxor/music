package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 书架 / 进度 / 收藏 / 正文缓存，全部落在 app 私有目录，不需要存储权限 */
object NovelStore {

    private const val PREFS = "novel_shelf"
    private const val KEY = "items"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<NovelShelfItem> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NovelShelfItem(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    author = o.optString("author"),
                    sourceId = o.optString("sourceId"),
                    sourceName = o.optString("sourceName"),
                    bookUrl = o.optString("bookUrl"),
                    cover = o.optString("cover"),
                    chapterIndex = o.optInt("chapterIndex"),
                    paragraphIndex = o.optInt("paragraphIndex"),
                    scrollY = o.optInt("scrollY"),
                    favorite = o.optBoolean("favorite"),
                    totalChapters = o.optInt("totalChapters"),
                    lastRead = o.optLong("lastRead")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: String): NovelShelfItem? = list(ctx).firstOrNull { it.id == id }

    fun bookId(book: NovelBook): String {
        val raw = "${book.sourceId}|${book.name}".trim()
        return raw.hashCode().toUInt().toString(16)
    }

    /** 第一次打开某本书 → 进书架 */
    fun touch(ctx: Context, book: NovelBook, totalChapters: Int = 0): NovelShelfItem {
        val id = bookId(book)
        val old = get(ctx, id)
        val item = NovelShelfItem(
            id = id, name = book.name, author = book.author,
            sourceId = book.sourceId, sourceName = book.sourceName,
            bookUrl = book.url, cover = book.cover,
            chapterIndex = old?.chapterIndex ?: 0,
            paragraphIndex = old?.paragraphIndex ?: 0,
            scrollY = old?.scrollY ?: 0,
            favorite = old?.favorite ?: false,
            totalChapters = if (totalChapters > 0) totalChapters else old?.totalChapters ?: 0,
            lastRead = System.currentTimeMillis()
        )
        save(ctx, list(ctx).filter { it.id != id } + item)
        return item
    }

    fun updateProgress(
        ctx: Context, id: String,
        chapterIndex: Int, paragraphIndex: Int = 0, scrollY: Int = 0,
        totalChapters: Int? = null
    ) {
        val current = list(ctx).toMutableList()
        val i = current.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = current[i]
        current[i] = old.copy(
            chapterIndex = chapterIndex,
            paragraphIndex = paragraphIndex,
            scrollY = scrollY,
            totalChapters = totalChapters ?: old.totalChapters,
            lastRead = System.currentTimeMillis()
        )
        save(ctx, current)
    }

    fun toggleFavorite(ctx: Context, id: String): Boolean {
        val current = list(ctx).toMutableList()
        val i = current.indexOfFirst { it.id == id }
        if (i < 0) return false
        val next = !current[i].favorite
        current[i] = current[i].copy(favorite = next)
        save(ctx, current)
        return next
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
        bookDir(ctx, id).deleteRecursively()
    }

    private fun save(ctx: Context, items: List<NovelShelfItem>) {
        val arr = JSONArray()
        items.sortedByDescending { it.lastRead }.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("author", it.author)
                put("sourceId", it.sourceId); put("sourceName", it.sourceName)
                put("bookUrl", it.bookUrl); put("cover", it.cover)
                put("chapterIndex", it.chapterIndex); put("paragraphIndex", it.paragraphIndex)
                put("scrollY", it.scrollY); put("favorite", it.favorite)
                put("totalChapters", it.totalChapters); put("lastRead", it.lastRead)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun bookDir(ctx: Context, id: String): File = File(ctx.filesDir, "novel/$id").apply { mkdirs() }

    fun saveToc(ctx: Context, id: String, chapters: List<NovelChapter>) {
        val arr = JSONArray()
        chapters.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name); put("url", it.url)
            })
        }
        bookDir(ctx, id).resolve("toc.json").writeText(arr.toString(), Charsets.UTF_8)
    }

    fun readToc(ctx: Context, id: String): List<NovelChapter> {
        val f = bookDir(ctx, id).resolve("toc.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NovelChapter(i, o.optString("name"), o.optString("url"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveChapter(ctx: Context, id: String, index: Int, text: String) {
        bookDir(ctx, id).resolve("%04d.txt".format(index)).writeText(text, Charsets.UTF_8)
    }

    fun readChapter(ctx: Context, id: String, index: Int): String {
        val f = bookDir(ctx, id).resolve("%04d.txt".format(index))
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    /** 按章节 URL 缓存正文：目录顺序变化（纠正倒序）也不会串章 */
    fun chapterFile(ctx: Context, id: String, url: String, index: Int): File {
        val key = java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return bookDir(ctx, id).resolve("c_$key.txt")
    }

    fun saveChapterByUrl(ctx: Context, id: String, url: String, index: Int, text: String) {
        chapterFile(ctx, id, url, index).writeText(text, Charsets.UTF_8)
    }

    fun readChapterByUrl(ctx: Context, id: String, url: String, index: Int): String {
        val f = chapterFile(ctx, id, url, index)
        if (f.exists()) return f.readText(Charsets.UTF_8)
        // 兼容旧版按 index 存的缓存
        val old = bookDir(ctx, id).resolve("%04d.txt".format(index))
        return if (old.exists()) old.readText(Charsets.UTF_8) else ""
    }
}
