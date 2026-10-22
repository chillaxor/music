package com.linghit.gdmusic

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 提醒调度层：把每条提醒拆成「触发点 × 提前天数」的精确闹钟，交给 AlarmManager。
 *
 * 设计要点：
 *  - requestCode 由 (提醒id|触发日期|提前天数) 哈希而来，重排前后保持稳定；
 *  - rescheduleAll 幂等：先按持久化的 scheduledCodes 全取消，再按当前数据全量重排；
 *    开机 / 时区变化 / APP 启动都会调它，进程被杀后闹钟由系统持有， receiver 被拉起；
 *  - Android 12+ 精确闹钟需 SCHEDULE_EXACT_ALARM 授权，未授权时自动降级为非精确
 *    setAndAllowWhileIdle（仍能响，只是不保证准点）。
 */
object ReminderScheduler {

    const val CHANNEL_ID = "reminder"

    fun rescheduleAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // 1) 取消上一轮注册的全部闹钟（保证幂等，不会越积越多）
        for (code in ReminderStore.scheduledCodes(ctx)) {
            val pi = buildPi(ctx, code, "", "", 0, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        // 2) 按当前数据重排
        val newCodes = mutableSetOf<Int>()
        val now = System.currentTimeMillis()
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        for (item in ReminderStore.list(ctx)) {
            if (!item.enabled) continue
            for ((occKey, occBase) in occurrences(ctx, item)) {
                for (adv in item.advances) {
                    val triggerAt = shiftDays(occBase, -adv) + (item.hour.toLong() * 3600_000L + item.minute.toLong() * 60_000L)
                    // 已过去的触发点不再排（firedCodes 去重在 receiver 侧）
                    if (triggerAt <= now + 60_000L) continue
                    val code = codeFor(item.id, occKey, adv)
                    val pi = buildPi(ctx, code, item.id, occKey, adv, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: continue
                    if (exactOk) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                    newCodes.add(code)
                }
            }
        }
        ReminderStore.setScheduledCodes(ctx, newCodes)
    }

    /** 单条提醒变化后调用（新增/编辑/启停） */
    fun onChanged(ctx: Context) = rescheduleAll(ctx)

    /** 稍后提醒：1 小时后再响一次 */
    fun snooze(ctx: Context, id: String, occKey: String, adv: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val code = codeFor("$id#snooze", occKey, adv)
        val triggerAt = System.currentTimeMillis() + 3600_000L
        val pi = buildPi(ctx, code, id, occKey, adv, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelCode(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPi(ctx, code, "", "", 0, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) { am.cancel(pi); pi.cancel() }
    }

    // ---------- 内部 ----------

    /**
     * 计算一条提醒未来的触发点列表：Pair(触发日期key yyyyMMdd, 触发日0点毫秒)。
     * 只返回 >= 今天 00:00 的点；每年/每月重复只返回最近一个，触发后 receiver 会再排下一个。
     */
    fun occurrences(ctx: Context, item: ReminderStore.Item): List<Pair<String, Long>> {
        val today0 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val out = mutableListOf<Pair<String, Long>>()
        fun add(y: Int, m: Int, d: Int) {
            val c = Calendar.getInstance().apply {
                clear(); set(y, m - 1, d, 0, 0, 0)
            }
            if (c.before(today0)) return
            val key = String.format("%04d%02d%02d", y, m, d)
            out.add(key to c.timeInMillis)
        }
        when {
            item.type == ReminderStore.TYPE_FESTIVAL && item.festKey.isNotBlank() -> {
                val table = FestivalTable.TABLES[item.festKey] ?: return out
                // 表里是完整公历日期 yyyymmdd（除夕/春节的年份归属已烘焙在数据里），
                // 全部过一遍、经 add() 过滤掉已过去的，取最早的一个（只排最近一个触发点）
                for (v in table) {
                    val md = v % 10000
                    add(v / 10000, md / 100, md % 100)
                }
                return out.sortedBy { it.second }.take(1)
            }
            item.repeat == ReminderStore.REPEAT_YEARLY || item.type == ReminderStore.TYPE_BIRTHDAY -> {
                val y = today0.get(Calendar.YEAR)
                for (yy in y..(y + 2)) {
                    if (item.month == 2 && item.day == 29 && !isLeap(yy)) continue
                    add(yy, item.month, item.day)
                }
                return out.sortedBy { it.second }.take(1)
            }
            item.repeat == ReminderStore.REPEAT_MONTHLY -> {
                val base = today0.clone() as Calendar
                for (i in 0..12) {
                    val c = base.clone() as Calendar
                    c.add(Calendar.MONTH, i)
                    val md = c.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtMost(item.day)
                    add(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, md)
                }
                return out.sortedBy { it.second }.take(1)
            }
            else -> {
                val y = if (item.year > 0) item.year else today0.get(Calendar.YEAR)
                add(y, item.month, item.day)
                return out
            }
        }
    }

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun shiftDays(base: Long, days: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun codeFor(id: String, occKey: String, adv: Int): Int =
        ("$id|$occKey|$adv").hashCode()

    private fun buildPi(ctx: Context, code: Int, id: String, occKey: String, adv: Int, flags: Int): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.linghit.gdmusic.REMINDER_FIRE"
            putExtra("id", id); putExtra("occ", occKey); putExtra("adv", adv)
            putExtra("code", code)
        }
        return runCatching {
            PendingIntent.getBroadcast(ctx, code, i, flags)
        }.getOrElse {
            // FLAG_IMMUTABLE 在部分老 ROM 上可能不被支持时的兜底
            PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
