package com.linghit.gdmusic

import android.content.Context
import org.json.JSONObject

/**
 * 常驻悬浮按钮：配置持久化。
 *
 * 四样东西：
 *  - enabled：总开关（默认 true；真正显示还要系统悬浮窗权限，服务里兜底判断）
 *  - action：点击执行的动作，复用首页快捷位 [HomeShortcutStore.Item] 的结构
 *    （type=page 时 target ∈ add/flow/journal/links/video/home/netasset/downloads/favorites/settings；
 *    type=link 时 url=链接地址，走 [ShortcutRouter] 与链接页同一套逻辑）
 *  - bubbles：长按悬浮球放射展开的气泡列表，结构同 [action]（默认影视仓 + 首页，上限 [MAX_BUBBLES]）
 *  - x / y：悬浮球左上角坐标（Gravity.TOP|START），拖动松手时写回
 *  - hiddenSide：贴边半隐藏状态（0=正常 / 1=藏左缘 / 2=藏右缘，见 FloatButtonService）
 */
object FloatButtonStore {
    private const val FILE = "float_button"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACTION = "action"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    /** 自定义图标落盘路径（相册图经 256px 圆形裁剪，见 FloatIcon）；空 = 默认 emoji 图标 */
    private const val KEY_ICON = "icon_path"
    /** 长按气泡列表（JSON 数组，元素结构同 action） */
    private const val KEY_BUBBLES = "bubbles"
    /** 贴边半隐藏：0=正常，1=藏左缘，2=藏右缘 */
    private const val KEY_HIDDEN_SIDE = "hidden_side"

    /** 气泡数量上限：放射展开，超过 5 个会挤到屏幕外 */
    const val MAX_BUBBLES = 5

    /** 默认动作：记一笔（和首页「＋」面板第一项一致） */
    val DEFAULT_ACTION = HomeShortcutStore.Item("page", "add", "记一笔", "✏️")

    /** 默认气泡：影视仓 + 首页 + 语音助手 */
    val DEFAULT_BUBBLES = listOf(
        HomeShortcutStore.Item("page", "video", "影视仓", "🎬"),
        HomeShortcutStore.Item("page", "home", "首页", "🏠"),
        HomeShortcutStore.Item("page", "voice", "语音助手", "🎤")
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
    }

    private fun itemFrom(o: JSONObject) = HomeShortcutStore.Item(
        type = o.optString("type"),
        target = o.optString("target"),
        label = o.optString("label"),
        emoji = o.optString("emoji"),
        url = o.optString("url")
    )

    fun action(ctx: Context): HomeShortcutStore.Item {
        val raw = prefs(ctx).getString(KEY_ACTION, null) ?: return DEFAULT_ACTION
        return runCatching { itemFrom(JSONObject(raw)) }.getOrElse { DEFAULT_ACTION }
    }

    fun setAction(ctx: Context, it: HomeShortcutStore.Item) {
        prefs(ctx).edit().putString(KEY_ACTION, it.toJson().toString()).apply()
    }

    /** 长按气泡列表；没有配置过或数据坏了回退默认（影视仓 + 首页） */
    fun bubbles(ctx: Context): MutableList<HomeShortcutStore.Item> {
        val raw = prefs(ctx).getString(KEY_BUBBLES, null) ?: return DEFAULT_BUBBLES.toMutableList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val out = mutableListOf<HomeShortcutStore.Item>()
            for (i in 0 until arr.length()) out.add(itemFrom(arr.getJSONObject(i)))
            out
        }.getOrElse { DEFAULT_BUBBLES.toMutableList() }
    }

    /** 写回气泡列表（超过上限直接截断） */
    fun setBubbles(ctx: Context, l: List<HomeShortcutStore.Item>) {
        val arr = org.json.JSONArray()
        l.take(MAX_BUBBLES).forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_BUBBLES, arr.toString()).apply()
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

    /** 贴边半隐藏边：0=正常 / 1=左 / 2=右（FloatButtonService.HIDDEN_*） */
    fun hiddenSide(ctx: Context): Int = prefs(ctx).getInt(KEY_HIDDEN_SIDE, 0)

    fun setHiddenSide(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_HIDDEN_SIDE, v).apply()
    }

    /** 自定义图标路径；空串 = 用动作自带的 emoji 图标 */
    fun iconPath(ctx: Context): String = prefs(ctx).getString(KEY_ICON, "") ?: ""

    fun setIconPath(ctx: Context, path: String) {
        prefs(ctx).edit().putString(KEY_ICON, path).apply()
    }
}
