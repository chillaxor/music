package com.linghit.gdmusic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 运行时订阅 88lin/video_vip 的 user.js，把里面的「事实型配置」同步到本机。
 *
 * ⚠️ 仓库里不含任何第三方解析接口域名。域名、接口前缀、直连模板都是运行时从脚本
 * 文本里提取出来，再写进本机 SharedPreferences。只有脚本的**分发地址**（GitHub raw
 * 与脚本自己声明的 @updateURL 镜像）会写进代码，它们不是解析接口。
 *
 * 提取三类数据：
 *  1. @include       → 官网播放页识别规则，交给 VipLinkRouter
 *  2. videoParseList → 带 url 的解析页前缀，交给 VipParseStore（origin=script）
 *  3. 无损云直连模块里的 SITE / API 模板 → 二期直连搜索的 suggest / detail
 *
 * GPL-3.0 脚本在这里只被当作**文本数据**读取：不复制它的代码，也不执行任何一行。
 * 上游改版时按 @version 拉取新文本重新提取，规则与接口跟着一起更新；
 * 拉取失败一律保留上次成功的数据，绝不把配置清空。
 */
object VipScriptUpdater {

    private const val TAG = "VipScript"
    private const val FILE = "vip_script"
    private const val KEY = "state"
    private const val MAX_BYTES = 1_500_000
    private const val MAX_RULES = 200
    private const val MAX_SOURCES = 50
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 60L * 60 * 1000

    /** 默认订阅候选：脚本仓库 raw 优先，再退到脚本头部 @updateURL 声明的镜像 */
    val DEFAULT_URLS = listOf(
        "https://raw.githubusercontent.com/88lin/video_vip/main/video_vip.user.js",
        "https://cdn.jsdmirror.com/gh/88lin/video_vip@main/video_vip.user.js"
    )

    data class Parsed(
        val version: String,
        val includes: List<String>,
        /** name to prefix */
        val sources: List<Pair<String, String>>,
        val suggest: String?,
        val detail: String?,
        val warnings: List<String>
    )

    data class Result(val ok: Boolean, val message: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun state(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun write(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    private fun put(ctx: Context, key: String, value: Any) {
        val o = state(ctx)
        o.put(key, value)
        write(ctx, o)
    }

    // ---- 状态读取（设置页展示用） ----

    fun version(ctx: Context) = state(ctx).optString("version", "")
    fun lastOk(ctx: Context) = state(ctx).optLong("lastOk", 0L)
    fun lastCheck(ctx: Context) = state(ctx).optLong("lastCheck", 0L)
    fun lastError(ctx: Context) = state(ctx).optString("lastError", "")
    fun fromUrl(ctx: Context) = state(ctx).optString("from", "")
    fun autoEnabled(ctx: Context) = state(ctx).optBoolean("auto", true)
    fun setAutoEnabled(ctx: Context, on: Boolean) = put(ctx, "auto", on)
    fun customUrl(ctx: Context) = state(ctx).optString("url", "")
    fun setCustomUrl(ctx: Context, url: String) = put(ctx, "url", url.trim())

    fun includes(ctx: Context): List<String> {
        val arr = state(ctx).optJSONArray("includes") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /** 上次同步到的脚本源条数（只做展示） */
    fun sourceCount(ctx: Context) = state(ctx).optInt("sourceCount", 0)

    // ---- 纯解析：不碰 Context，方便单测复刻 ----

    private val RX_VERSION = Regex("""//\s*@version\s+(\S+)""")
    private val RX_INCLUDE = Regex("""//\s*@include\s+(\S+)""")
    private val RX_LIST = Regex("""videoParseList\s*:\s*\[([\s\S]*?)\]""")
    private val RX_OBJ = Regex("""\{([^{}]*)\}""")
    private val RX_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")
    private val RX_URL = Regex("\"url\"\\s*:\\s*\"([^\"]*)\"")
    private val RX_SITE = Regex("""const\s+SITE\s*=\s*['"](https?://[^'"]+)['"]""")
    private val RX_API = Regex("""const\s+API\s*=\s*['"](https?://[^'"]+)['"]""")
    private val RX_TPL = Regex("`([^`]+)`")
    private val RX_VAR = Regex("""\$\{[^}]*\}""")

    /**
     * 把脚本当文本解析。所有结果都做长度 / 协议 / 数量限制，防止上游被篡改时
     * 把乱七八糟的内容灌进本机配置。
     */
    fun parse(script: String): Parsed {
        val warn = mutableListOf<String>()
        val version = RX_VERSION.find(script)?.groupValues?.get(1)?.trim() ?: ""
        if (version.isBlank()) warn.add("没读到 @version")

        val includes = RX_INCLUDE.findAll(script)
            .map { it.groupValues[1] }
            .filter { it.startsWith("*://") || it.startsWith("http://") || it.startsWith("https://") }
            .filter { s -> s.none { it.isWhitespace() || it.isISOControl() } && s.length <= 200 }
            .distinct()
            .take(MAX_RULES)
            .toList()
        if (includes.size < 5) warn.add("@include 规则偏少（${includes.size} 条），继续用内置兜底规则")

        val block = RX_LIST.find(script)?.groupValues?.get(1).orEmpty()
        val sources = mutableListOf<Pair<String, String>>()
        if (block.isNotBlank()) {
            RX_OBJ.findAll(block).forEach { m ->
                val o = m.groupValues[1]
                val name = RX_NAME.find(o)?.groupValues?.get(1) ?: return@forEach
                val url = RX_URL.find(o)?.groupValues?.get(1) ?: return@forEach
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
                if (!url.contains("?") && !url.contains("&")) return@forEach
                if (url.length > 500) return@forEach
                sources.add(name.trim() to url)
            }
        }
        val limitedSources = sources.distinctBy { it.second }.take(MAX_SOURCES)
        if (limitedSources.isEmpty()) warn.add("没解析出带 url 的 videoParseList 条目")

        val site = RX_SITE.find(script)?.groupValues?.get(1)
        val api = RX_API.find(script)?.groupValues?.get(1)
        val tpls = RX_TPL.findAll(script).map { it.groupValues[1] }.toList()

        // 优先直接取脚本里的请求模板，路径和参数跟着上游一起变；
        // 模板写法大改时退回「常量 + 已知路径」，再不行就不动旧配置。
        fun usable(t: String?, ph: String): Boolean {
            if (t == null || !t.startsWith("http")) return false
            return t.contains(ph) && t.indexOf(ph) == t.lastIndexOf(ph)
        }

        var suggest = tpls.firstOrNull { it.contains("\${SITE}") && it.contains("suggest") }
            ?.let { RX_VAR.replace(it.replace("\${SITE}", site.orEmpty()), "{title}") }
        var detail = tpls.firstOrNull { it.contains("\${API}") && it.contains("ac=detail") }
            ?.let { RX_VAR.replace(it.replace("\${API}", api.orEmpty()), "{ids}") }

        if (!usable(suggest, "{title}")) {
            suggest = if (site != null && script.contains("/index.php/ajax/suggest"))
                site.trimEnd('/') + "/index.php/ajax/suggest?mid=1&wd={title}" else null
            if (suggest != null) warn.add("联想模板按已知路径重建（脚本模板写法有变化）")
        }
        if (!usable(detail, "{ids}")) {
            detail = if (api != null && script.contains("ac=detail&ids="))
                api + "?ac=detail&ids={ids}" else null
            if (detail != null) warn.add("详情模板按已知路径重建（脚本模板写法有变化）")
        }
        if (suggest == null || detail == null) warn.add("没找到无损云的 SITE / API，直连模板沿用旧值")

        return Parsed(version, includes, limitedSources, suggest, detail, warn)
    }

    // ---- 应用解析结果 ----

    /**
     * 把一份脚本文本应用到本机配置。联网订阅与「粘贴导入」共用这一条路径。
     * 只有解析出有效内容才写状态，失败时原有规则 / 接口 / 模板一律保留。
     */
    fun applyText(ctx: Context, script: String, from: String): Result {
        if (script.length > MAX_BYTES) return Result(false, "脚本超过 1.5MB，已忽略")
        val p = parse(script)
        if (p.version.isBlank() && p.includes.isEmpty() && p.sources.isEmpty()) {
            return Result(false, "没解析出 @version / @include / videoParseList，可能不是目标脚本")
        }

        val arr = JSONArray()
        p.includes.forEach { arr.put(it) }
        val names = JSONArray()
        p.sources.forEach { names.put(it.first) }
        val sTpl = p.suggest
        val dTpl = p.detail

        val o = state(ctx)
        o.put("version", p.version)
        o.put("includes", arr)
        o.put("sourceCount", p.sources.size)
        o.put("sourceNames", names)
        if (sTpl != null) o.put("suggest", sTpl)
        if (dTpl != null) o.put("detail", dTpl)
        o.put("lastOk", System.currentTimeMillis())
        o.put("lastCheck", System.currentTimeMillis())
        o.put("lastError", "")
        o.put("from", from)
        write(ctx, o)

        // 1) 官网识别规则
        VipLinkRouter.applySubscribed(p.includes)
        // 2) 解析页前缀：只替换脚本来源，用户手填的保持不动
        val n = VipParseStore.mergeScriptSources(ctx, p.sources, encoded = false)
        // 3) 直连模板：用户手填过就不覆盖
        val applied = if (sTpl != null && dTpl != null)
            VipParseStore.applyScriptDirect(ctx, sTpl, dTpl) else false

        val msg = buildString {
            append("v").append(p.version.ifBlank { "?" })
            append(" · ").append(p.includes.size).append(" 条规则")
            append(" / ").append(n).append(" 条解析源")
            append(" / 直连模板").append(
                when {
                    sTpl == null || dTpl == null -> "缺失（沿用旧值）"
                    applied -> "已写入"
                    else -> "已保留你手填的值"
                }
            )
            if (p.warnings.isNotEmpty()) append("\n").append(p.warnings.joinToString("；"))
        }
        Log.i(TAG, "apply ok from=$from $msg")
        return Result(true, msg)
    }

    /** 阻塞方法，必须后台线程调用。按候选地址依次尝试，全失败也不动旧数据。 */
    fun fetchAndApply(ctx: Context): Result {
        val urls = mutableListOf<String>()
        val custom = customUrl(ctx).trim()
        if (custom.isNotEmpty()) urls.add(custom)
        urls.addAll(DEFAULT_URLS)

        var lastErr = ""
        for (u in urls.distinct()) {
            if (!u.startsWith("http://") && !u.startsWith("https://")) continue
            try {
                val req = Request.Builder()
                    .url(u)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastErr = "HTTP ${resp.code} @ $u"
                        Log.w(TAG, lastErr)
                        return@use
                    }
                    val body = resp.body
                    if (body == null) {
                        lastErr = "空响应 @ $u"
                        return@use
                    }
                    if (body.contentLength() > MAX_BYTES) {
                        lastErr = "响应过大 @ $u"
                        return@use
                    }
                    val text = body.string()
                    if (text.length > MAX_BYTES || !text.contains("==UserScript==")) {
                        lastErr = "不是用户脚本 @ $u"
                        return@use
                    }
                    return applyText(ctx, text, u)
                }
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "fetch fail $u -> $lastErr")
            }
        }

        // 失败也记一次检查时间，但 1 小时后就允许自动重试（不像成功那样等 24 小时）
        val o = state(ctx)
        o.put("lastError", lastErr.ifBlank { "所有订阅地址都不可用" })
        o.put("lastCheck", System.currentTimeMillis() - INTERVAL_MS + RETRY_MS)
        write(ctx, o)
        return Result(false, "订阅失败：${o.optString("lastError")}\n已保留上次的 v${version(ctx)} 数据")
    }

    /** 冷启动调用：先把缓存规则灌进路由器，再按 24 小时节流做一次后台检查 */
    fun autoCheck(ctx: Context) {
        VipLinkRouter.applySubscribed(includes(ctx))
        if (!autoEnabled(ctx)) return
        val last = lastCheck(ctx)
        if (last > 0 && System.currentTimeMillis() - last < INTERVAL_MS) return
        Thread {
            runCatching { fetchAndApply(ctx) }
                .onFailure { Log.w(TAG, "autoCheck failed: ${it.message}") }
        }.start()
    }

    /** 手动点「立即检查更新」：忽略节流，结果回主线程 */
    fun syncNow(ctx: Context, onDone: (Result) -> Unit) {
        Thread {
            val r = runCatching { fetchAndApply(ctx) }
                .getOrElse { Result(false, "异常：${it.message}") }
            Handler(Looper.getMainLooper()).post { onDone(r) }
        }.start()
    }

    /** 清掉订阅数据并恢复内置规则（不动用户手填的接口与直连模板） */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
        VipLinkRouter.applySubscribed(emptyList())
        VipParseStore.mergeScriptSources(ctx, emptyList(), encoded = false)
    }
}
