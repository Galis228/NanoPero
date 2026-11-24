package com.nanopero.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream

// ---------- Markdown ----------

/** Простой рендер markdown: заголовки, списки, **жирный**, *курсив*, `код`, блоки ``` и таблицы как моноширинный текст. */
@Composable
fun MdText(text: String) {
    val cs = MaterialTheme.colorScheme
    val blocks = remember(text) { mdBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            when (b.kind) {
                'c' -> Surface(shape = MaterialTheme.shapes.small, color = cs.surfaceContainerHighest) {
                    SelectionContainer {
                        Text(b.text, Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                'h' -> Text(inline(b.text, cs.primary, cs.surfaceContainerHighest), style = MaterialTheme.typography.titleMedium)
                'l' -> Row {
                    Text(b.marker, Modifier.width(22.dp), color = cs.primary, style = MaterialTheme.typography.bodyLarge)
                    SelectionContainer { Text(inline(b.text, cs.primary, cs.surfaceContainerHighest), style = MaterialTheme.typography.bodyLarge) }
                }
                'q' -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Surface(Modifier.width(3.dp).fillMaxHeight(), color = cs.primary) {}
                    Spacer(Modifier.width(8.dp))
                    Text(inline(b.text, cs.primary, cs.surfaceContainerHighest), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                }
                else -> SelectionContainer { Text(inline(b.text, cs.primary, cs.surfaceContainerHighest), style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

private class MdBlock(val kind: Char, val text: String, val marker: String = "")

private fun mdBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock('p', para.toString().trim()); para.clear() }
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]; val t = l.trim()
        when {
            t.startsWith("```") -> {
                flush(); val code = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += MdBlock('c', code.toString().trimEnd())
            }
            t.startsWith("|") && t.endsWith("|") -> {
                flush(); val tb = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val r = lines[i].trim()
                    if (!r.replace("|", "").replace("-", "").replace(":", "").isBlank()) tb.appendLine(r.trim('|').split('|').joinToString("  │ ") { it.trim() })
                    i++
                }
                i--; out += MdBlock('c', tb.toString().trimEnd())
            }
            Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); out += MdBlock('h', t.trimStart('#').trim()) }
            Regex("^[-*•]\\s").containsMatchIn(t) -> { flush(); out += MdBlock('l', t.substring(2).trim(), "•") }
            Regex("^\\d+[.)]\\s").containsMatchIn(t) -> { flush(); val m = t.substringBefore(' '); out += MdBlock('l', t.substringAfter(' ').trim(), m) }
            t.startsWith(">") -> { flush(); out += MdBlock('q', t.trimStart('>').trim()) }
            t.isEmpty() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
        }
        i++
    }
    flush()
    return out
}

private fun inline(s: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > i + 2 -> {
                val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > i -> {
                val e = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, e)) }; i = e + 1
            }
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf(s[i], i + 1) > i + 1 &&
                    (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                val e = s.indexOf(s[i], i + 1); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }; i = e + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

// ---------- Озвучка ----------

object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    var speakingId by mutableStateOf<String?>(null)
    private var pending: Pair<String, String>? = null

    fun toggle(ctx: Context, id: String, text: String) {
        if (speakingId == id) { stop(); return }
        val clean = text.replace(Regex("[*_`#>|]"), "").replace(Regex("\\s+"), " ")
        if (tts == null) {
            pending = id to clean
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(u: String?) {}
                        override fun onDone(u: String?) { if (speakingId == u) speakingId = null }
                        @Deprecated("") override fun onError(u: String?) { speakingId = null }
                    })
                    pending?.let { (i, t) -> say(i, t) }; pending = null
                } else SnackHost.show(ctx, "Синтез речи недоступен на телефоне")
            }
        } else if (ready) say(id, clean)
    }

    private fun say(id: String, text: String) {
        val t = tts ?: return
        // Язык по алфавиту текста
        val loc = when {
            Regex("[іїєґ]", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Locale("uk", "UA")
            Regex("[а-яё]", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Locale("ru", "RU")
            else -> Locale.US
        }
        runCatching { t.language = loc }
        speakingId = id
        // Длинный текст — частями (у движка есть лимит на одну фразу)
        val parts = text.chunked(TextToSpeech.getMaxSpeechInputLength() - 10)
        parts.forEachIndexed { i, p -> t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) id else "$id#$i") }
    }

    fun stop() { tts?.stop(); speakingId = null }
}

// ---------- Роли ----------

data class Persona(val name: String, val emoji: String, val prompt: String)

object Personas {
    val all = listOf(
        Persona("Обычный", "✦", ""),
        Persona("Кратко", "⚡", "Отвечай максимально кратко: 1–3 предложения или короткий список, без вступлений."),
        Persona("Учитель", "🎓", "Ты терпеливый учитель. Объясняй простыми словами, с примером из жизни, в конце — один вопрос для проверки понимания."),
        Persona("Редактор", "✍️", "Ты строгий литературный редактор. Указывай на ошибки и слабые места, предлагай улучшенный вариант."),
        Persona("Друг", "🙂", "Говори тепло и по-дружески, на «ты», с лёгким юмором, но по делу."),
        Persona("Программист", "💻", "Ты опытный разработчик. Отвечай точно, с кодом в блоках ``` и короткими пояснениями."),
        Persona("Своя роль", "🎭", ""),
    )
    fun current(s: Settings): Persona {
        val p = all.getOrElse(s.persona) { all[0] }
        return if (p.name == "Своя роль") p.copy(prompt = s.customPersona) else p
    }
}

// ---------- Документы ----------

object DocReader {
    /** Текст из PDF / DOCX / TXT / MD. Возвращает (имя, текст). */
    fun read(ctx: Context, uri: Uri): Pair<String, String> {
        val cr = ctx.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "документ"
        val mime = cr.getType(uri).orEmpty()
        val text = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> pdf(ctx, uri)
            mime.contains("wordprocessingml") || name.endsWith(".docx", true) -> docx(ctx, uri)
            else -> cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        }
        if (text.isBlank()) throw EngineException("Не удалось достать текст из «$name». Если это скан — в нём нет текста, только картинки.")
        return name to text
    }

    private fun docx(ctx: Context, uri: Uri): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        val xml = z.readBytes().toString(Charsets.UTF_8)
                        xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t")
                            .replace(Regex("<[^>]+>"), "").let { sb.append(android.text.Html.fromHtml(it, 0).toString()) }
                    }
                    e = z.nextEntry
                }
            }
        }
        return sb.toString()
    }

    private fun pdf(ctx: Context, uri: Uri): String {
        if (android.os.Build.VERSION.SDK_INT < 35) throw EngineException("Чтение PDF работает на Android 15 и новее.")
        val sb = StringBuilder()
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                for (i in 0 until minOf(r.pageCount, 200)) {
                    r.openPage(i).use { p -> p.textContents.forEach { sb.append(it.text).append('\n') } }
                }
            }
        }
        return sb.toString()
    }

    /** Самые подходящие к вопросу куски документа (BM25 по абзацам), не больше бюджета. */
    fun relevant(doc: String, query: String, budget: Int): String {
        if (doc.length <= budget) return doc
        val chunks = doc.split(Regex("\n\\s*\n")).flatMap { if (it.length > 900) it.chunked(800) else listOf(it) }.filter { it.isNotBlank() }
        val q = NotesIndex.tokens(query).filter { it.length > 2 }.map { it.take(5) }.toSet()
        if (q.isEmpty()) return doc.take(budget)
        val toks = chunks.map { c -> NotesIndex.tokens(c).map { it.take(5) } }
        val avg = toks.map { it.size }.average().coerceAtLeast(1.0)
        val df = q.associateWith { t -> toks.count { t in it }.coerceAtLeast(1) }
        val n = chunks.size
        val scored = chunks.indices.map { i ->
            val tf = toks[i].groupingBy { it }.eachCount()
            val sc = q.sumOf { t ->
                val f = (tf[t] ?: 0).toDouble(); val idf = Math.log(1 + (n - df[t]!! + 0.5) / (df[t]!! + 0.5))
                idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * toks[i].size / avg))
            }
            i to sc
        }.sortedByDescending { it.second }
        val pick = ArrayList<Int>(); var used = 0
        for ((i, _) in scored) { if (used + chunks[i].length > budget) continue; pick += i; used += chunks[i].length }
        return pick.sorted().joinToString("\n…\n") { chunks[it] }
    }
}

// ---------- Свои напоминания (из чата и дневника) ----------

object UserReminders {
    private const val CH = "remind"
    const val ACTION = "com.nanopero.app.REMIND"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Напоминания", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun add(ctx: Context, at: Long, text: String) {
        val id = (at / 1000 % Int.MAX_VALUE).toInt()
        prefs(ctx).edit().putString(id.toString(), "$at\u0001$text").apply()
        schedule(ctx, id, at, text)
    }

    private fun schedule(ctx: Context, id: Int, at: Long, text: String) {
        val pi = PendingIntent.getBroadcast(ctx, 1000 + id,
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Неточное окно 10 минут — без разрешения на точные будильники и бережнее к батарее
        ctx.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        prefs(ctx).all.forEach { (k, v) ->
            val (at, text) = (v as? String)?.split('\u0001', limit = 2)?.let { it[0].toLong() to it.getOrElse(1) { "" } } ?: return@forEach
            if (at > now - 3600_000) schedule(ctx, k.toInt(), maxOf(at, now + 5000), text) else prefs(ctx).edit().remove(k).apply()
        }
    }

    fun fire(ctx: Context, id: Int, text: String) {
        prefs(ctx).edit().remove(id.toString()).apply()
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 2000 + id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_tile).setContentTitle("Напоминание")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(3000 + id, n) }
    }

    /** Варианты времени для быстрого выбора. */
    fun presets(): List<Pair<String, Long>> {
        val now = Calendar.getInstance()
        fun at(days: Int, h: Int) = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, days); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        return buildList {
            add("Через час" to now.timeInMillis + 3600_000)
            add("Через 3 часа" to now.timeInMillis + 3 * 3600_000)
            if (now.get(Calendar.HOUR_OF_DAY) < 19) add("Сегодня в 20:00" to at(0, 20))
            add("Завтра в 9:00" to at(1, 9))
            add("Через неделю" to at(7, 9))
        }
    }
}

/** Диалог «Напомнить»: быстрые варианты + свой день/час. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindDialog(text: String, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var custom by remember { mutableStateOf(false) }
    val notif = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    fun set(at: Long) {
        UserReminders.add(ctx, at, text.take(300))
        SnackHost.show(ctx, "Напомню " + java.text.SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(java.util.Date(at)))
        onDismiss()
    }
    if (custom) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        val tp = rememberTimePickerState(9, 0, true)
        var step by remember { mutableIntStateOf(0) }
        if (step == 0) DatePickerDialog(onDismiss, confirmButton = { TextButton({ step = 1 }) { Text("Далее") } }) { DatePicker(dp) }
        else AlertDialog(onDismiss, confirmButton = {
            TextButton({
                val c = Calendar.getInstance().apply {
                    timeInMillis = dp.selectedDateMillis ?: System.currentTimeMillis()
                    val y = get(Calendar.YEAR); val m = get(Calendar.MONTH); val d = get(Calendar.DAY_OF_MONTH)
                    clear(); set(y, m, d, tp.hour, tp.minute)
                }
                set(c.timeInMillis)
            }) { Text("Готово") }
        }, text = { TimePicker(tp) })
        return
    }
    AlertDialog(onDismiss, confirmButton = { TextButton(onDismiss) { Text("Отмена") } },
        title = { Text("Напомнить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text.take(140), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                UserReminders.presets().forEach { (l, at) -> FilledTonalButton({ set(at) }, Modifier.fillMaxWidth()) { Text(l) } }
                OutlinedButton({ custom = true }, Modifier.fillMaxWidth()) { Text("Выбрать дату и время…") }
            }
        })
}
