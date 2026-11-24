package com.nanopero.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------- модели данных ----------

data class HistoryItem(
    val id: Long, val time: Long, val mode: String, val input: String, val output: String, val engine: String
)

data class DiaryTask(val text: String, val done: Boolean)

data class DiaryEntry(
    val id: Long, val time: Long, val text: String,
    val title: String = "", val summary: String = "",
    val tags: List<String> = emptyList(), val tasks: List<DiaryTask> = emptyList(),
    val processed: Boolean = false
)

data class NotifItem(
    val id: Long, val time: Long, val pkg: String, val app: String, val title: String, val text: String
)

data class ReadItem(
    val id: Long, val time: Long, val title: String, val url: String,
    val text: String, val summary: String = "", val error: String = ""
)

data class Card(val id: Long, val deck: String, val q: String, val a: String, val box: Int = 0, val due: Long = 0)

data class ChatMsg(val role: String, val text: String, val image: String? = null)
data class Chat(val id: Long, val time: Long, val title: String, val notes: Boolean, val msgs: List<ChatMsg>,
                val docName: String? = null, val doc: String? = null)

// ---------- универсальное JSON-хранилище ----------

/**
 * Список, который живёт в Compose-состоянии и сохраняется в JSON-файл.
 * Запись откладывается на 400 мс и выполняется в фоне, чтобы не дёргать диск на каждое изменение.
 */
class JsonStore<T>(
    private val file: File,
    private val cap: Int,
    private val enc: (T) -> JSONObject,
    private val dec: (JSONObject) -> T,
) {
    val items: SnapshotStateList<T> = mutableStateListOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                val list = (0 until arr.length()).mapNotNull { runCatching { dec(arr.getJSONObject(it)) }.getOrNull() }
                items.addAll(list)
            }
        }
    }

    fun add(item: T) { items.add(0, item); trim(); save() }
    fun update(pred: (T) -> Boolean, f: (T) -> T) {
        val i = items.indexOfFirst(pred); if (i >= 0) { items[i] = f(items[i]); save() }
    }
    fun remove(pred: (T) -> Boolean) { if (items.removeAll(pred)) save() }
    fun clear() { items.clear(); save() }

    private fun trim() { while (items.size > cap) items.removeAt(items.lastIndex) }

    fun save() {
        val snapshot = items.toList()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val arr = JSONArray(); snapshot.forEach { arr.put(enc(it)) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(arr.toString()); tmp.renameTo(file)
        }
    }
}

private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun List<String>.json() = JSONArray().also { a -> forEach { a.put(it) } }

class Stores(ctx: Context) {
    private val dir = File(ctx.filesDir, "data").apply { mkdirs() }

    private val histEnc: (HistoryItem) -> JSONObject = { JSONObject().put("id", it.id).put("t", it.time).put("m", it.mode).put("i", it.input).put("o", it.output).put("e", it.engine) }
    private val readsEnc: (ReadItem) -> JSONObject = { JSONObject().put("id", it.id).put("t", it.time).put("ti", it.title).put("u", it.url).put("x", it.text).put("s", it.summary).put("e", it.error) }
    private val diaryEnc: (DiaryEntry) -> JSONObject = {
        JSONObject().put("id", it.id).put("t", it.time).put("x", it.text).put("ti", it.title).put("s", it.summary)
            .put("tg", it.tags.json()).put("p", it.processed)
            .put("tk", JSONArray().also { a -> it.tasks.forEach { k -> a.put(JSONObject().put("x", k.text).put("d", k.done)) } })
    }
    private val diaryDec: (JSONObject) -> DiaryEntry = {
        val tk = it.optJSONArray("tk") ?: JSONArray()
        DiaryEntry(
            it.getLong("id"), it.getLong("t"), it.optString("x"), it.optString("ti"), it.optString("s"),
            (it.optJSONArray("tg") ?: JSONArray()).strings(),
            (0 until tk.length()).map { i -> tk.getJSONObject(i).let { o -> DiaryTask(o.optString("x"), o.optBoolean("d")) } },
            it.optBoolean("p")
        )
    }

    val history by lazy { JsonStore(File(dir, "history.json"), 300, histEnc,
        { HistoryItem(it.getLong("id"), it.getLong("t"), it.optString("m"), it.optString("i"), it.optString("o"), it.optString("e")) }) }

    val diary by lazy { JsonStore(File(dir, "diary.json"), 5000, diaryEnc, diaryDec) }

    val notifs by lazy { JsonStore(File(dir, "notifs.json"), 1500,
        { JSONObject().put("id", it.id).put("t", it.time).put("p", it.pkg).put("a", it.app).put("ti", it.title).put("x", it.text) },
        { NotifItem(it.getLong("id"), it.getLong("t"), it.optString("p"), it.optString("a"), it.optString("ti"), it.optString("x")) }) }

    val reads by lazy { JsonStore(File(dir, "reads.json"), 200, readsEnc,
        { ReadItem(it.getLong("id"), it.getLong("t"), it.optString("ti"), it.optString("u"), it.optString("x"), it.optString("s"), it.optString("e")) }) }

    val presets by lazy { JsonStore(File(dir, "presets.json"), 50,
        { JSONObject().put("id", it.id).put("n", it.name).put("i", it.instruction) },
        { Preset(it.getLong("id"), it.optString("n"), it.optString("i")) }) }

    val cards by lazy { JsonStore(File(dir, "cards.json"), 5000,
        { JSONObject().put("id", it.id).put("d", it.deck).put("q", it.q).put("a", it.a).put("b", it.box).put("u", it.due) },
        { Card(it.getLong("id"), it.optString("d"), it.optString("q"), it.optString("a"), it.optInt("b"), it.optLong("u")) }) }

    val chats by lazy { JsonStore(File(dir, "chats.json"), 100,
        {
            JSONObject().put("id", it.id).put("t", it.time).put("ti", it.title).put("n", it.notes)
                .put("m", JSONArray().also { a -> it.msgs.forEach { m -> a.put(JSONObject().put("r", m.role).put("x", m.text).apply { m.image?.let { put("img", it) } }) } })
                .apply { it.docName?.let { put("dn", it) }; it.doc?.let { put("d", it) } }
        },
        {
            val m = it.optJSONArray("m") ?: JSONArray()
            Chat(it.getLong("id"), it.getLong("t"), it.optString("ti"), it.optBoolean("n"),
                (0 until m.length()).map { i -> m.getJSONObject(i).let { o -> ChatMsg(o.optString("r"), o.optString("x"), o.optString("img").ifEmpty { null }) } },
                it.optString("dn").ifEmpty { null }, it.optString("d").ifEmpty { null })
        }) }

    /** Все данные как один JSON — для резервной копии. */
    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("diary", JSONArray().also { a -> diary.items.forEach { a.put(diaryEnc(it)) } })
        .put("history", JSONArray().also { a -> history.items.forEach { a.put(histEnc(it)) } })
        .put("reads", JSONArray().also { a -> reads.items.forEach { a.put(readsEnc(it)) } })
        .put("presets", JSONArray().also { a -> presets.items.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("i", it.instruction)) } })
        .put("cards", JSONArray().also { a -> cards.items.forEach { a.put(JSONObject().put("id", it.id).put("d", it.deck).put("q", it.q).put("a", it.a).put("b", it.box).put("u", it.due)) } })
        .toString(1)

    // ---------- import ----------
    fun importJson(text: String): Int {
        val o = JSONObject(text)
        var n = 0
        fun <T> merge(store: JsonStore<T>, key: String, id: (T) -> Long, dec: (JSONObject) -> T) {
            val arr = o.optJSONArray(key) ?: return
            val have = store.items.map(id).toHashSet()
            for (i in 0 until arr.length()) {
                val item = runCatching { dec(arr.getJSONObject(i)) }.getOrNull() ?: continue
                if (id(item) !in have) { store.items.add(item); n++ }
            }
            store.save()
        }
        merge(diary, "diary", { it.id }, diaryDec)
        merge(history, "history", { it.id }, { HistoryItem(it.getLong("id"), it.getLong("t"), it.optString("m"), it.optString("i"), it.optString("o"), it.optString("e")) })
        merge(reads, "reads", { it.id }, { ReadItem(it.getLong("id"), it.getLong("t"), it.optString("ti"), it.optString("u"), it.optString("x"), it.optString("s"), "") })
        merge(presets, "presets", { it.id }, { Preset(it.getLong("id"), it.optString("n"), it.optString("i")) })
        merge(cards, "cards", { it.id }, { Card(it.getLong("id"), it.optString("d"), it.optString("q"), it.optString("a"), it.optInt("b"), it.optLong("u")) })
        diary.items.sortByDescending { it.time }; history.items.sortByDescending { it.time }; reads.items.sortByDescending { it.time }
        diary.save(); history.save(); reads.save()
        return n
    }

    /** Дневник в Markdown — читаемый экспорт. */
    fun diaryMarkdown(): String = buildString {
        append("# Дневник НаноПеро\n\n")
        val f = java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale("ru"))
        diary.items.sortedBy { it.time }.forEach { e ->
            append("## ").append(e.title.ifBlank { "Заметка" }).append("\n")
            append("*").append(f.format(java.util.Date(e.time))).append("*")
            if (e.tags.isNotEmpty()) append(" · ").append(e.tags.joinToString(" ") { "#$it" })
            append("\n\n")
            if (e.summary.isNotBlank()) append("> ").append(e.summary).append("\n\n")
            append(e.text).append("\n\n")
            e.tasks.forEach { t -> append(if (t.done) "- [x] " else "- [ ] ").append(t.text).append("\n") }
            if (e.tasks.isNotEmpty()) append("\n")
        }
    }

    init {
        if (!File(dir, "presets.json").exists() && presets.items.isEmpty()) {
            presets.items.addAll(listOf(
                Preset(1, "Пост в Telegram", "Перепиши текст как живой пост для Telegram-канала: короткие абзацы, 1–3 уместных эмодзи, цепляющая первая строка."),
                Preset(2, "Письмо начальнику", "Перепиши как короткое уважительное сообщение руководителю: суть в первой фразе, без воды, вежливо, но не заискивающе."),
                Preset(3, "Без канцелярита", "Убери из текста канцелярит, штампы и лишние слова. Пиши просто и по-человечески.")
            ))
            presets.save()
        }
    }
}

private var idCounter = 0L
/** Уникальный id на основе времени. */
@Synchronized fun newId(): Long {
    val now = System.currentTimeMillis() * 100
    idCounter = if (now > idCounter) now else idCounter + 1
    return idCounter
}
