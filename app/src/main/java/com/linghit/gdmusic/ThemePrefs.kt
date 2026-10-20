package com.linghit.gdmusic

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity

/**
 * 主题色弹窗：12 套主题色（6 纯色 + 6 渐变），只换主色，
 * 不动明暗、形状、背景等中性色（用户明确否决过改这些，且暗色套装会造成叠影）。
 *
 * primaryEnd 非 null 表示该套在 header / 播放圈上走渐变；
 * styles.xml 里 colorPrimary 仍是起点色，影响按钮、进度条等单色组件。
 * 主题定义见 res/values/styles.xml，顺序与 PACKS 一一对应。
 */
object ThemePrefs {

    data class StylePack(
        val name: String,
        val themeRes: Int,
        val primary: Int,
        val primaryDark: Int,
        /** 非 null = 渐变终点色；null = 纯色 */
        val primaryEnd: Int? = null
    )

    /** 弹窗网格顺序；第 0 套为默认（薄荷青） */
    val PACKS = listOf(
        StylePack("薄荷青", R.style.AppTheme, 0xFF0E9C8F.toInt(), 0xFF00796B.toInt()),
        StylePack("天空蓝", R.style.AppTheme_Sky, 0xFF4AA8FF.toInt(), 0xFF2B7FD4.toInt()),
        StylePack("马卡龙粉", R.style.AppTheme_Macaron, 0xFFFFB3C6.toInt(), 0xFFE58AA6.toInt()),
        StylePack("香芋紫", R.style.AppTheme_Taro, 0xFFA78BFA.toInt(), 0xFF7C5CC8.toInt()),
        StylePack("蜜桃橙", R.style.AppTheme_Peach, 0xFFFF9A62.toInt(), 0xFFE57238.toInt()),
        StylePack("抹茶绿", R.style.AppTheme_Matcha, 0xFF8BC34A.toInt(), 0xFF689F38.toInt()),
        StylePack("落日渐变", R.style.AppTheme_Sunset, 0xFFFF7E5F.toInt(), 0xFFF08A50.toInt(), 0xFFFEB47B.toInt()),
        StylePack("海洋渐变", R.style.AppTheme_Ocean, 0xFF2E9BFF.toInt(), 0xFF00B8A9.toInt(), 0xFF00D4C8.toInt()),
        StylePack("星云渐变", R.style.AppTheme_Nebula, 0xFF7C5CFC.toInt(), 0xFF5A3FD6.toInt(), 0xFFB45CFF.toInt()),
        StylePack("樱花渐变", R.style.AppTheme_Sakura, 0xFFFF9AC1.toInt(), 0xFFE87BA8.toInt(), 0xFFFFC1E3.toInt()),
        StylePack("极光渐变", R.style.AppTheme_Aurora, 0xFF00E5C0.toInt(), 0xFF00B894.toInt(), 0xFF5B8CFF.toInt()),
        StylePack("暮光渐变", R.style.AppTheme_Dusk, 0xFFFF6B8A.toInt(), 0xFFE05270.toInt(), 0xFFFFC371.toInt())
    )

    private const val DEFAULT_INDEX = 0
    private const val KEY = "theme_idx"
    private const val PREF = "gdmusic_theme"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun indexOf(ctx: Context): Int {
        val i = sp(ctx).getInt(KEY, DEFAULT_INDEX)
        return if (i in PACKS.indices) i else DEFAULT_INDEX
    }

    /** 当前选中索引（弹窗里用来高亮） */
    fun currentIndex(ctx: Context): Int = indexOf(ctx)

    fun currentPack(ctx: Context): StylePack = PACKS[indexOf(ctx)]
    fun packAt(i: Int): StylePack = PACKS.getOrElse(i) { PACKS[DEFAULT_INDEX] }

    /** 在 Activity.setContentView 之前调用，应用已保存的主题 */
    fun applyTheme(a: AppCompatActivity) {
        a.setTheme(currentPack(a).themeRes)
    }

    /** 弹窗选中某套：持久化并返回 */
    fun set(a: AppCompatActivity, index: Int): StylePack {
        val i = if (index in PACKS.indices) index else DEFAULT_INDEX
        sp(a).edit().putInt(KEY, i).apply()
        return PACKS[i]
    }
}
