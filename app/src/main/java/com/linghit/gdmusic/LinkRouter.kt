package com.linghit.gdmusic

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 链接路由：区分「网页网址」与「跳转指定 App 的深链」。
 *
 * 产品规则：
 *  - 链接页 / 首页自定义链接：用户配的是 App 深链（weixin://、alipays://、xhsdiscover:// 等）
 *    或小红书分享链接时，直接唤起链接指定的目标 App；
 *  - 网页里打开的网址（内嵌浏览器中页面自身的跳转）：一律不跳外部 App。
 */
object LinkRouter {

    /**
     * 归一化用户输入的链接：
     *  - 去首尾空白；
     *  - 全角转半角（中文输入法容易敲出全角冒号/斜杠/空格，
     *    不转换的话 scheme 识别不出来，会被当成网页丢进浏览器报「网页无法打开」）。
     */
    fun normalize(url: String?): String {
        val u = (url ?: "").trim()
        if (u.isEmpty()) return ""
        val sb = StringBuilder(u.length)
        for (ch in u) {
            when (ch) {
                '：' -> sb.append(':')
                '／' -> sb.append('/')
                '　' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 把用户配的链接解析成「要唤起的 App 深链」。
     * @return 深链 URL；null = 普通网页网址，该走内嵌浏览器
     *
     * 覆盖：
     *  - 小红书分享链接（从小红书 App「分享→复制链接」拿到的 https 地址）→ 转成 xhsdiscover:// 深链，
     *    这样直接粘贴分享链接就能跳 App，不用手工拆 ID；
     *  - 本身就是自定义 scheme 的深链（weixin://、alipays://、xhsdiscover://、intent:// 等）。
     */
    fun resolveAppLink(url: String?): String? {
        val u = normalize(url)
        if (u.isEmpty()) return null
        toAppLink(u)?.let { return it }
        return if (isAppLink(u)) u else null
    }

    /** 小红书分享链接 → App 深链；不是小红书链接返回 null */
    private fun toAppLink(u: String): String? {
        if (!u.contains("xiaohongshu.com", true) && !u.contains("xhslink.com", true)) return null
        // 笔记：/explore/{id} 或 /discovery/item/{id}（分享链接最常见的两种形态）
        Regex("xiaohongshu\\.com/(?:explore|discovery/item)/([A-Za-z0-9]+)")
            .find(u)?.let { return "xhsdiscover://item/${it.groupValues[1]}" }
        // 用户主页：/user/profile/{id}
        Regex("xiaohongshu\\.com/user/profile/([A-Za-z0-9]+)")
            .find(u)?.let { return "xhsdiscover://user/${it.groupValues[1]}" }
        return null
    }

    /**
     * 是否是「跳转指定 App」的深链。
     * 判定：有自定义 scheme 且不是 http/https/file/about（比如 xhsdiscover://、weixin://、alipays://）。
     */
    fun isAppLink(url: String?): Boolean {
        val u = normalize(url)
        if (u.isEmpty()) return false
        if (u.startsWith("http://", true) || u.startsWith("https://", true) ||
            u.startsWith("file://", true) || u.startsWith("about:", true)
        ) return false
        return Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:").containsMatchIn(u)
    }

    /**
     * 尝试唤起深链指定的 App。
     * 深链路径不被目标 App 识别时（App 版本旧/路径失效），自动退回落整个 scheme 本身
     * （比如 xhsdiscover://home/explore 打不开时试 xhsdiscover://），至少把 App 拉起来。
     * @return true = 已成功唤起；false = 没装目标 App / 彻底失败（调用方自行提示）
     */
    fun openApp(context: Context, url: String): Boolean {
        if (tryStart(context, url)) return true
        val scheme = url.substringBefore("://") + "://"
        if (scheme != url && scheme != "://") return tryStart(context, scheme)
        return false
    }

    private fun tryStart(context: Context, url: String): Boolean {
        return try {
            val intent = if (url.startsWith("intent://", true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
