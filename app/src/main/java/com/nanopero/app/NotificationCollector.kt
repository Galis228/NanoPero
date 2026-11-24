package com.nanopero.app

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings as SysSettings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Собирает текст уведомлений из выбранных мессенджеров в локальный файл.
 * Никуда не отправляет — только для сводки дня на телефоне.
 */
class NotificationCollector : NotificationListenerService() {

    /** Действие «Ответить» из уведомления, пока оно живо (ключ — приложение + чат). */
    data class ReplyAction(val pi: PendingIntent, val input: RemoteInput)

    companion object {
        val replies = mutableMapOf<String, ReplyAction>()
        /** Время последнего сохранённого сообщения по каждому чату — чтобы брать все новые, а не только последнее. */
        private val lastMsgTime = HashMap<String, Long>()
        fun chatKey(pkg: String, title: String) = "$pkg|$title"

        fun isEnabled(ctx: Context): Boolean {
            val flat = SysSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotificationCollector::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }

        /** Отправить ответ через RemoteInput мессенджера. */
        fun sendReply(ctx: Context, key: String, text: String): Boolean {
            val a = replies[key] ?: return false
            return runCatching {
                val intent = Intent()
                val b = Bundle().apply { putCharSequence(a.input.resultKey, text) }
                RemoteInput.addResultsToIntent(arrayOf(a.input), intent, b)
                a.pi.send(ctx, 0, intent)
                true
            }.getOrDefault(false)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext as? App ?: return
        val s = app.settings
        if (sbn.packageName == packageName) return
        val collect = s.digestEnabled && (s.notifAllApps || sbn.packageName in s.notifAppSet)
        if (!collect && sbn.packageName !in ScamGuard.smsApps) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return
        val cat = n.category
        if (cat == Notification.CATEGORY_PROGRESS || cat == Notification.CATEGORY_TRANSPORT ||
            cat == Notification.CATEGORY_SERVICE || cat == Notification.CATEGORY_SYSTEM) return

        val ex = n.extras ?: return
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = (convTitle ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: appName).trim()

        // Сообщения: MessagingStyle (по одному) или обычный текст
        val lines = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val msgs: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgs.isNullOrEmpty()) {
            // Мессенджеры перевыкладывают весь чат каждый раз: берём только сообщения новее уже сохранённых
            // (если пришло два сразу — сохраняем оба, а не только последнее)
            val key = chatKey(sbn.packageName, title)
            val since = lastMsgTime[key] ?: (System.currentTimeMillis() - 10 * 60_000L)
            var newest = since
            val bundles = msgs.mapNotNull { it as? Bundle }
            val fresh = bundles.filter { it.getLong("time", 0L).let { t -> t == 0L || t > since } }.ifEmpty { listOfNotNull(bundles.lastOrNull()) }
            fresh.takeLast(10).forEach { b ->
                val sender = b.getCharSequence("sender")?.toString()
                val text = b.getCharSequence("text")?.toString().orEmpty()
                newest = maxOf(newest, b.getLong("time", 0L))
                if (text.isNotBlank()) lines += if (sender != null && sender != title) "$sender: $text" else text
            }
            lastMsgTime[key] = newest
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
            if (text.isNotBlank()) lines += text
        }
        val text = lines.joinToString("\n").trim().take(2000)
        if (text.isBlank()) return

        ScamGuard.check(this, sbn.packageName, title, text)
        if (!collect) return
        if (chatKey(sbn.packageName, title) in s.mutedSet) return

        // Действие «Ответить»
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }?.let { a ->
            val ri = a.remoteInputs.first { it.allowFreeFormInput }
            replies[chatKey(sbn.packageName, title)] = ReplyAction(a.actionIntent, ri)
        }

        val store = app.stores.notifs
        val now = System.currentTimeMillis()
        // Дубли: тот же текст в том же чате за последние сутки
        val dup = store.items.asSequence().take(300).any {
            it.pkg == sbn.packageName && it.title == title && it.text == text && now - it.time < 86_400_000L
        }
        if (dup) return
        store.add(NotifItem(newId(), now, sbn.packageName, appName, title, text))
        // Храним не больше 3 суток
        store.remove { now - it.time > 3 * 86_400_000L }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Ответ через уведомление больше невозможен
        val ex = sbn.notification?.extras ?: return
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim() ?: return
        replies.remove(chatKey(sbn.packageName, title))
    }
}
