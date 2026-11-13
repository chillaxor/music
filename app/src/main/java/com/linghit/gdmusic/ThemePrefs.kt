package com.linghit.gdmusic

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * 风格包（StylePack）：一次切换决定整站视觉语言，而不只是主色。
 *
 * 与旧版的区别（2026-11-13 改造）：
 *  - 旧：StylePack 只有 name + 3 个色值，12 套只覆盖 colorPrimary，首页材质/字色/蒙层
 *    全部硬编码在 XML，所以「换主题」永远只换按钮和进度条的颜色。
 *  - 新：5 套风格各自绑定固定配色（用户定案「风格绑定固定配色」），一份契约里同时带上
 *    卡片材质、圆角、描边、字色、pill、播放条、兜底底色、主页面中性色、
 *    以及对每日一图的加工参数（去饱和 / 罩染 / 蒙层 / 亮度自适应）。
 *  - 消费通道两条：① res/values/attrs.xml 的 ?attr/gdXxx，20+ 页面自动跟随；
 *    ② StyleEngine 代码生成 GradientDrawable / LayerDrawable，处理 XML 表达不了的
 *    多层玻璃、渐变兜底、播放条与壁纸加工（layer-list 的 gradient 无法用 ?attr 上色）。
 *
 * 关键约束（用户定案）：
 *  1) 兼容背景图：四套…五套全部保留壁纸，风格只决定「怎么加工」，不决定「有没有」。
 *  2) 背景图开关：设置页 wallpaper_enabled，默认开；关掉只隐藏显示层，
 *     不碰 DailyImageStore 的文件夹 / 当天索引 / 手动换图序号，再打开立刻回到原图。
 *  3) 系统深色让位：固定 MODE_NIGHT_NO，明暗由风格自己决定，values-night 不再抢戏。
 *  4) 覆盖范围：首页 + 走 applyTheme 的原生主页面（不含记账 WebView / 悬浮球 / 桌面歌词）。
 *
 * 兼容性：保留 PACKS / currentIndex / currentPack / packAt / set / applyTheme 这些旧 API 名，
 * 让 QueueActivity / NewsActivity / RailActivity / FloatButtonService / SplashActivity 等
 * 现有调用点（读 primary）无需改动即可跟随风格。旧持久化键 theme_idx 退休，默认落「玻璃」。
 */
object ThemePrefs {

    enum class Material { GLASS, PAPER, INK, LINE, CLAY }

    /** 壁纸加工参数。scrimK > 0 = 深色系（亮图加压暗）；< 0 = 浅色系（暗图加提亮） */
    data class WallSpec(
        val saturation: Float,
        val tint: Int,
        val scrimBase: Float,
        val scrimK: Float,
        val scrimMin: Float,
        val scrimMax: Float,
        val imageAlpha: Int
    )

    data class StylePack(
        val name: String,
        val themeRes: Int,
        val primary: Int,
        val primaryDark: Int,
        val primaryEnd: Int? = null,
        val desc: String = "",
        val material: Material = Material.GLASS,
        val cardFill: Int = 0xFF46161B21.toInt(),
        val cardEdge: Int = 0xFFA8FFFFFF.toInt(),
        val cardHighlight: Int = 0xFF2BFFFFFF.toInt(),
        val cardRadiusDp: Float = 18f,
        val cardStrokeDp: Float = 0f,
        val cardStrokeColor: Int = 0x00000000,
        val playerBarFill: Int = 0xFF1F2B3641.toInt(),
        val playerBarRadiusDp: Float = 16f,
        val textPrimary: Int = 0xFFFFFFFF.toInt(),
        val textSecondary: Int = 0xFFB8FFFFFF.toInt(),
        val textHint: Int = 0xFF99FFFFFF.toInt(),
        val iconTint: Int = 0xFFFFFFFF.toInt(),
        val pillIdle: Int = 0xFF1AFFFFFF.toInt(),
        val pillActive: Int = 0xFFEBFFFFFF.toInt(),
        val pillActiveText: Int = 0xFF0E9C8F.toInt(),
        val pillIdleText: Int = 0xFFFFFFFF.toInt(),
        val accentBtn: Int = 0xFF46525E.toInt(),
        val fallbackA: Int = 0xFF161B21.toInt(),
        val fallbackB: Int = 0xFF232A31.toInt(),
        val surface: Int = 0xFFFFFFFF.toInt(),
        val background: Int = 0xFFFAF6F2.toInt(),
        val onSurface: Int = 0xFF241A14.toInt(),
        val onSurfaceVariant: Int = 0xFF7A6A60.toInt(),
        val lightStatusBar: Boolean = true,
        val fontFamily: String = "sans-serif",
        val wall: WallSpec = WallSpec(1f, 0xFF000000.toInt(), 0.08f, 0.14f, 0.06f, 0.30f, 255)
    )

    /** 5 套风格，顺序即切换器顺序。第 0 套「玻璃」保持线上现状，作为视觉回归基线。 */
    val PACKS = listOf(
        StylePack(
            name = "玻璃", themeRes = R.style.AppTheme_StyleGlass,
            primary = 0xFF0E9C8F.toInt(), primaryDark = 0xFF00796B.toInt(),
            desc = "半透明玻璃 · 白色折射亮边 · 壁纸通透",
            material = Material.GLASS,
            cardFill = 0xFF46161B21.toInt(), cardEdge = 0xFFA8FFFFFF.toInt(),
            cardHighlight = 0xFF2BFFFFFF.toInt(), cardRadiusDp = 18f,
            playerBarFill = 0xFF1F2B3641.toInt(), playerBarRadiusDp = 16f,
            textPrimary = 0xFFFFFFFF.toInt(), textSecondary = 0xFFB8FFFFFF.toInt(),
            textHint = 0xFF99FFFFFF.toInt(), iconTint = 0xFFFFFFFF.toInt(),
            pillIdle = 0xFF1AFFFFFF.toInt(), pillActive = 0xFFEBFFFFFF.toInt(),
            pillActiveText = 0xFF0E9C8F.toInt(), pillIdleText = 0xFFFFFFFF.toInt(),
            accentBtn = 0xFF46525E.toInt(),
            fallbackA = 0xFF161B21.toInt(), fallbackB = 0xFF232A31.toInt(),
            surface = 0xFFFFFFFF.toInt(), background = 0xFFFAF6F2.toInt(),
            onSurface = 0xFF241A14.toInt(), onSurfaceVariant = 0xFF7A6A60.toInt(),
            lightStatusBar = true, fontFamily = "sans-serif",
            wall = WallSpec(1.0f, 0xFF000000.toInt(), 0.08f, 0.14f, 0.06f, 0.30f, 255)
        ),
        StylePack(
            name = "素纸", themeRes = R.style.AppTheme_StylePaper,
            primary = 0xFFB4462F.toInt(), primaryDark = 0xFF8C3321.toInt(),
            desc = "实底纸白卡 · 细描边方角 · 衬线标题 · 墨色字",
            material = Material.PAPER,
            cardFill = 0xFFFFFDF8.toInt(), cardEdge = 0xFFE4DAC6.toInt(),
            cardHighlight = 0x00000000, cardRadiusDp = 8f,
            cardStrokeDp = 1f, cardStrokeColor = 0xFFE4DAC6.toInt(),
            playerBarFill = 0xFFFFFDF8.toInt(), playerBarRadiusDp = 8f,
            textPrimary = 0xFF2A2622.toInt(), textSecondary = 0xFF7A6F60.toInt(),
            textHint = 0xFFA99E8C.toInt(), iconTint = 0xFF2A2622.toInt(),
            pillIdle = 0xFFF0E9DA.toInt(), pillActive = 0xFFB4462F.toInt(),
            pillActiveText = 0xFFFFFFFF.toInt(), pillIdleText = 0xFF2A2622.toInt(),
            accentBtn = 0xFFB4462F.toInt(),
            fallbackA = 0xFFF6F1E7.toInt(), fallbackB = 0xFFEDE5D5.toInt(),
            surface = 0xFFFFFDF8.toInt(), background = 0xFFF6F1E7.toInt(),
            onSurface = 0xFF2A2622.toInt(), onSurfaceVariant = 0xFF7A6F60.toInt(),
            lightStatusBar = true, fontFamily = "serif",
            wall = WallSpec(0.65f, 0xFFF6F1E7.toInt(), 0.32f, -0.14f, 0.18f, 0.55f, 235)
        ),
        StylePack(
            name = "墨夜", themeRes = R.style.AppTheme_StyleInk,
            primary = 0xFF4FD1C5.toInt(), primaryDark = 0xFF2E9B8F.toInt(),
            desc = "实底墨黑卡 · 细描边 · 高对比浅字 · OLED 友好",
            material = Material.INK,
            cardFill = 0xFF171B20.toInt(), cardEdge = 0xFF2A3038.toInt(),
            cardHighlight = 0x00000000, cardRadiusDp = 12f,
            cardStrokeDp = 1f, cardStrokeColor = 0xFF2A3038.toInt(),
            playerBarFill = 0xFF171B20.toInt(), playerBarRadiusDp = 12f,
            textPrimary = 0xFFF2F4F6.toInt(), textSecondary = 0xFFA8B2BC.toInt(),
            textHint = 0xFF78838E.toInt(), iconTint = 0xFFF2F4F6.toInt(),
            pillIdle = 0xFF232A31.toInt(), pillActive = 0xFF4FD1C5.toInt(),
            pillActiveText = 0xFF0E1114.toInt(), pillIdleText = 0xFFF2F4F6.toInt(),
            accentBtn = 0xFF4FD1C5.toInt(),
            fallbackA = 0xFF0E1114.toInt(), fallbackB = 0xFF1B2128.toInt(),
            surface = 0xFF171B20.toInt(), background = 0xFF0E1114.toInt(),
            onSurface = 0xFFF2F4F6.toInt(), onSurfaceVariant = 0xFFA8B2BC.toInt(),
            lightStatusBar = false, fontFamily = "sans-serif",
            wall = WallSpec(0.60f, 0xFF000000.toInt(), 0.45f, 0.10f, 0.28f, 0.70f, 200)
        ),
        StylePack(
            name = "线稿", themeRes = R.style.AppTheme_StyleLine,
            primary = 0xFF1F2933.toInt(), primaryDark = 0xFF0F161D.toInt(),
            desc = "卡片无填充 · 只留细线分区 · 直角 · 留白更大",
            material = Material.LINE,
            cardFill = 0x00000000, cardEdge = 0x66FFFFFF.toInt(),
            cardHighlight = 0x00000000, cardRadiusDp = 0f,
            cardStrokeDp = 1f, cardStrokeColor = 0x66FFFFFF.toInt(),
            playerBarFill = 0x00000000, playerBarRadiusDp = 0f,
            textPrimary = 0xFF1F2933.toInt(), textSecondary = 0xFF5A6672.toInt(),
            textHint = 0xFF8A95A0.toInt(), iconTint = 0xFF1F2933.toInt(),
            pillIdle = 0x00000000, pillActive = 0xFF1F2933.toInt(),
            pillActiveText = 0xFFFFFFFF.toInt(), pillIdleText = 0xFF1F2933.toInt(),
            accentBtn = 0xFF1F2933.toInt(),
            fallbackA = 0xFFF7F8FA.toInt(), fallbackB = 0xFFECEFF3.toInt(),
            surface = 0xFFFFFFFF.toInt(), background = 0xFFF7F8FA.toInt(),
            onSurface = 0xFF1F2933.toInt(), onSurfaceVariant = 0xFF5A6672.toInt(),
            lightStatusBar = true, fontFamily = "sans-serif",
            wall = WallSpec(0.10f, 0xFFFFFFFF.toInt(), 0.28f, -0.16f, 0.14f, 0.58f, 230)
        ),
        StylePack(
            name = "软陶", themeRes = R.style.AppTheme_StyleClay,
            primary = 0xFF6C8AA5.toInt(), primaryDark = 0xFF4E6A83.toInt(),
            desc = "拟物软浮雕 · 双向明暗边 · 大圆角 · 雾霾蓝",
            material = Material.CLAY,
            cardFill = 0xFFE8EBF0.toInt(), cardEdge = 0xFFFFFFFF.toInt(),
            cardHighlight = 0xFFB9C3D0.toInt(), cardRadiusDp = 20f,
            playerBarFill = 0xFFE8EBF0.toInt(), playerBarRadiusDp = 20f,
            textPrimary = 0xFF37414C.toInt(), textSecondary = 0xFF71808F.toInt(),
            textHint = 0xFF9AA7B4.toInt(), iconTint = 0xFF37414C.toInt(),
            pillIdle = 0xFFE8EBF0.toInt(), pillActive = 0xFF6C8AA5.toInt(),
            pillActiveText = 0xFFFFFFFF.toInt(), pillIdleText = 0xFF37414C.toInt(),
            accentBtn = 0xFF6C8AA5.toInt(),
            fallbackA = 0xFFE8EBF0.toInt(), fallbackB = 0xFFDCE2EA.toInt(),
            surface = 0xFFE8EBF0.toInt(), background = 0xFFE8EBF0.toInt(),
            onSurface = 0xFF37414C.toInt(), onSurfaceVariant = 0xFF71808F.toInt(),
            lightStatusBar = true, fontFamily = "sans-serif",
            wall = WallSpec(0.75f, 0xFFE8EBF0.toInt(), 0.38f, -0.12f, 0.22f, 0.60f, 220)
        )
    )

    private const val DEFAULT_INDEX = 0
    private const val KEY_STYLE = "style_id"
    private const val KEY_WALL = "wallpaper_enabled"
    private const val PREF = "gdmusic_theme"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun indexOf(ctx: Context): Int {
        val i = sp(ctx).getInt(KEY_STYLE, DEFAULT_INDEX)
        return if (i in PACKS.indices) i else DEFAULT_INDEX
    }

    fun currentIndex(ctx: Context): Int = indexOf(ctx)
    fun currentPack(ctx: Context): StylePack = PACKS[indexOf(ctx)]
    fun packAt(i: Int): StylePack = PACKS.getOrElse(i) { PACKS[DEFAULT_INDEX] }

    /** 背景图软开关：默认开（旧用户没有这个 key 也视为开）。关掉不清除任何图片配置。 */
    fun wallpaperEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_WALL, true)
    fun setWallpaperEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_WALL, on).apply()
    }

    /**
     * 必须在 Activity.setContentView 之前调用。
     * 固定 MODE_NIGHT_NO = 系统深色让位给风格包，明暗由风格自己决定。
     */
    fun applyTheme(a: AppCompatActivity) {
        if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        a.setTheme(currentPack(a).themeRes)
    }

    /** 切换器选中某套：持久化并返回 */
    fun set(a: AppCompatActivity, index: Int): StylePack {
        val i = if (index in PACKS.indices) index else DEFAULT_INDEX
        sp(a).edit().putInt(KEY_STYLE, i).apply()
        return PACKS[i]
    }
}
