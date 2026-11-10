package com.linghit.gdmusic.spider

import android.app.Application
import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.lang.reflect.Modifier
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
 * 2026-11-09：内置影视仓同款 10 个 dex jar（assets/tvbox/jar/<md5(jarUrl)>.jar，
 * 见 manifest.json），带自有 jar 的 XBPQ/AppGet 等站点也可用 DexClassLoader 加载；
 * 外链 .js 与 .py 源仍需二期 QuickJS / Chaquopy，本引擎不处理。
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
    @Volatile private var builtInLoaded = false

    // ===================== 对外异步 API =====================

    fun searchAsync(jar: String, api: String, ext: String, key: String, wd: String, cb: (String?) -> Unit) {
        submit(15_000, cb) {
            val sp = spider(jar, api, ext, key) ?: return@submit null
            // 多数爬虫实现 2 参重载；为空再试 3 参（带页码）
            var r = runCatching { sp.searchContent(wd, false) }
                .onFailure { Log.w(TAG, "searchContent 失败[$key/$api]: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            if (r.isNullOrEmpty()) r = runCatching { sp.searchContent(wd, false, "1") }
                .onFailure { Log.w(TAG, "searchContent(3参) 失败[$key/$api]: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            r
        }
    }

    fun detailAsync(jar: String, api: String, ext: String, key: String, ids: String, cb: (String?) -> Unit) {
        submit(12_000, cb) {
            val sp = spider(jar, api, ext, key) ?: return@submit null
            // TVBox 标准：detailContent 收的是 List<String>，多数 jar 只取 [0]=vod_id。
            // 旧逻辑按 ',' '#' '$' 一刀切，把 Ikanbot 的 "id$$$名称$$$封面" 与
            // SP360 的 JSON 型 id 拆成一堆碎片，jar 取 [1]/[2] 越界 → 详情恒为 null。
            // 先整体传，失败再按 "$$$" 还原成 TVBox 的 [id,name,pic] 三段。
            var r = runCatching { sp.detailContent(listOf(ids)) }
                .onFailure { Log.w(TAG, "detailContent 失败[$key/$api] 整体: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            if (r.isNullOrEmpty() && ids.contains("$$$")) {
                r = runCatching { sp.detailContent(ids.split("$$$")) }
                    .onFailure { Log.w(TAG, "detailContent 失败[$key/$api] 三段: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            r
        }
    }

    fun playAsync(jar: String, api: String, ext: String, key: String, flag: String, id: String, cb: (String?) -> Unit) {
        submit(15_000, cb) {
            val sp = spider(jar, api, ext, key) ?: return@submit null
            val r = runCatching { sp.playerContent(flag, id, emptyList()) }
                .onFailure { Log.w(TAG, "playerContent 失败[$key/$api]: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
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
                val cause = e.cause ?: e
                Log.w(TAG, "调用超时或失败(${timeoutMs}ms): ${e.javaClass.simpleName} ${e.message} / cause=${cause.javaClass.simpleName}: ${cause.message}", cause)
                cb(null)
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    /** 冷启动把 assets/tvbox/jar 全部装进 loaders，建立全局类表。
     *  影视仓是切线路时一次性 oat 编译全部 jar、任意 csp_ 都能全局找类；
     *  旧逻辑的跨 jar 回退只遍历「本次会话已经加载过的」jar，Qiyou 这类
     *  只在另一份 jar 里的类冷启动永远找不到。 */
    private fun ensureBuiltInLoaders() {
        if (builtInLoaded) return
        synchronized(this) {
            if (builtInLoaded) return
            val dir = File(appCtx.filesDir, "spider").apply { mkdirs() }
            val odex = File(dir, "odex").apply { mkdirs() }
            val names = runCatching { appCtx.assets.list("tvbox/jar") }.getOrNull() ?: emptyArray()
            names.filter { it.endsWith(".jar") }.forEach { n ->
                val key = n.removeSuffix(".jar")
                if (loaders.containsKey(key)) return@forEach
                val f = File(dir, n)
                runCatching {
                    if (!f.exists() || f.length() == 0L) {
                        appCtx.assets.open("tvbox/jar/$n").use { f.writeBytes(it.readBytes()) }
                    }
                    f.setReadOnly()
                    val loader = DexClassLoader(f.absolutePath, odex.absolutePath, null, appCtx.classLoader)
                    injectContext(loader)
                    loaders[key] = loader
                }.onFailure { Log.w(TAG, "内置 jar 预加载失败 $n: ${it.message}") }
            }
            builtInLoaded = true
            Log.d(TAG, "内置 jar 预加载完成，全局类表 ${loaders.size} 个 loader")
        }
    }

    /**
     * TVBox 标准由 Init.init(ctx) 注册全局 Application；但那个方法还会启动 Go 代理、
     * 悬浮球、拉起多个后台线程（详见 11-10 排查）。这里只做 init() 的第一条指令：
     * 把宿主 Application 写进 jar 的 Init Context 字段，其余副作用一概不碰。
     *
     * 没有这一步，jar 的 detailContent → saveName() → KV 存储会取到 null Context 抛 NPE，
     * 被 runCatching 吞成 null，表现为「搜得到、点进去详情加载失败」。
     * 各 jar 混淆不同（新摸鱼 jar 是实例字段 c，老 box jar 是静态字段 C），
     * 故按字段类型查找，不按名字硬编码。
     */
    private fun injectContext(loader: DexClassLoader) {
        val clz = runCatching { loader.loadClass("com.github.catvod.spider.Init") }.getOrNull() ?: return
        val app = appCtx.applicationContext
        val fields = runCatching { clz.declaredFields }.getOrNull() ?: emptyArray()
        val field = fields.firstOrNull { it.type == Application::class.java }
            ?: fields.firstOrNull { it.type == Context::class.java }
        if (field == null) {
            // 兜底：走标准 init，仅极少数没有可识别字段的 jar 会到这里
            runCatching { clz.getDeclaredMethod("init", Context::class.java).invoke(null, app) }
                .onFailure { Log.w(TAG, "Init.init 降级失败: ${it.javaClass.simpleName}: ${it.message}") }
            return
        }
        val isStatic = Modifier.isStatic(field.modifiers)
        val holder = if (isStatic) null else runCatching {
            val getter = runCatching { clz.getDeclaredMethod("get") }.getOrNull()
                ?: clz.declaredMethods.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == clz
                }
            getter?.apply { isAccessible = true }?.invoke(null)
        }.getOrNull()
        if (!isStatic && holder == null) {
            Log.w(TAG, "Init 单例获取失败，跳过 Context 注入 (${field.name})")
            return
        }
        runCatching {
            field.isAccessible = true
            if (field.get(holder) == null) field.set(holder, app)
            Log.d(TAG, "Init Context 注入成功: ${if (isStatic) "static" else "singleton"}.${field.name}")
        }.onFailure { Log.w(TAG, "Init Context 注入失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 取（或创建）爬虫实例：先用自己的 jar，失败时跨 jar 回退 */
    private fun spider(jarSpec: String, api: String, ext: String, siteKey: String): Spider? {
        ensureBuiltInLoaders()
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
        // 带 native 反调试壳的爬虫（Sir88Guard 等）构造时会调 DexNative.getSpider()，
        // 在 x86/houdini 上 JNI CheckJNI 检测到 null ClassLoader 直接 SIGABRT，
        // Java 层 runCatching 根本拦不住，必须在实例化前跳过。实测 11-10 全网搜索
        // 跑到 csp_Sir88Guard 时整个进程被杀回首页。
        val superName = runCatching { clz.superclass?.name }.getOrNull().orEmpty()
        if (name.endsWith("Guard", true) || superName.contains("SpiderGuard", true)) {
            Log.w(TAG, "跳过 native Guard 爬虫: $name (super=$superName)")
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
                // 只补 Init 的全局 Application 字段，不调 Init.init()：后者会去下载并
                // 启动 Go 代理（2.68MB 二进制）、悬浮球和多个后台线程。jar 内部写 KV
                // （detailContent→saveName）依赖 Init.context()，缺了它详情必挂。
                injectContext(loader)
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
            // 影视仓行为：订阅声明的 ;md5; 常与 CDN 实际内容不符（jar?id=index 会 302
            // 到另一份 jar，内容 md5 39556ad1… ≠ 声明 b8ccf689…），它照样加载。
            // 旧逻辑一不符就 delete，导致 csp_Jpys / 整条摸鱼线路直接失效。
            if (expectMd5 != null && !fileMd5(file).equals(expectMd5, true)) {
                Log.w(TAG, "jar md5 与订阅声明不符（按影视仓行为继续使用）: $url")
            }
            return file
        }
        // 内置源包优先：影视仓实机缓存的 10 个 dex jar 已随 APK 打包到
        // assets/tvbox/jar/<md5(url)>.jar。文件名就是订阅 jar URL 的 md5，
        // 内容 md5 已逐个核对与订阅 ;md5; 声明一致（见 assets/tvbox/manifest.json）。
        // 命中即离线落盘，跳过网络下载：订阅 CDN 偶发返回错误页/被换内容，
        // 旧逻辑会因 md5 校验失败直接丢弃（csp_Jpys 此前就死在这一步）。
        val builtIn = runCatching {
            appCtx.assets.open("tvbox/jar/${md5(url)}.jar").use { it.readBytes() }
        }.getOrNull()
        if (builtIn != null && builtIn.isNotEmpty()) {
            file.writeBytes(builtIn)
            Log.d(TAG, "内置 jar 命中: ${file.name} (${file.length()}B)")
            return file
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
                // 只告警不删：订阅里的 md5 常滞后于 CDN 实际内容，删掉等于自断一条线路
                Log.w(TAG, "jar md5 与订阅声明不符（继续使用）: $url")
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
