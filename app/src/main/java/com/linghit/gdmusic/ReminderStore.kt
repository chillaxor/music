package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 提醒数据层：SharedPreferences + JSON，沿用项目既有 Store 模式，完全离线。
 *
 * 类型：
 *  - birthday 生日（每年重复，公历）
 *  - custom   自定义（每年/每月/仅一次）
 *  - festival 农历节日（FestivalTable key，日期每年从表里重算）
 *  - train    高铁票（创建时由 ReminderStore.addTrain 派生为多条仅一次提醒）
 *
 * 触发时刻统一用 hour:minute；advances = 提前提醒天数列表（0=当天）。
 */
object ReminderStore {
    private const val FILE = "reminders"
    private const val KEY_LIST = "list"
    private const val KEY_SCHEDULED = "scheduled_codes"
    private const val KEY_FIRED = "fired_codes"

    const val TYPE_BIRTHDAY = "birthday"
    const val TYPE_CUSTOM = "custom"
    const val TYPE_FESTIVAL = "festival"
    const val TYPE_TRAIN = "train"

    const val REPEAT_NONE = "none"
    const val REPEAT_YEARLY = "yearly"
    const val REPEAT_MONTHLY = "monthly"

    class Item(
        val id: String,
        var type: String,
        var title: String,
        var emoji: String,
        var month: Int,
        var day: Int,
        var repeat: String = REPEAT_NONE,
        var advances: List<Int> = listOf(0),
        var hour: Int = 9,
        var minute: Int = 0,
        var note: String = "",
        /** 节日类专用：FestivalTable 的 key（SPRING/CHU_XI/…），日期每年从表重算 */
        var festKey: String = "",
        /** 仅一次提醒的显式年份（custom/festival 不需要，生日/农历节日按年循环算） */
        var year: Int = 0,
        var enabled: Boolean = true,
        var createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("type", type); put("title", title); put("emoji", emoji)
            put("month", month); put("day", day); put("repeat", repeat)
            put("advances", JSONArray(advances))
            put("hour", hour); put("minute", minute); put("note", note)
            put("festKey", festKey); put("year", year)
            put("enabled", enabled); put("createdAt", createdAt)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<Item> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val out = mutableListOf<Item>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val adv = mutableListOf<Int>()
                o.optJSONArray("advances")?.let { a ->
                    for (j in 0 until a.length()) adv.add(a.optInt(j))
                }
                if (adv.isEmpty()) adv.add(0)
                out.add(
                    Item(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        type = o.optString("type", TYPE_CUSTOM),
                        title = o.optString("title"),
                        emoji = o.optString("emoji", "⏰"),
                        month = o.optInt("month"),
                        day = o.optInt("day"),
                        repeat = o.optString("repeat", REPEAT_NONE),
                        advances = adv.distinct().sortedDescending(),
                        hour = o.optInt("hour", 9),
                        minute = o.optInt("minute", 0),
                        note = o.optString("note"),
                        festKey = o.optString("festKey"),
                        year = o.optInt("year"),
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
        } catch (e: Exception) { }
        return out
    }

    private fun save(ctx: Context, l: List<Item>) {
        val arr = JSONArray()
        l.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun upsert(ctx: Context, it: Item) {
        val l = list(ctx)
        val i = l.indexOfFirst { x -> x.id == it.id }
        if (i >= 0) l[i] = it else l.add(it)
        save(ctx, l)
    }

    fun remove(ctx: Context, id: String) {
        val l = list(ctx)
        l.removeAll { it.id == id }
        save(ctx, l)
    }

    fun get(ctx: Context, id: String): Item? = list(ctx).firstOrNull { it.id == id }

    /**
     * 高铁票：按出发日派生三条仅一次提醒（12306 默认提前 15 天放票）。
     * @return 生成的提醒条数
     */
    fun addTrain(ctx: Context, departY: Int, departM: Int, departD: Int, from: String, to: String, saleLeadDays: Int = 15): Int {
        fun cal(y: Int, m: Int, d: Int) = java.util.Calendar.getInstance().apply {
            clear(); set(y, m - 1, d, 0, 0, 0)
        }
        val dep = cal(departY, departM, departD)
        val sale = cal(departY, departM, departD).apply { add(java.util.Calendar.DAY_OF_YEAR, -saleLeadDays) }
        val dayBefore = cal(departY, departM, departD).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        fun fmt(c: java.util.Calendar) =
            "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}"
        fun ym(c: java.util.Calendar) = c.get(java.util.Calendar.MONTH) + 1
        fun yd(c: java.util.Calendar) = c.get(java.util.Calendar.DAY_OF_MONTH)
        fun yy(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR)
        val trip = if (from.isNotBlank() || to.isNotBlank()) "$from → $to" else "高铁出行"
        val items = listOf(
            Item(
                id = UUID.randomUUID().toString(), type = TYPE_TRAIN,
                title = "该抢${trip}的车票了", emoji = "🚄",
                month = ym(sale), day = yd(sale), year = yy(sale),
                repeat = REPEAT_NONE, advances = listOf(0), hour = 8, minute = 0,
                note = "${fmt(sale)} 放票（提前 $saleLeadDays 天），记得抢 ${fmt(dep)} 的票"
            ),
            Item(
                id = UUID.randomUUID().toString(), type = TYPE_TRAIN,
                title = "${trip}该出发了", emoji = "🧳",
                month = ym(dayBefore), day = yd(dayBefore), year = yy(dayBefore),
                repeat = REPEAT_NONE, advances = listOf(0), hour = 20, minute = 0,
                note = "明天 ${fmt(dep)} 出发，今晚收拾好东西"
            ),
            Item(
                id = UUID.randomUUID().toString(), type = TYPE_TRAIN,
                title = "${trip}今天出发", emoji = "🚅",
                month = ym(dep), day = yd(dep), year = yy(dep),
                repeat = REPEAT_NONE, advances = listOf(0), hour = 7, minute = 0,
                note = "今天 ${fmt(dep)} 出发，别误车"
            )
        )
        items.forEach { upsert(ctx, it) }
        return items.size
    }

    /** 已注册闹钟的 requestCode 集合（rescheduleAll 先按它全取消再重排，保证幂等）。prefs 存字符串，读出来转 Int */
    fun scheduledCodes(ctx: Context): MutableSet<Int> {
        val raw = prefs(ctx).getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()
        val out = LinkedHashSet<Int>()
        raw.forEach { it.toIntOrNull()?.let { c -> out.add(c) } }
        return out
    }

    fun setScheduledCodes(ctx: Context, codes: Set<Int>) {
        val strs = LinkedHashSet(codes.map { it.toString() })
        prefs(ctx).edit().putStringSet(KEY_SCHEDULED, strs).apply()
    }

    /** 已触发记录：requestCode 集合，防同一触发点重复弹通知 */
    fun firedCodes(ctx: Context): MutableSet<String> {
        val raw = prefs(ctx).getStringSet(KEY_FIRED, emptySet()) ?: emptySet()
        return LinkedHashSet(raw)
    }

    fun markFired(ctx: Context, code: String) {
        val s = firedCodes(ctx)
        s.add(code)
        // 只保留最近 500 条，防止无限增长
        val trimmed = if (s.size > 500) LinkedHashSet(s.toList().takeLast(500)) else s
        prefs(ctx).edit().putStringSet(KEY_FIRED, trimmed).apply()
    }

    fun isFired(ctx: Context, code: String) = firedCodes(ctx).contains(code)
}
