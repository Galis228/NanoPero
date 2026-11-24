package com.nanopero.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Защита от развода в SMS: быстрая эвристика в фоне (без нейросети — телефон не греется).
 * Если сообщение подозрительное — уведомление, по нажатию модель разбирает его подробно.
 */
object ScamGuard {
    private const val CHANNEL = "scam"
    val smsApps = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.android.messaging", "com.textra", "org.thoughtcrime.securesms"
    )

    private val strong = listOf(
        Regex("(код|code)\\s*(из|в)?\\s*(смс|sms|сообщени)", RegexOption.IGNORE_CASE),
        Regex("(карт\\w*|счёт|счет|рахун\\w*)\\s+(заблокир|блокир|арешт|приостанов)", RegexOption.IGNORE_CASE),
        Regex("(служб\\w* безопасности|безпеки банку|security service)", RegexOption.IGNORE_CASE),
        Regex("(выигр|виграл|приз|подарок|компенсац|выплат\\w* от государ)", RegexOption.IGNORE_CASE),
        Regex("(cvv|cvc|пин-?код|pin)", RegexOption.IGNORE_CASE),
    )
    private val weak = listOf(
        Regex("https?://|bit\\.ly|tinyurl|t\\.me/|wa\\.me/|\\.(top|xyz|click|link|icu|shop)\\b", RegexOption.IGNORE_CASE),
        Regex("(срочно|терміново|немедленно|в течение \\d+|осталось \\d+)", RegexOption.IGNORE_CASE),
        Regex("(перевед|переказ|оплат|доплат|подтверд|підтверд|верифик)", RegexOption.IGNORE_CASE),
        Regex("(посылк|доставк|nova poshta|нова пошта|укрпошт)", RegexOption.IGNORE_CASE),
        Regex("(мама|сынок|доченька|это я|новый номер|новий номер)", RegexOption.IGNORE_CASE),
    )

    fun score(text: String): Int = strong.count { it.containsMatchIn(text) } * 2 + weak.count { it.containsMatchIn(text) }

    fun check(ctx: Context, pkg: String, sender: String, text: String) {
        if (!ctx.app.settings.smsGuard || pkg !in smsApps) return
        if (score(text) < 3) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Подозрительные SMS", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, text.hashCode(),
            Intent(ctx, ProcessTextActivity::class.java)
                .putExtra(ProcessTextActivity.EXTRA_INPUT, text)
                .putExtra(ProcessTextActivity.EXTRA_AUTO_MODE, Mode.SCAM.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("⚠️ Похоже на развод: $sender")
            .setContentText("Не переходи по ссылкам и не называй коды. Нажми — разберу подробнее.")
            .setStyle(Notification.BigTextStyle().bigText(
                "«${text.take(200)}»\n\nНе переходи по ссылкам, не называй коды из SMS и данные карты. Нажми, чтобы проверить подробнее."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(text.hashCode(), n) }
    }
}
