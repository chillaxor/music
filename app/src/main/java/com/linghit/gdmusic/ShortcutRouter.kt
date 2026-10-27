package com.linghit.gdmusic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 快捷位跳转路由：首页自定义资源位与常驻悬浮按钮共用。
 *
 * 从 MainActivity.openShortcut 原样抽出，唯一差别是 ctx 可能是 Service
 * （悬浮按钮点击）——非 Activity 上下文启动页面必须带 FLAG_ACTIVITY_NEW_TASK。
 */
object ShortcutRouter {

    fun open(ctx: Context, item: HomeShortcutStore.Item) {
        val needNewTask = ctx !is Activity
        fun go(i: Intent) {
            if (needNewTask) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
                .onFailure { Toast.makeText(ctx, "打不开这个入口", Toast.LENGTH_SHORT).show() }
        }
        when (item.type) {
            "link" -> {
                if (item.url.isBlank()) {
                    Toast.makeText(ctx, "这个链接没有地址", Toast.LENGTH_SHORT).show()
                    return
                }
                val u = LinkRouter.normalize(item.url)
                // 内部伪协议（app://gallery / app://camera / app://<包名>）→ 本 App 接管，
                // 不再当深链去系统找接收方（否则报「无安卓对应软件」）
                if (InternalScheme.isInternal(u) && InternalScheme.open(ctx, u)) return
                // 深链 / 小红书分享链接 → 先尝试唤起指定的 App
                val appLink = LinkRouter.resolveAppLink(u)
                if (appLink != null) {
                    val ok = LinkRouter.openApp(ctx, appLink)
                    if (!ok && (u.startsWith("http://") || u.startsWith("https://"))) {
                        // 没装目标 App，但原始链接本身是网页（如小红书分享链接）→ 退回内嵌浏览器
                        go(Intent(ctx, NetAssetActivity::class.java).putExtra("open_url", u))
                    } else if (!ok) {
                        Toast.makeText(ctx, "未安装对应的应用，无法跳转", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                // 网页网址：在 App 内嵌浏览器打开（不跳出 App，可返回）
                go(Intent(ctx, NetAssetActivity::class.java).putExtra("open_url", u))
            }
            else -> when (item.target) {
                "video" -> go(Intent(ctx, VideoActivity::class.java))
                // 首页：CLEAR_TOP 复用已有实例，不叠新页面（从 Service/悬浮球启动时尤其重要）
                "home" -> go(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                "netasset" -> go(Intent(ctx, NetAssetActivity::class.java))
                "rail" -> go(Intent(ctx, RailActivity::class.java))
                "reminder" -> go(Intent(ctx, ReminderActivity::class.java))
                "downloads" -> go(Intent(ctx, DownloadedActivity::class.java))
                "favorites" -> go(Intent(ctx, FavoritesActivity::class.java))
                "podcast" -> go(Intent(ctx, PodcastActivity::class.java))
                "ai" -> go(Intent(ctx, AiTestActivity::class.java))
                "settings" -> go(Intent(ctx, SettingsActivity::class.java))
                // 记账页子视图：add 记一笔 / flow 收支查询 / journal 日志 / links 链接收藏
                "add", "flow", "journal", "links", "calendar" ->
                    go(Intent(ctx, NetAssetActivity::class.java).putExtra("open_view", item.target))
                else -> Toast.makeText(ctx, "未知入口", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
