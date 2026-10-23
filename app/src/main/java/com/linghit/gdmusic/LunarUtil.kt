package com.linghit.gdmusic

import java.util.Calendar

/**
 * 公历转农历（1900-2099）。
 *
 * 数据表由 `tools/gen_lunar.cjs` 从 `assets/lunar.min.js`（lunar-javascript 1.7.7）生成，
 * 并与该库逐日对拍 73018 天（1900-02-01 ~ 2099-12-31）零差异；基准日 1900-01-31 = 农历 1900 正月初一。
 *
 * 表项 = 经典 lunarInfo 编码（每项一个 Int，下标 = 公元年 - 1900）：
 *  - bits 0-3 ：闰月月份（0 = 无闰月）
 *  - bit  16  ：闰月是否大月 30 天（1 = 30 天，0 = 29 天）
 *  - bits 4-15：正月~腊月是否大月 30 天，月 m 占 bit(16 - m)（正月 → bit15，腊月 → bit4）
 *
 * 超出 1900-2099 的日期返回空串（调用方自己决定显不显示）。
 */
object LunarUtil {

    private const val Y0 = 1900
    private const val Y1 = 2099

    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520
    )

    private val MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )

    private val DAY_NAMES = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    private class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)

    // ==================== 表解码 ====================
    private fun leapMonth(idx: Int) = LUNAR_INFO[idx] and 0xf
    private fun leapDays(idx: Int): Int =
        if (leapMonth(idx) != 0) (if (LUNAR_INFO[idx] and 0x10000 != 0) 30 else 29) else 0
    /** 第 m 个平月的天数（m 为 1..12） */
    private fun monthDays(idx: Int, m: Int) = if (LUNAR_INFO[idx] and (0x10000 shr m) != 0) 30 else 29
    /** 农历整年天数（含闰月） */
    private fun yearDays(idx: Int): Int {
        var sum = 348 // 12 × 29
        var bit = 0x8000
        while (bit > 0x8) {
            if (LUNAR_INFO[idx] and bit != 0) sum++
            bit = bit shr 1
        }
        return sum + leapDays(idx)
    }

    // ==================== 公历 → 农历 ====================
    /** 格里高利利日序（Fliegel-Van Flandern，纯整数运算，无时区/夏令时依赖） */
    private fun julianDay(y: Int, m: Int, d: Int): Int {
        val a = (m - 14) / 12
        return (1461 * (y + 4800 + a)) / 4 +
            (367 * (m - 2 - 12 * a)) / 12 -
            (3 * ((y + 4900 + a) / 100)) / 4 +
            d - 32075
    }

    private fun convert(y: Int, m: Int, d: Int): LunarDate? {
        val base = julianDay(1900, 1, 31) // 农历 1900 正月初一
        var offset = julianDay(y, m, d) - base
        if (offset < 0) return null // 早于表起点

        // 1) 定农历年：逐年减去年天数，减到offset归零或为负
        var i = Y0
        var temp = 0
        while (i <= Y1 && offset > 0) {
            temp = yearDays(i - Y0)
            offset -= temp
            i++
        }
        if (offset < 0 && temp > 0) {
            offset += temp
            i--
        }
        val lunarYear = i
        if (lunarYear < Y0 || lunarYear > Y1) return null

        // 2) 定月与日：闰月插在它所闰的平月之后
        val leap = leapMonth(lunarYear - Y0)
        var isLeap = false
        i = 1
        while (i < 13 && offset > 0) {
            if (leap > 0 && i == leap + 1 && !isLeap) {
                i--
                isLeap = true
                temp = leapDays(lunarYear - Y0)
            } else {
                temp = monthDays(lunarYear - Y0, i)
            }
            if (isLeap && i == leap + 1) isLeap = false
            offset -= temp
            i++
        }
        if (offset == 0 && leap > 0 && i == leap + 1) {
            if (isLeap) isLeap = false
            else {
                isLeap = true
                i--
            }
        }
        if (offset < 0) {
            offset += temp
            i--
        }
        val month = i
        val day = offset + 1
        if (month < 1 || month > 12 || day < 1 || day > 30) return null
        return LunarDate(lunarYear, month, day, isLeap)
    }

    /** 公历 Calendar → 农历文本，如「农历九月十四」「农历闰八月初一」；表外日期返回空串 */
    fun text(cal: Calendar): String {
        val r = convert(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        ) ?: return ""
        val month = (if (r.leap) "闰" else "") + MONTH_NAMES[r.month - 1]
        return "农历$month${DAY_NAMES[r.day - 1]}"
    }
}
