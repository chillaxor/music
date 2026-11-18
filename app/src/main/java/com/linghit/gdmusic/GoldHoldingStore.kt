package com.linghit.gdmusic

import android.content.Context

/**
 * 首页金价卡「我的持仓」轻量存储。
 *
 * 只存两个数：买入克数（如 69g）+ 买入均价（如 1028 元/克）。与首页实时国内金价
 * （gold-api 的 XAU × 汇率 ÷ 31.1035）对比，算出投入 / 现值 / 浮亏。
 *
 * 独立 SharedPreferences「gold_holding_store」，不并入 networth_store：
 * 这是金价卡的小配置，跟记账页的资产流水无关，避免污染其 payload 备份结构。
 *
 * 首次使用给出一组可直接看到效果的默认值（69g / 1028 元），用户可在详情面板里改。
 */
object GoldHoldingStore {
    private const val FILE = "gold_holding_store"
    private const val KEY_GRAMS = "grams"
    private const val KEY_AVG = "avg_price"

    const val DEFAULT_GRAMS = 69f
    const val DEFAULT_AVG = 1028f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 买入克数；未保存过返回默认值 */
    fun grams(ctx: Context): Float = prefs(ctx).getFloat(KEY_GRAMS, DEFAULT_GRAMS)

    /** 买入均价（元/克）；未保存过返回默认值 */
    fun avgPrice(ctx: Context): Float = prefs(ctx).getFloat(KEY_AVG, DEFAULT_AVG)

    /** 是否已由用户手动保存过（仅用于 UI 提示，读取一律走 grams/avgPrice 的默认值） */
    fun isSaved(ctx: Context): Boolean =
        prefs(ctx).contains(KEY_GRAMS) || prefs(ctx).contains(KEY_AVG)

    fun save(ctx: Context, grams: Float, avg: Float) {
        prefs(ctx).edit().putFloat(KEY_GRAMS, grams).putFloat(KEY_AVG, avg).apply()
    }
}
