package com.linghit.gdmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 提醒广播接收：
 *  - REMINDER_FIRE：闹钟到点 → 去重 → 弹通知；重复类提醒触发后重排下一个触发点
 *  - REMINDER_DISMISS / REMINDER_SNOOZE：通知栏按钮
 *  - BOOT_COMPLETED / 时区变化 / 系统时间变化 / 应用更新：全量重排闹钟（离线提醒的关键兜底）
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.TIME_SET" -> {
                ReminderScheduler.rescheduleAll(ctx)
            }
            "com.linghit.gdmusic.REMINDER_FIRE" -> {
                val id = intent.getStringExtra("id") ?: return
                val occ = intent.getStringExtra("occ") ?: return
                val adv = intent.getIntExtra("adv", 0)
                val code = intent.getIntExtra("code", -1)
                val item = ReminderStore.get(ctx, id) ?: return
                if (!item.enabled) return
                if (code >= 0 && ReminderStore.isFired(ctx, code.toString())) return
                if (code >= 0) ReminderStore.markFired(ctx, code.toString())
                val notifId = (id.hashCode() and 0x0FFFFFFF) + (adv * 31) + (occ.takeLast(2).toIntOrNull() ?: 0)
                ReminderNotif.post(ctx, notifId, item, occ, adv)
                // 每年/每月重复的提醒：触发后立刻排下一个周期，用户不重启 App 也不会断
                if (item.repeat != ReminderStore.REPEAT_NONE) {
                    ReminderScheduler.rescheduleAll(ctx)
                }
            }
            "com.linghit.gdmusic.REMINDER_DISMISS" -> {
                val notifId = intent.getIntExtra("notifId", -1)
                if (notifId >= 0) ReminderNotif.cancel(ctx, notifId)
                val id = intent.getStringExtra("id") ?: return
                val occ = intent.getStringExtra("occ") ?: return
                val adv = intent.getIntExtra("adv", 0)
                val code = ("$id|$occ|$adv").hashCode()
                ReminderStore.markFired(ctx, code.toString())
            }
            "com.linghit.gdmusic.REMINDER_SNOOZE" -> {
                val notifId = intent.getIntExtra("notifId", -1)
                if (notifId >= 0) ReminderNotif.cancel(ctx, notifId)
                val id = intent.getStringExtra("id") ?: return
                val occ = intent.getStringExtra("occ") ?: return
                val adv = intent.getIntExtra("adv", 0)
                ReminderScheduler.snooze(ctx, id, occ, adv)
            }
        }
    }
}
