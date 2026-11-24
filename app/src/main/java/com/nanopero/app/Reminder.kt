package com.nanopero.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import java.util.Calendar

/** Вечернее напоминание «сделать сводку дня». Сама сводка запускается вручную — фоном телефон не греем. */
object Reminder {
    private const val CHANNEL = "digest"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Сводка дня", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 10, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun apply(ctx: Context) {
        val s = ctx.app.settings
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx))
        if (!s.reminderOn) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.reminderHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC, c.timeInMillis, AlarmManager.INTERVAL_DAY, pending(ctx))
    }

    fun show(ctx: Context) {
        val count = ctx.app.stores.notifs.items.count { it.time > startOfToday() }
        if (count == 0) return
        val open = PendingIntent.getActivity(
            ctx, 11,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DIGEST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Сводка дня готова к созданию")
            .setContentText("За сегодня $count сообщений. Нажми, чтобы получить главное — офлайн.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(42, n) }
    }

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { Reminder.apply(ctx); UserReminders.rescheduleAll(ctx) }
            UserReminders.ACTION -> UserReminders.fire(ctx, intent.getIntExtra("id", 0), intent.getStringExtra("text").orEmpty())
            else -> Reminder.show(ctx)
        }
    }
}
