package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 小说入口页：书架 / 全网搜索 / 阅读 / 书源管理，逻辑都在 assets/novel.html */
class NovelActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        title = "小说"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView = WebView(this)
        setContentView(webView)
        webView.setBackgroundColor(themeBg())
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.setSupportZoom(false)
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "NovelAndroid")
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.loadUrl("file:///android_asset/novel.html")
    }

    private fun themeBg(): Int {
        val p = ThemePrefs.currentPack(this)
        val rgb = Color.rgb(Color.red(p.textPrimary), Color.green(p.textPrimary), Color.blue(p.textPrimary))
        val dark = rgb > 0x7F7F7F
        return if (dark) p.fallbackA else p.background
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.__novelBack&&window.__novelBack()") { r ->
            if (r != "true") runOnUiThread { finish() }
        }
    }

    private fun async(cbId: String, block: suspend () -> String) {
        scope.launch {
            val result = runCatching { block() }.getOrDefault(err("执行失败"))
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("window.__novelCb($cbId,${JSONObject.quote(result)})", null)
            }
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
    private fun ok(b: JSONObject.() -> Unit) = JSONObject().apply { put("ok", true); b() }.toString()

    private fun shelfJson(): String {
        val arr = JSONArray()
        NovelStore.list(this@NovelActivity).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("author", it.author)
                put("sourceId", it.sourceId); put("sourceName", it.sourceName)
                put("bookUrl", it.bookUrl); put("cover", it.cover)
                put("chapterIndex", it.chapterIndex); put("totalChapters", it.totalChapters)
                put("favorite", it.favorite); put("lastRead", it.lastRead)
            })
        }
        return arr.toString()
    }

    private inner class Bridge {

        @JavascriptInterface fun theme(): String {
            val p = ThemePrefs.currentPack(this@NovelActivity)
            return JSONObject().apply {
                put("fg", hex(p.textPrimary)); put("muted", hex(p.textSecondary))
                put("accent", hex(if (Color.red(p.textPrimary) > 0x7F) 0xFF2C8C7C.toInt() else 0xFF7FD9C6.toInt()))
                put("dark", Color.red(p.textPrimary) > 0x7F)
            }.toString()
        }

        private fun hex(c: Int) = "#%06X".format(0xFFFFFF and c)

        @JavascriptInterface fun shelf(): String = shelfJson()

        @JavascriptInterface fun sourceStats(): String {
            val all = NovelEngine.sources(this@NovelActivity)
            return ok {
                put("total", all.size)
                put("legado", all.count { it.dialect == "legado" })
                put("sonovel", all.count { it.dialect == "sonovel" })
            }
        }

        @JavascriptInterface fun search(keyword: String, cbId: String) = async(cbId) {
            val r = NovelEngine.search(this@NovelActivity, keyword)
            val arr = JSONArray()
            r.books.forEach {
                arr.put(JSONObject().apply {
                    put("id", NovelStore.bookId(it)); put("name", it.name); put("author", it.author)
                    put("sourceId", it.sourceId); put("sourceName", it.sourceName); put("url", it.url)
                    put("cover", it.cover); put("intro", it.intro); put("latest", it.latest)
                })
            }
            ok {
                put("books", arr); put("failed", r.failedSources)
                put("total", r.totalSources); put("skipped", r.skippedSources)
            }
        }

        @JavascriptInterface fun openBook(bookJson: String, cbId: String) = async(cbId) {
            val o = JSONObject(bookJson)
            val book = NovelBook(
                sourceId = o.optString("sourceId"), sourceName = o.optString("sourceName"),
                name = o.optString("name"), author = o.optString("author"),
                url = o.optString("url"), cover = o.optString("cover"),
                intro = o.optString("intro"), latest = o.optString("latest")
            )
            var chapters = NovelStore.readToc(this@NovelActivity, NovelStore.bookId(book))
            if (chapters.isEmpty()) chapters = NovelEngine.loadToc(this@NovelActivity, book)
            // 统一去重 + 纠正倒序，并回写缓存（旧缓存可能是倒序且有重复项）
            chapters = NovelEngine.normalizeTocOrder(chapters)
            NovelStore.saveToc(this@NovelActivity, NovelStore.bookId(book), chapters)
            val item = NovelStore.touch(this@NovelActivity, book, chapters.size)
            val arr = JSONArray()
            chapters.take(3000).forEach {
                arr.put(JSONObject().apply { put("index", it.index); put("name", it.name) })
            }
            ok {
                put("item", JSONObject().apply {
                    put("id", item.id); put("name", item.name); put("author", item.author)
                    put("sourceName", item.sourceName); put("chapterIndex", item.chapterIndex)
                    put("paragraphIndex", item.paragraphIndex); put("favorite", item.favorite)
                })
                put("chapters", arr)
            }
        }

        @JavascriptInterface fun chapter(id: String, index: Int, cbId: String) = async(cbId) {
            val item = NovelStore.get(this@NovelActivity, id)
                ?: return@async err("书架里没有这本书")
            val chapters = NovelStore.readToc(this@NovelActivity, id)
            val url = chapters.getOrNull(index)?.url.orEmpty()
            var text = if (url.isNotBlank()) NovelStore.readChapterByUrl(this@NovelActivity, id, url, index)
                else NovelStore.readChapter(this@NovelActivity, id, index)
            if (text.isBlank() && url.isNotBlank()) {
                text = NovelEngine.loadChapter(this@NovelActivity, item.sourceId, url)
                if (text.isNotBlank()) NovelStore.saveChapterByUrl(this@NovelActivity, id, url, index, text)
            }
            ok {
                put("name", chapters.getOrNull(index)?.name.orEmpty())
                put("text", text)
                put("cached", text.isNotBlank())
            }
        }

        @JavascriptInterface fun progress(id: String, chapterIndex: Int, paragraphIndex: Int, scrollY: Int) {
            NovelStore.updateProgress(this@NovelActivity, id, chapterIndex, paragraphIndex, scrollY)
        }

        @JavascriptInterface fun favorite(id: String): Boolean = NovelStore.toggleFavorite(this@NovelActivity, id)

        @JavascriptInterface fun remove(id: String) {
            NovelStore.remove(this@NovelActivity, id)
        }

        /** 清除阅读记录（保留收藏），返回清掉的本数 */
        @JavascriptInterface fun clearRecent(): Int =
            NovelStore.clearRecent(this@NovelActivity, keepFavorite = true)

        @JavascriptInterface fun importSources(url: String, cbId: String) = async(cbId) {
            val raw = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                http.newCall(req).execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
            if (raw.isBlank()) return@async err("订阅下载失败")
            val n = NovelSourceParser.importCustom(this@NovelActivity, raw)
            NovelEngine.invalidate()
            if (n == 0) err("没有可用的静态规则书源") else ok { put("count", n) }
        }

        @JavascriptInterface fun clearImported() {
            java.io.File(this@NovelActivity.filesDir, "sources_custom.json").delete()
            NovelEngine.invalidate()
        }

        @JavascriptInterface fun settings(): String {
            val sp = getSharedPreferences("novel_settings", MODE_PRIVATE)
            return JSONObject().apply {
                put("font", sp.getInt("font", 19))
                put("line", sp.getInt("line", 175))
                put("night", sp.getBoolean("night", false))
            }.toString()
        }

        @JavascriptInterface fun setSetting(key: String, value: String) {
            val sp = getSharedPreferences("novel_settings", MODE_PRIVATE)
            sp.edit().apply {
                when (key) {
                    "font" -> putInt("font", value.toIntOrNull() ?: 19)
                    "line" -> putInt("line", value.toIntOrNull() ?: 175)
                    "night" -> putBoolean("night", value == "true")
                }
            }.apply()
        }
    }
}
