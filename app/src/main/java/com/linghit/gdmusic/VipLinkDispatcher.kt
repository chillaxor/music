package com.linghit.gdmusic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * VIP 官网链接的统一分流器（阶段 2）。
 *
 * 两个入口共用这一份判定，避免规则散落：
 *  - ShortcutRouter.open(): 首页自定义链接 / 悬浮球快捷位
 *  - NetAssetActivity.openLinkOrApp(): 链接页点击、以及 onNewIntent 复用实例
 *
 * 命中条件：总开关已开 + VipLinkRouter 判定是官网播放页 + 本次不是「失败回退」。
 * 命中后直接起 VideoActivity（exported=false，仅 App 内部），带 open_url；
 * VideoActivity 页面就绪后交给 JS 的 __resolveVipAt 走解析链，最终进 ExoPlayer。
 *
 * ⚠️ 回退到内嵌浏览器时必须带 vip_bypass，否则会再次命中分流造成死循环。
 */
object VipLinkDispatcher {

    const val EXTRA_OPEN_URL = "open_url"
    const val EXTRA_BYPASS = "vip_bypass"
    const val EXTRA_PLATFORM = "vip_platform"
    /** 独立解析态：VideoActivity 只跑 VIP 解析，不加载影视仓订阅与首页列表 */
    const val EXTRA_VIP_ONLY = "vip_only"

    fun shouldHandle(ctx: Context, url: String, bypass: Boolean = false): Boolean {
        if (bypass) return false
        if (!VipParseStore.isEnabled(ctx)) return false
        return VipLinkRouter.isVipPage(url)
    }

    /** 命中官网播放页：转原生播放器解析链 */
    fun open(ctx: Context, url: String) {
        val platform = VipLinkRouter.platformOf(url) ?: "官网"
        Log.i("VipParse", "route url=$url platform=$platform sources=${VipParseStore.orderedSources(ctx).size}")
        val i = Intent(ctx, VideoActivity::class.java)
            .putExtra(EXTRA_OPEN_URL, url)
            .putExtra(EXTRA_PLATFORM, platform)
            .putExtra(EXTRA_VIP_ONLY, true)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
            .onFailure { Log.w("VipParse", "start VideoActivity failed: ${it.message}") }
    }

    /** 解析全失败：回内嵌浏览器打开原网页，bypass 防止再次分流 */
    fun fallback(ctx: Context, url: String) {
        Log.i("VipParse", "fallback url=$url")
        val i = Intent(ctx, NetAssetActivity::class.java)
            .putExtra(EXTRA_OPEN_URL, url)
            .putExtra(EXTRA_BYPASS, true)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
            .onFailure { Log.w("VipParse", "start NetAssetActivity failed: ${it.message}") }
    }
}
