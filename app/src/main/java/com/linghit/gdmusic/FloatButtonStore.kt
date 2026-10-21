package com.linghit.gdmusic

import android.content.Context
import org.json.JSONObject

/**
 * 常驻悬浮按钮：配置持久化。
 *
 * 三样东西：
 *  - enabled：总开关（默认 true；真正显示还要系统悬浮窗权限，服务里兜底判断）
 *  - action：点击执行的动作，复用首页快捷位 [HomeShortcutStore.Item] 的结构
 *    （type=page 时 target ∈ add/flow/journal/links/video/netasset/downloads/favorites/settings；
 *    type=link 时 url=链接地址，走 [ShortcutRouter] 与链接页同一套逻辑）
 *  - x / y：悬浮球左上角坐标（Gravity.TOP|START），拖动松手时写回
 */
object FloatButtonStore {
    private const val FILE = "float_button"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACTION = "action"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /** 默认动作：记一笔（和首页「＋」面板第一项一致） */
    val DEFAULT_ACTION = HomeShortcutStore.Item("page", "add", "记一笔", "✏️")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
    }

    fun action(ctx: Context): HomeShortcutStore.Item {
        val raw = prefs(ctx).getString(KEY_ACTION, null) ?: return DEFAULT_ACTION
        return runCatching {
            val o = JSONObject(raw)
            HomeShortcutStore.Item(
                type = o.optString("type"),
                target = o.optString("target"),
                label = o.optString("label"),
                emoji = o.optString("emoji"),
                url = o.optString("url")
            )
        }.getOrElse { DEFAULT_ACTION }
    }

    fun setAction(ctx: Context, it: HomeShortcutStore.Item) {
        prefs(ctx).edit().putString(KEY_ACTION, it.toJson().toString()).apply()
    }

    /** 已拖动过的位置；没有时返回 null，由服务按默认位置（右缘、35% 高度）摆放 */
    fun position(ctx: Context): Pair<Int, Int>? {
        val p = prefs(ctx)
        if (!p.contains(KEY_X) || !p.contains(KEY_Y)) return null
        return Pair(p.getInt(KEY_X, 0), p.getInt(KEY_Y, 0))
    }

    fun setPosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
