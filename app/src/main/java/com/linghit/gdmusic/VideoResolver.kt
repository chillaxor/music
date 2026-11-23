package com.linghit.gdmusic

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 播放地址解析链 —— 与 assets/video.html 的 playSpiderEp 同口径，供原生影视版复用。
 *
 * WebView 版的解析优先级（video.html:1941-1963）：
 *   1) parse:0 且已是媒体直链 → 直接用
 *   2) parse:1 且 jx:0 → 离屏 WebView 直嗅播放页（iframe / JS 拼接地址）
 *   3) resolveViaParses → 订阅里 parses 接口逐个试（HTTP 型优先，3 并发）
 *   4) sniffStream 静态正则兜底（var main / mac_url）
 *   5) 都不行 → 交回 null，由调用方提示「换线路」，绝不把非直链丢给播放器
 *
 * 原版只有第 4 步，且会透传加密串给 ExoPlayer 导致 FileNotFoundException。
 */
class VideoResolver(private val ctx: Context) {

    companion object {
        private const val TAG = "VidResolver"
        const val SNIFF_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private val HOP_BY_HOP = setOf(
            "host", "connection", "content-length", "transfer-encoding",
            "accept-encoding", "range", "upgrade", "proxy-connection", "keep-alive", "te"
        )
        private val MEDIA_RE = Regex("""\.(?:m3u8|mp4|flv|mpd|ts|mkv|mov|avi|webm)(?:[?#&]|$)""", RegexOption.IGNORE_CASE)
        private const val CACHE_PREF = "gd_native_resolve"
        private const val CACHE_TTL = 6 * 3600 * 1000L
        private const val CACHE_MAX = 200
        private const val WEB_SNIFF_MS = 13_000L
        private const val HTTP_TIMEOUT_MS = 12_000
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true).followSslRedirects(true).build()
    private val cache: SharedPreferences = ctx.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE)

    /** 解析结果：url + 最终请求头 + 命中方式（日志/诊断用） */
    data class Result(val url: String, val headers: Map<String, String>, val by: String)

    fun isMediaUrl(u: String): Boolean {
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        return MEDIA_RE.containsMatchIn(path) || path.contains("/index.m3u8")
    }

    // ==================== 直链缓存（对齐 video.html resolveC*） ====================
    private fun hash(s: String): String {
        var h = 5381
        for (c in s) h = ((h shl 5) + h + c.code) and 0x7fffffff
        return h.toString(36)
    }

    private fun ck(kind: String, src: String, flag: String, seed: String) =
        "$kind|$src|$flag|${hash(seed)}"

    fun cacheGet(kind: String, src: String, flag: String, seed: String): Result? = try {
        val key = ck(kind, src, flag, seed)
        val raw = cache.getString(key, null) ?: return null
        val o = JSONObject(raw)
        val url = o.optString("url")
        if (url.isBlank() || System.currentTimeMillis() - o.optLong("ts") > CACHE_TTL) {
            cache.edit().remove(key).apply(); null
        } else {
            val hm = HashMap<String, String>()
            o.optJSONObject("headers")?.let { h ->
                val it = h.keys()
                while (it.hasNext()) { val k = it.next(); hm[k] = h.optString(k) }
            }
            Result(url, hm, "缓存")
        }
    } catch (e: Exception) { null }

    private fun cachePut(kind: String, src: String, flag: String, seed: String, url: String, headers: Map<String, String>) {
        if (url.isBlank()) return
        try {
            val key = ck(kind, src, flag, seed)
            val h = JSONObject(); headers.forEach { (k, v) -> h.put(k, v) }
            cache.edit().putString(key, JSONObject()
                .put("url", url).put("headers", h).put("ts", System.currentTimeMillis()).toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "cachePut: ${e.message}") }
    }

    // ==================== 静态正则嗅探（对齐 VideoActivity.extractStream） ====================
    fun sniffStatic(pageUrl: String, referer: String): String? = try {
        val rb = Request.Builder().url(pageUrl)
            .header("User-Agent", SNIFF_UA).header("Accept", "*/*")
        if (referer.isNotBlank()) rb.header("Referer", referer)
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            Regex("""var\s+main\s*=\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)
                ?.let { return absUrl(it.groupValues[1], pageUrl) }
            Regex("""mac_url\s*=\s*['"]([^'"]+)['"]""").find(html)?.let { m ->
                val first = m.groupValues[1].split('#').firstOrNull()?.split('$')?.lastOrNull()?.trim()
                if (!first.isNullOrBlank()) return absUrl(first, pageUrl)
            }
            Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4|flv|mpd)""").find(html)
                ?.let { return absUrl(it.value, pageUrl) }
            Regex("""var\s+\w*[Uu]rl\w*\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)
                ?.let { return absUrl(it.groupValues[1], pageUrl) }
            null
        }
    } catch (e: Exception) { Log.w(TAG, "sniffStatic fail: ${e.message}"); null }

    private fun absUrl(u: String, base: String): String = try {
        if (u.startsWith("http", true)) u
        else android.net.Uri.parse(base).let { "${it.scheme}://${it.host}" + if (u.startsWith("/")) u else "/$u" }
    } catch (e: Exception) { u }

    // ==================== 离屏 WebView 嗅探（对齐 VideoActivity.startWebSniff） ====================
    /** 后台线程调用：post 到主线程建 WebView，latch 等回调，不阻塞主线程。 */
    fun webSniff(url: String, headers: Map<String, String>, scripts: List<String>): Result? {
        val box = AtomicReference<Result?>()
        val latch = CountDownLatch(1)
        main.post { launchWebSniff(url, headers, scripts, latch, box) }
        try { latch.await(WEB_SNIFF_MS + 2500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { return null }
        return box.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun launchWebSniff(
        url: String, headers: Map<String, String>, scripts: List<String>,
        latch: CountDownLatch, box: AtomicReference<Result?>
    ) {
        val wv = try { WebView(ctx) } catch (e: Exception) { latch.countDown(); return }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: SNIFF_UA
        }
        var done = false
        fun emit(found: String?) {
            if (done) return
            done = true
            if (!found.isNullOrBlank()) box.set(Result(found, headers, "WebView直嗅"))
            runCatching { wv.stopLoading(); wv.destroy() }
            latch.countDown()
            Log.d(TAG, "webSniff => ${found ?: "未命中"} url=${url.take(110)}")
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (MEDIA_RE.containsMatchIn(u)) {
                    Log.d(TAG, "webSniff 拦截: ${u.take(150)}")
                    main.post { emit(u) }
                }
                return null
            }
            override fun onPageFinished(view: WebView, page: String) {
                scripts.forEach { runCatching { view.evaluateJavascript(it, null) } }
            }
        }
        val extra = headers.filterKeys { !HOP_BY_HOP.contains(it.lowercase()) }
        runCatching { wv.loadUrl(url, extra) }.onFailure { emit(null) }
        main.postDelayed({ emit(null) }, WEB_SNIFF_MS.toLong())
    }

    // ==================== parses 在线接口（对齐 video.html resolveViaParses） ====================
    private fun norm(u: String): String = try {
        val b = android.net.Uri.parse(u)
        val host = b.host ?: return u
        val ascii = IDN.toASCII(host)
        if (ascii == host) u else u.replace(host, ascii, true)
    } catch (e: Exception) { u }

    private fun pickStream(text: String?): String {
        if (text.isNullOrBlank()) return ""
        try {
            val j = JSONObject(text)
            val c = listOf(
                j.optString("url"), j.optString("playUrl"),
                j.optJSONObject("data")?.optString("url"), j.optJSONObject("data")?.optString("playUrl"),
                j.optJSONObject("result")?.optString("url")
            )
            for (u in c) if (!u.isNullOrBlank() && u.startsWith("http", true)) return u
        } catch (e: Exception) { /* 纯文本走正则 */ }
        Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4|flv|mpd)[^"'\s\\]*""").find(text)
            ?.let { return it.value }
        return ""
    }

    private fun scriptsFor(cfg: JSONObject?, pageUrl: String): List<String> {
        val out = ArrayList<String>()
        val rules = cfg?.optJSONArray("rules") ?: return out
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val hs = r.optJSONArray("hosts")
            if (hs != null && hs.length() > 0) {
                var hit = false
                for (j in 0 until hs.length()) {
                    val raw = hs.optString(j)
                    val needle = raw.replace(".*", "").replace("*", "")
                    if (needle.isNotBlank() && pageUrl.contains(needle, true)) { hit = true; break }
                }
                if (!hit) continue
            }
            r.optJSONArray("script")?.let { ss ->
                for (j in 0 until ss.length()) out.add(ss.optString(j))
            }
        }
        return out
    }

    private fun mergeHeaders(base: Map<String, String>, ext: Any?): Map<String, String> {
        val out = LinkedHashMap(base)
        when (ext) {
            is JSONObject -> {
                val it = ext.keys()
                while (it.hasNext()) { val k = it.next(); val v = ext.optString(k); if (v.isNotBlank()) out[k] = v }
            }
            is String -> runCatching {
                val j = JSONObject(ext)
                val it = j.keys()
                while (it.hasNext()) { val k = it.next(); val v = j.optString(k); if (v.isNotBlank()) out[k] = v }
            }
        }
        return out
    }

    /** HTTP 型解析接口逐个试（3 并发批），命中即返回；type0 WebView 型也支持。 */
    fun resolveViaParses(url: String, headers: Map<String, String>, cfg: JSONObject?): Result? {
        val ps = cfg?.optJSONArray("parses") ?: return null
        data class Job(val type: Int, val name: String, val urlTpl: String, val ext: Any?)
        val jobs = ArrayList<Job>()
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            val tpl = p.optString("url")
            if (tpl.isBlank() || tpl.equals("Demo", true) || tpl.equals("Web", true)) continue
            var type = p.optInt("type", 1)
            if (type == 3) continue
            val eObj = p.optJSONObject("ext")
            val ext = eObj?.opt("header")
            jobs.add(Job(type, p.optString("name").ifBlank { "parse$i" }, tpl, ext))
        }
        // HTTP 型(type!=0) 优先，WebView 型(type0) 排后，避免过早开离屏 WebView
        jobs.sortBy { if (it.type == 0) 1 else 0 }
        Log.d(TAG, "resolveViaParses parses=${jobs.size} url=${url.take(110)}")
        var batch = 0
        while (batch < jobs.size) {
            val slice = jobs.drop(batch).take(3)
            val refs = slice.map { AtomicReference<Result?>() }
            val threads = slice.mapIndexed { i, job ->
                Thread {
                    val s = System.currentTimeMillis()
                    try {
                        val full = norm(job.urlTpl + URLEncoder.encode(url, "UTF-8"))
                        val h = mergeHeaders(headers, job.ext)
                        val got: Result? = if (job.type == 0) {
                            webSniff(full, h, scriptsFor(cfg, full))
                        } else {
                            val rb = Request.Builder().url(full)
                            h.forEach { (k, v) -> if (!HOP_BY_HOP.contains(k.lowercase())) rb.header(k, v) }
                            if (h.keys.none { it.equals("User-Agent", true) }) rb.header("User-Agent", SNIFF_UA)
                            client.newCall(rb.build()).execute().use { resp ->
                                val u = pickStream(resp.body?.string().orEmpty())
                                if (u.isNotBlank()) Result(u, h, job.name) else null
                            }
                        }
                        if (got != null) refs[i].set(got)
                        Log.d(TAG, "parse ${job.name} ${if (got != null) "命中" else "未命中"} (${System.currentTimeMillis() - s}ms)")
                    } catch (e: Exception) {
                        Log.w(TAG, "parse ${job.name} fail: ${e.message}")
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join(16_000) }
            for (r in refs) r.get()?.let { return it }
            batch += 3
        }
        return null
    }

    /**
     * 完整解析链（对齐 video.html playSpiderEp）：
     * @param raw            playerContent 返回的 url（可能是直链/播放页/加密串/http）
     * @param isParse        playerContent 的 parse 标志（1=需二次解析）
     * @param jx             playerContent 的 jx 标志（0=播放页自带解析，优先直嗅）
     * @param headers        playerContent 返回的 header
     * @param cfg            订阅配置（parses/rules）
     * @param referer        站点 referer（兜底）
     * @param cacheSeed      缓存 key 种子（回填前原始集 url）
     */
    fun resolve(
        raw: String, isParse: Boolean, jx: Int, headers: Map<String, String>,
        cfg: JSONObject?, referer: String, cacheKind: String, cacheSrc: String,
        cacheFlag: String, cacheSeed: String
    ): Result? {
        if (raw.isBlank()) return null
        // 缓存命中（必须在 raw 被回填前用 seed）
        cacheGet(cacheKind, cacheSrc, cacheFlag, cacheSeed)?.let { return it }

        // parse:0 且已是媒体直链 → 直接用
        if (!isParse && isMediaUrl(raw)) {
            val r = Result(raw, headers, "直链")
            cachePut(cacheKind, cacheSrc, cacheFlag, cacheSeed, raw, headers)
            return r
        }
        // 非 http → 不可能是播放页，绝不丢给播放器（加密串就在这里被挡住）
        if (!raw.startsWith("http", true)) {
            Log.w(TAG, "非 http 地址，跳过: ${raw.take(100)}")
            return null
        }

        var finalUrl = if (isMediaUrl(raw)) raw else ""
        var finalHeaders = headers
        if (isParse && finalUrl.isEmpty()) {
            if (jx == 0) {
                webSniff(raw, headers, scriptsFor(cfg, raw))?.let {
                    finalUrl = it.url; finalHeaders = mergeHeaders(headers, it.headers)
                }
            }
            if (finalUrl.isEmpty()) {
                resolveViaParses(raw, headers, cfg)?.let {
                    finalUrl = it.url; finalHeaders = mergeHeaders(headers, it.headers)
                }
            }
        }
        if (finalUrl.isEmpty()) {
            sniffStatic(raw, headers["Referer"] ?: referer)?.let { finalUrl = it }
        }
        if (finalUrl.isEmpty() || !isMediaUrl(finalUrl)) {
            Log.w(TAG, "解析链未得到直链: ${raw.take(110)} -> ${finalUrl.take(110)}")
            return null
        }
        cachePut(cacheKind, cacheSrc, cacheFlag, cacheSeed, finalUrl, finalHeaders)
        return Result(finalUrl, finalHeaders, "解析链")
    }
}
