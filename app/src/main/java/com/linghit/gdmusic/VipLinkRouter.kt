package com.linghit.gdmusic

import android.net.Uri

/**
 * 官网播放页识别（阶段 1）。
 *
 * 规则来源：88lin/video_vip 用户脚本的 @include 声明。脚本订阅成功前用内置的
 * v3.2.11 快照（35 条）兜底；订阅成功后由 VipScriptUpdater 把上游最新规则整包灌进来，
 * 所以脚本改版时官网识别会跟着更新。只搬「域名 + 路径」这类事实数据，
 * 不复制脚本代码、不内置解析接口地址，也不依赖 GM_* / jQuery。
 *
 * 与 LinkRouter 各司其职：
 *  - LinkRouter 判「是不是 App 深链」（scheme 维度）；
 *  - VipLinkRouter 判「是不是主流视频官网的播放页」（host + path 维度）。
 * VipLinkDispatcher 在两者之间做分流。
 */
object VipLinkRouter {

    /** 脚本首次同步成功前的兜底：原样保留 v3.2.11 的 @include 写法 */
    private val BUILTIN = listOf(
        "*://v.qq.com/x/page/*",
        "*://v.qq.com/x/cover/*",
        "*://v.qq.com/tv/*",
        "*://*.iqiyi.com/v_*",
        "*://*.iqiyi.com/a_*",
        "*://*.iqiyi.com/w_*",
        "*://*.iq.com/play/*",
        "*://*.youku.com/v_*",
        "*://*.youku.com/video*",
        "*://*.youku.com/*?vid=*",
        "*://*.mgtv.com/b/*",
        "*://*.tudou.com/v_*",
        "*://tv.sohu.com/v/*",
        "*://*.bilibili.com/video/*",
        "*://*.bilibili.com/bangumi/play/*",
        "*://v.pptv.com/show/*",
        "*://vip.pptv.com/show/*",
        "*://www.wasu.cn/Play/show/*",
        "*://*.le.com/ptv/vplay/*",
        "*://*.acfun.cn/v/*",
        "*://*.acfun.cn/bangumi/*",
        "*://*.1905.com/play/*",
        "*://m.v.qq.com/x/m/*",
        "*://m.v.qq.com/*",
        "*://m.iqiyi.com/*",
        "*://m.iqiyi.com/v_*",
        "*://m.youku.com/video/*",
        "*://m.youku.com/alipay_*",
        "*://m.mgtv.com/b/*",
        "*://m.tv.sohu.com/v/*",
        "*://m.tv.sohu.com/album/*",
        "*://m.pptv.com/show/*",
        "*://m.bilibili.com/anime/*",
        "*://m.bilibili.com/video/*",
        "*://m.bilibili.com/bangumi/play/*"
    )

    private class Rule(val host: String, val path: String, val platform: String) {
        private val pathRx = globRegex(path)
        fun matches(h: String, p: String) = matchHost(h, host) && pathRx.matches(p)
    }

    /**
     * 当前生效的规则。脚本订阅成功后由 VipScriptUpdater.applySubscribed() 整包替换，
     * 进程内缓存，platformOf() 不需要 Context 就能被调用（调用点有 3 处，避免签名扩散）。
     */
    @Volatile
    private var active: List<Rule> = BUILTIN.map(::parseRule)

    /** 当前生效规则里来自脚本订阅的条数，0 表示在用内置兜底 */
    @Volatile
    var subscribedRuleCount: Int = 0
        private set

    fun applySubscribed(list: List<String>) {
        val valid = list
            .filter { it.startsWith("*://") || it.startsWith("http://") || it.startsWith("https://") }
            .filter { s -> s.none { it.isWhitespace() || it.isISOControl() } && s.length <= 200 }
            .distinct()
            .take(200)
        val useScript = valid.size >= 5
        active = (if (useScript) valid else BUILTIN).map(::parseRule)
        subscribedRuleCount = if (useScript) valid.size else 0
        android.util.Log.i("VipParse", "rules active=${active.size} fromScript=$subscribedRuleCount")
    }

    val ruleCount: Int get() = active.size

    /** 覆盖的平台名，供设置页展示 */
    val platforms: List<String> get() = active.map { it.platform }.distinct()

    /** 命中官网播放页返回 true */
    fun isVipPage(url: String?): Boolean = platformOf(url) != null

    /**
     * 命中返回平台名（腾讯视频 / 爱奇艺 / …），未命中返回 null。
     * 阶段 3 可以用平台名去匹配订阅 parses 的 ext.flag。
     */
    fun platformOf(url: String?): String? {
        val raw = (url ?: "").trim()
        if (raw.isEmpty()) return null
        val uri = try { Uri.parse(LinkRouter.normalize(raw)) } catch (e: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = (uri.host ?: return null).lowercase().trimEnd('.')
        val path = uri.path ?: ""
        val query = uri.query
        // 脚本的「*?vid=*」规则里 ? 是 URL query 分隔符，把 query 拼回路径一起匹配
        val fullPath = if (query.isNullOrEmpty()) path else "$path?$query"
        for (r in active) if (r.matches(host, fullPath)) return r.platform
        return null
    }

    private fun parseRule(raw: String): Rule {
        val body = raw.removePrefix("*://").removePrefix("https://").removePrefix("http://")
        val slash = body.indexOf('/')
        val host = if (slash < 0) body else body.substring(0, slash)
        val path = if (slash < 0) "/*" else body.substring(slash)
        return Rule(host, path, platformOfHost(host))
    }

    /** *. 前缀 = 主域或任意子域；否则精确匹配 host */
    private fun matchHost(host: String, pattern: String): Boolean {
        val p = pattern.lowercase()
        return if (p.startsWith("*.")) {
            val base = p.substring(2)
            host == base || host.endsWith(".$base")
        } else {
            host == p
        }
    }

    /** @include 的 glob：* 是通配，? 是字面的 query 分隔符，其余全部转义 */
    private fun globRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append("\\?")
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private fun platformOfHost(h: String): String = when {
        h.contains("qq.com") -> "腾讯视频"
        h.contains("iqiyi.com") || h.contains("iq.com") -> "爱奇艺"
        h.contains("youku.com") -> "优酷"
        h.contains("tudou.com") -> "土豆"
        h.contains("mgtv.com") -> "芒果TV"
        h.contains("bilibili.com") -> "哔哩哔哩"
        h.contains("sohu.com") -> "搜狐视频"
        h.contains("pptv.com") -> "PPTV"
        h.contains("wasu.cn") -> "华数TV"
        h.contains("le.com") -> "乐视"
        h.contains("acfun.cn") -> "AcFun"
        h.contains("1905.com") -> "1905电影网"
        else -> "其他"
    }
}
