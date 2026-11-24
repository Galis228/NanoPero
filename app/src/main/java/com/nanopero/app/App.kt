package com.nanopero.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class App : Application() {
    lateinit var settings: Settings
    lateinit var ai: AiRouter
    lateinit var stores: Stores
    val isForeground get() = visible > 0
    val userDict by lazy { UserDict(this) }

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        settings = Settings(this)
        ai = AiRouter(this, settings)
        stores = Stores(this)
        AppLock.init(this)
        Reminder.ensureChannel(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (visible == 0) AppLock.onShown(this@App); visible++ }
            override fun onActivityStopped(a: Activity) {
                visible--
                if (visible <= 0) {
                    AppLock.onHidden()
                    // Приложение скрыто: не греем телефон фоновой генерацией и освобождаем RAM
                    ai.local.cancelActive()
                    ai.local.scheduleUnload(15_000)
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private var visible = 0

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Системе не хватает памяти — сразу выгружаем модель
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL)
            ai.local.releaseAsync()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ai.local.releaseAsync()
    }
}

val Context.app get() = applicationContext as App

fun Context.copyText(text: String) {
    val cm = getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("НаноПеро", text))
    // Android 13+ сам показывает подтверждение копирования
    if (android.os.Build.VERSION.SDK_INT < 33) SnackHost.show(this, "Скопировано")
}

fun Context.shareText(text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(i, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Разбор JSON от модели и открытие экрана создания события в календаре. */
object CalendarHelper {
    fun today(): String {
        val f = SimpleDateFormat("yyyy-MM-dd, EEEE", Locale("ru"))
        return f.format(Calendar.getInstance().time)
    }

    data class Ev(val title: String, val date: String, val time: String, val duration: Int, val location: String)

    fun parse(raw: String): Ev? = runCatching {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        val o = JSONObject(raw.substring(start, end + 1))
        Ev(
            o.optString("title").ifBlank { "Событие" },
            o.optString("date"),
            o.optString("time"),
            o.optInt("duration_min", 60).coerceIn(5, 24 * 60),
            o.optString("location")
        )
    }.getOrNull()

    fun pretty(e: Ev) = buildString {
        append("📌 ").append(e.title)
        append("\n📅 ").append(e.date.ifBlank { "дата не найдена" })
        if (e.time.isNotBlank()) append("  🕒 ").append(e.time)
        if (e.location.isNotBlank()) append("\n📍 ").append(e.location)
    }

    fun open(ctx: Context, e: Ev) {
        val cal = Calendar.getInstance()
        var allDay = true
        runCatching {
            val d = e.date.split("-").map { it.trim().toInt() }
            cal.set(d[0], d[1] - 1, d[2], 9, 0, 0)
        }
        runCatching {
            val t = e.time.split(":").map { it.trim().toInt() }
            cal.set(Calendar.HOUR_OF_DAY, t[0]); cal.set(Calendar.MINUTE, t[1]); allDay = false
        }
        val begin = cal.timeInMillis
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, e.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + e.duration * 60_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (e.location.isNotBlank()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) {
            SnackHost.show(ctx, "Не найдено приложение календаря")
        }
    }
}

/** Сколько байт приложение отправило/получило по сети с момента загрузки телефона. */
fun trafficBytes(): Pair<Long, Long> {
    val uid = android.os.Process.myUid()
    return android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0) to
            android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
}

fun fmtBytes(b: Long) = when {
    b < 1024 -> "$b Б"
    b < 1_048_576 -> "${b / 1024} КБ"
    else -> "%.1f МБ".format(b / 1_048_576f)
}

fun fmtTime(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    val f = if (sameDay) "HH:mm" else "d MMM, HH:mm"
    return SimpleDateFormat(f, Locale("ru")).format(c.time)
}
