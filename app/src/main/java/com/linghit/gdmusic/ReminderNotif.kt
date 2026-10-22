package com.linghit.gdmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 提醒通知：IMPORTANCE_HIGH 渠道，锁屏/应用外弹出 Heads-up 横幅（即聊天气泡样式） */
object ReminderNotif {

    const val CHANNEL_ID = "reminder_alert"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "生日、节日、高铁票等重要日期提醒"
                    enableLights(true)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun post(ctx: Context, notifId: Int, item: ReminderStore.Item, occKey: String, adv: Int) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return

        val itemPi = PendingIntent.getActivity(
            ctx, notifId,
            Intent(ctx, ReminderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getBroadcast(
            ctx, notifId + 1,
            Intent(ctx, ReminderReceiver::class.java).apply {
                action = "com.linghit.gdmusic.REMINDER_DISMISS"
                putExtra("notifId", notifId); putExtra("id", item.id)
                putExtra("occ", occKey); putExtra("adv", adv)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getBroadcast(
            ctx, notifId + 2,
            Intent(ctx, ReminderReceiver::class.java).apply {
                action = "com.linghit.gdmusic.REMINDER_SNOOZE"
                putExtra("id", item.id); putExtra("occ", occKey); putExtra("adv", adv)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val whenText = when (adv) {
            0 -> "就是今天"
            1 -> "还有 1 天"
            else -> "还有 $adv 天"
        }
        val title = "${item.emoji} ${item.title}"
        val text = buildString {
            append(whenText)
            if (item.note.isNotBlank()) append(" · ${item.note}")
        }

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(itemPi)
            .addAction(0, "稍后提醒", snoozePi)
            .addAction(0, "知道了", dismissPi)
            .build()
        runCatching { nm.notify(notifId, n) }
    }

    fun cancel(ctx: Context, notifId: Int) {
        runCatching {
            NotificationManagerCompat.from(ctx).cancel(notifId)
        }
    }
}
