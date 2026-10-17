package com.linghit.gdmusic.spider

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Spider 引擎（一期：DexClassLoader 路线）。
 *
 * 背景：盒子迷等 TVBox 多仓配置里 type 3 站点（api 形如 csp_Bili）不是 HTTP 接口，
 * 而由订阅自带的 dex jar 实现（配置的 spider 字段，盒子迷伪装成 .png 且带 ;md5; 校验）。
 * jar 内爬虫类继承宿主提供的 com.github.catvod.crawler.Spider，经
 * com.github.catvod.spider.Init.init(Context) 注册后按类名实例化。
 *
 * 覆盖范围（实测）：jar 内含 Bili/Mogg/Push/Ddys/Dyyj/Wogg/Xb6v/Xydj/Zxzj/Czzy/Kwps/
 * Ddyy/Guazi/Kugou/Living/Market/NongMin/Jinpai/Jianpian/FirstAid/TianTian/Tgsou/
 * HaiYin/PanSearch/Douban/Bili2 等具名爬虫，两仓约 67 个站点可用。
 * XBPQ/AppGet/XYQHiker 等 JS 框架源与 .py 源不在本引擎范围（二期）。
 *
 * 用法（全部异步，结果回调在 watchdog 线程，调用方自行切 UI 线程）：
 *   SpiderEngine.get(ctx).searchAsync(jar, api, ext, key, wd) { json -> }
 */
class SpiderEngine private constructor(private val appCtx: Context) {

    companion object {
        private const val TAG = "VidSpider"
        /** 盒子迷配置里大小写和 jar 类名不一致的站点名 → jar 实际类名 */
        private val ALIAS = mapOf(
            "JianPian" to "Jianpian",
            "DouBan" to "Douban",
            "TGSou" to "Tgsou",
            "LocalFile" to "Local"
        )

        @Volatile
        private var instance: SpiderEngine? = null

        fun get(ctx: Context): SpiderEngine =
            instance ?: synchronized(this) {
                instance ?: SpiderEngine(ctx.applicationContext).also { instance = it }
            }

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        // 爬虫源/订阅服务器同样可能慢或断流；不加超时则 jar 下载或请求会永久挂起，
        // 看门狗虽能兜住 search/detail/play，但下载阶段没有保护，这里统一加上。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 爬虫执行池：网络 IO 密集，4 线程足够（全网搜索并发 6 时会排队） */
    private val pool = Executors.newFixedThreadPool(4)
    /** 看门狗：单独线程等 Future，超时就取消，避免占满执行池导致超时永远不触发 */
    private val watchdog: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    /** jar URL key → DexClassLoader */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    /** jar key + siteKey → 爬虫实例（init 有成本，缓存复用） */
    private val spiders = ConcurrentHashMap<String, Spider>()
    private val jarLocks = ConcurrentHashMap<String, Any>()

    // ===================== 对外异步 API =====================

    fun searchAsync(jar: String, api: String, ext: String, key: String, wd: String, cb: (String?) -> Unit) {
        submit(15_000, cb) {
            val sp = spider(jar, api, ext, key) ?: return@submit null
            // 多数爬虫实现 2 参重载；为空再试 3 参（带页码）
            var r = runCatching { sp.searchContent(wd, false) }.getOrNull()
            if (r.isNullOrEmpty()) r = runCatching { sp.searchContent(wd, false, "1") }.getOrNull()
            r
        }
    }

    fun detailAsync(jar: String, api: String, ext: String, key: String, ids: String, cb: (String?) -> Unit) {
        submit(12_000, cb) {
            val sp = spider(jar, api, ext, key) ?: return@submit null
            val idList = ids.split(',', '#', '$').filter { it.isNotBlank() }
            runCatching { sp.detailContent(idList) }.getOrNull()
        }
    }

    fun playAsync(jar: String, api: String, ext: String, key: String, flag: String, id: String, cb: (String?) -> Unit) {
        submit(15_000, cb) {
            val sp = spider(jar, api, ext, key) ?: return@submit null
            val r = runCatching { sp.playerContent(flag, id, emptyList()) }.getOrNull()
            Log.d(TAG, "playAsync[$key] flag=$flag id=${id.take(40)} => ${r?.take(300)}")
            r
        }
    }

    // ===================== 内部实现 =====================

    /** 提交任务到执行池，看门狗线程等待，超时取消；回调保证且仅保证一次 */
    private fun submit(timeoutMs: Long, cb: (String?) -> Unit, body: () -> String?) {
        val future = pool.submit(body)
        watchdog.schedule({
            try {
                cb(future.get(timeoutMs, TimeUnit.MILLISECONDS))
            } catch (e: Throwable) {
                future.cancel(true)
                Log.w(TAG, "调用超时或失败(${timeoutMs}ms): ${e.javaClass.simpleName} ${e.message}")
                cb(null)
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    /** 取（或创建）爬虫实例：先用自己的 jar，失败时跨 jar 回退 */
    private fun spider(jarSpec: String, api: String, ext: String, siteKey: String): Spider? {
        val cacheKey = md5(jarSpec) + "|" + siteKey
        spiders[cacheKey]?.let { return it }
        val name = api.removePrefix("csp_")
        var sp = loadFrom(jarSpec, name, ext, siteKey)
        if (sp == null) {
            // 跨 jar 回退：摸鱼仓 spider 主机已死（DNS 解析失败），但 Bili/Push 等类
            // 在禁止贩卖的 jar 里也有，让它的站点也能用
            for ((k, loader) in loaders) {
                if (k == md5(jarSpec.split(";md5;")[0].trim())) continue
                sp = instantiate(loader, name, ext, siteKey)
                if (sp != null) {
                    Log.d(TAG, "跨 jar 回退命中: $name <- ${loader.javaClass.simpleName}")
                    break
                }
            }
        }
        if (sp == null) {
            Log.w(TAG, "找不到爬虫类: $api (jar=${jarSpec.take(60)})")
            return null
        }
        spiders[cacheKey] = sp
        return sp
    }

    private fun loadFrom(jarSpec: String, name: String, ext: String, siteKey: String): Spider? {
        val loader = dexLoader(jarSpec) ?: return null
        return instantiate(loader, name, ext, siteKey)
    }

    private fun instantiate(loader: DexClassLoader, name: String, ext: String, siteKey: String): Spider? {
        val clz = try {
            loader.loadClass("com.github.catvod.spider.$name")
        } catch (e: ClassNotFoundException) {
            val alt = ALIAS[name]
            if (alt == null) return null
            try {
                loader.loadClass("com.github.catvod.spider.$alt")
            } catch (e2: ClassNotFoundException) {
                return null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "loadClass $name 失败: ${e.message}")
            return null
        }
        return try {
            val obj = clz.getDeclaredConstructor().newInstance() as Spider
            obj.siteKey = siteKey
            obj.init(appCtx, ext)
            Log.d(TAG, "爬虫就绪: $name (siteKey=$siteKey)")
            obj
        } catch (e: Throwable) {
            Log.w(TAG, "爬虫初始化失败 $name: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 下载（带 md5 校验）并加载 jar，返回 DexClassLoader；失败返回 null */
    private fun dexLoader(jarSpec: String): DexClassLoader? {
        val url = jarSpec.split(";md5;")[0].trim()
        if (url.isEmpty()) return null
        val key = md5(url)
        loaders[key]?.let { return it }
        synchronized(jarLocks.getOrPut(key) { Any() }) {
            loaders[key]?.let { return it }
            val file = ensureJar(jarSpec) ?: return null
            return try {
                file.setReadOnly()
                val odex = File(appCtx.filesDir, "spider/odex").apply { mkdirs() }
                val loader = DexClassLoader(file.absolutePath, odex.absolutePath, null, appCtx.classLoader)
                try {
                    val initClz = loader.loadClass("com.github.catvod.spider.Init")
                    val m = initClz.getMethod("init", Context::class.java)
                    m.invoke(null, appCtx)
                    Log.d(TAG, "Init.init 完成 (${file.name}, ${file.length()}B)")
                } catch (e: Throwable) {
                    Log.w(TAG, "Init.init 失败: ${e.message}")
                }
                loaders[key] = loader
                loader
            } catch (e: Throwable) {
                Log.w(TAG, "DexClassLoader 失败: ${e.message}")
                null
            }
        }
    }

    /** 下载 jar 到 files/spider/<md5(url)>.jar 并校验 md5 */
    private fun ensureJar(jarSpec: String): File? {
        val parts = jarSpec.split(";md5;")
        val url = parts[0].trim()
        val expectMd5 = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (url.isEmpty()) return null
        val dir = File(appCtx.filesDir, "spider").apply { mkdirs() }
        val file = File(dir, "${md5(url)}.jar")
        if (file.exists() && file.length() > 0) {
            if (expectMd5 == null || fileMd5(file).equals(expectMd5, true)) return file
            Log.w(TAG, "jar md5 不符，重新下载: $url")
            file.delete()
        }
        return try {
            Log.d(TAG, "下载 jar: $url")
            val req = Request.Builder().url(url)
                .header("User-Agent", "okhttp/3.15.0")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "jar 下载失败: code=${resp.code} url=$url")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                file.writeBytes(bytes)
            }
            if (expectMd5 != null && !fileMd5(file).equals(expectMd5, true)) {
                Log.w(TAG, "jar md5 校验失败: $url")
                file.delete()
                return null
            }
            Log.d(TAG, "jar 就绪: ${file.name} (${file.length()}B)")
            file
        } catch (e: Exception) {
            Log.w(TAG, "jar 下载异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun fileMd5(f: File): String =
        MessageDigest.getInstance("MD5").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
}
