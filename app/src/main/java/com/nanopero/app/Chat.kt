package com.nanopero.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

// ---------- Поиск по своим заметкам (локальный BM25) ----------

data class NoteDoc(val title: String, val date: Long, val text: String, val kind: String)

object NotesIndex {
    private val stop = setOf(
        "что", "как", "это", "был", "была", "было", "были", "для", "или", "его", "её", "она", "они", "оно", "мне", "меня",
        "мой", "моя", "мои", "моё", "все", "всё", "так", "там", "тут", "где", "когда", "чем", "кто", "уже", "ещё", "еще",
        "про", "при", "над", "под", "без", "though", "the", "and", "what", "when", "where", "who", "how", "with",
        "есть", "нет", "надо", "нужно", "очень", "можно", "тоже", "если", "чтобы", "потом", "сейчас", "который", "которые"
    )

    /** Грубый «стемминг»: обрезаем окончания, чтобы «обещал/обещала/обещания» совпадали. */
    fun tokens(s: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
        .filter { it.length >= 3 && it !in stop }
        .map { if (it.length > 6) it.take(it.length - 2).take(7) else it.take(6) }
        .toList()

    fun docs(app: App): List<NoteDoc> {
        val out = mutableListOf<NoteDoc>()
        app.stores.diary.items.forEach { e ->
            val tasks = if (e.tasks.isEmpty()) "" else "\nЗадачи: " + e.tasks.joinToString("; ") { (if (it.done) "[сделано] " else "[открыто] ") + it.text }
            out += NoteDoc(e.title.ifBlank { "Заметка" }, e.time, "${e.summary}\n${e.text}$tasks".trim(), "дневник")
        }
        app.stores.reads.items.forEach { r ->
            if (r.summary.isNotBlank()) out += NoteDoc(r.title, r.time, r.summary, "статья")
            r.text.chunked(900).take(30).forEach { part -> out += NoteDoc(r.title, r.time, part, "статья") }
        }
        return out
    }

    fun search(app: App, query: String, k: Int = 4): List<NoteDoc> {
        val all = docs(app)
        if (all.isEmpty()) return emptyList()
        val q = tokens(query).distinct()
        val toks = all.map { tokens(it.title + " " + it.text) }
        val avg = toks.map { it.size }.average().coerceAtLeast(1.0)
        val df = HashMap<String, Int>()
        toks.forEach { t -> t.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = all.size.toDouble()
        val scored = all.indices.map { i ->
            val tf = toks[i].groupingBy { it }.eachCount()
            var sc = 0.0
            for (w in q) {
                val f = tf[w] ?: continue
                val idf = ln(1 + (n - (df[w] ?: 0) + 0.5) / ((df[w] ?: 0) + 0.5))
                sc += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * toks[i].size / avg))
            }
            // Свежие заметки немного важнее
            sc *= 1.0 + 0.15 * (1.0 / (1 + (System.currentTimeMillis() - all[i].date) / 86_400_000.0 / 30))
            i to sc
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val base = scored.take(k).map { all[it.first] }.toMutableList()
        // Вопрос про дела — добавим открытые задачи
        if (q.any { it.startsWith("зада") || it.startsWith("дел") || it.startsWith("обеща") }) {
            val open = app.stores.diary.items.flatMap { e -> e.tasks.filter { !it.done }.map { e to it } }.take(20)
            if (open.isNotEmpty()) base.add(0, NoteDoc("Открытые задачи", System.currentTimeMillis(),
                open.joinToString("\n") { (e, t) -> "• ${t.text} (${fmtDate(e.time)})" }, "задачи"))
        }
        return base
    }
}

fun fmtDate(t: Long): String = SimpleDateFormat("d MMM", Locale("ru")).format(Date(t))

// ---------- Состояние чата ----------

object ChatState {
    var currentId by mutableStateOf<Long?>(null)
    /** Текст, переданный в чат из других мест (история, «Открыть в приложении»). */
    var draft by mutableStateOf("")
    /** Запрос открыть экран моделей (из чата). */
    var openModels by mutableStateOf(false)
    var streaming by mutableStateOf("")
    var busy by mutableStateOf(false)
    var sources by mutableStateOf<List<NoteDoc>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var image by mutableStateOf<String?>(null)   // путь к прикреплённому фото
    var thinking by mutableStateOf(false)        // модель сейчас размышляет (ответ ещё не начался)
    var thinkMode by mutableStateOf(false)       // запрос отправлен с «Думать перед ответом»
    var job: Job? = null
}

/** Уменьшенная копия фото в папке приложения (модели хватает ~768 px). */
fun saveChatImage(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
    val k = 768f / maxOf(src.width, src.height)
    val bm = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
    val dir = java.io.File(ctx.filesDir, "chat_img").apply { mkdirs() }
    val f = java.io.File(dir, "${System.currentTimeMillis()}.jpg")
    f.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
    f.path
}.getOrNull()

/** Системная роль + найденные заметки; история — отдельными сообщениями (правильные роли для модели). */
private fun buildReq(app: App, chat: Chat, text: String, budget: Int, notes: List<NoteDoc>): AiReq {
    val sys = StringBuilder(Prompts.SYSTEM_CHAT)
    Personas.current(app.settings).prompt.takeIf { it.isNotBlank() }?.let { sys.append("\n\nРоль: ").append(it) }
    chat.doc?.let { d ->
        sys.append("\n\nПользователь приложил документ «${chat.docName}». Отвечай по нему; если ответа в нём нет — так и скажи. " +
                "Фрагменты документа:\n").append(DocReader.relevant(d, text + " " + (chat.msgs.lastOrNull { it.role == "user" }?.text ?: ""), (budget * 0.55).toInt()))
    }
    if (chat.notes) {
        if (notes.isEmpty()) sys.append("\n\nВ личных заметках пользователя по этому вопросу ничего не найдено — если вопрос про заметки, так и скажи.")
        else {
            sys.append("\n\nНиже фрагменты из личных заметок пользователя. Отвечай, опираясь на них, и называй дату заметки. " +
                    "Если в них нет ответа — так и скажи.\n")
            var left = (budget * 0.5).toInt()
            notes.forEach { d ->
                val piece = "— [${d.kind}, ${fmtDate(d.date)}] ${d.title}: ${d.text.replace('\n', ' ')}".take(800)
                if (left - piece.length > 0) { sys.append(piece).append('\n'); left -= piece.length }
            }
        }
    }
    // Предыдущие сообщения (без текущего), пока влезают в бюджет
    val prev = chat.msgs.dropLast(1)
    val hist = ArrayDeque<ChatMsg>()
    var used = sys.length + text.length
    for (m in prev.asReversed()) {
        if (used + m.text.length > budget) break
        hist.addFirst(m); used += m.text.length
    }
    // История должна начинаться с сообщения пользователя
    while (hist.isNotEmpty() && hist.first().role != "user") hist.removeFirst()
    return AiReq(sys.toString(), text, hist.toList(), temperature = app.settings.temperature, maxOut = 1024)
}

fun sendChat(app: App, scope: kotlinx.coroutines.CoroutineScope, text: String, notesMode: Boolean, image: String? = null, resend: Boolean = false) {
    val store = app.stores.chats
    var chat = ChatState.currentId?.let { id -> store.items.firstOrNull { it.id == id } }
    if (chat == null) {
        chat = Chat(newId(), System.currentTimeMillis(), text.take(40), notesMode, emptyList())
        store.add(chat); ChatState.currentId = chat.id
    }
    val id = chat.id
    if (!resend) store.update({ it.id == id }) { it.copy(msgs = it.msgs + ChatMsg("user", text, image), time = System.currentTimeMillis(), notes = notesMode) }
    ChatState.job?.cancel()
    ChatState.job = scope.launch {
        ChatState.busy = true; ChatState.streaming = ""; ChatState.error = null; ChatState.sources = emptyList()
        try {
            val c = store.items.first { it.id == id }
            val notes = if (c.notes) NotesIndex.search(app, text) else emptyList()
            ChatState.sources = notes
            val img = (image ?: if (resend) c.msgs.lastOrNull()?.image else null)?.let { p -> runCatching { java.io.File(p).readBytes() }.getOrNull() }
            val think = app.settings.think && supportsThinking(app.settings.activeModel) && !app.ai.useNanoNow()
            val req = buildReq(app, c, text, app.ai.chunkChars() + 600, notes).copy(image = img, think = think)
            ChatState.thinkMode = think; ChatState.thinking = think
            app.ai.stream(req).second.collect { raw ->
                if (think) ChatState.thinking = afterThink(raw).isBlank()
                ChatState.streaming = Clean.output(raw, null)
            }
            val answer = ChatState.streaming.trim().removePrefix("Ассистент:").trim()
            store.update({ it.id == id }) { it.copy(msgs = it.msgs + ChatMsg("assistant", answer.ifBlank { "…" })) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val partial = ChatState.streaming.trim()
            if (partial.isNotBlank()) store.update({ it.id == id }) { it.copy(msgs = it.msgs + ChatMsg("assistant", "$partial …")) }
            throw e
        } catch (e: Throwable) {
            ErrorLog.add("Чат", e)
            ChatState.error = e.message
        } finally {
            ChatState.busy = false; ChatState.streaming = ""; ChatState.thinking = false; ChatState.thinkMode = false
        }
    }
}

/** «Ещё раз»: убираем последний ответ и спрашиваем заново. */
fun regenerate(app: App, scope: kotlinx.coroutines.CoroutineScope) {
    val id = ChatState.currentId ?: return
    val c = app.stores.chats.items.firstOrNull { it.id == id } ?: return
    if (c.msgs.lastOrNull()?.role == "assistant") app.stores.chats.update({ it.id == id }) { it.copy(msgs = it.msgs.dropLast(1)) }
    val q = app.stores.chats.items.first { it.id == id }.msgs.lastOrNull { it.role == "user" } ?: return
    sendChat(app, scope, q.text, c.notes, resend = true)
}

/** «Изменить вопрос»: убираем последний вопрос с ответом и возвращаем текст в поле ввода. */
fun editLastQuestion(app: App): String? {
    val id = ChatState.currentId ?: return null
    val c = app.stores.chats.items.firstOrNull { it.id == id } ?: return null
    val i = c.msgs.indexOfLast { it.role == "user" }
    if (i < 0) return null
    app.stores.chats.update({ it.id == id }) { it.copy(msgs = it.msgs.take(i)) }
    return c.msgs[i].text
}

/** Обработка текста режимом прямо в чате (замена старой вкладки «Текст»). */
fun sendMode(app: App, scope: kotlinx.coroutines.CoroutineScope, mode: Mode, text: String, preset: Preset? = null) {
    val store = app.stores.chats
    var chat = ChatState.currentId?.let { id -> store.items.firstOrNull { it.id == id } }
    val title = if (mode == Mode.CUSTOM) preset?.name ?: "Пресет" else mode.title
    if (chat == null) {
        chat = Chat(newId(), System.currentTimeMillis(), "$title: ${text.take(30)}", false, emptyList())
        store.add(chat); ChatState.currentId = chat.id
    }
    val id = chat.id
    store.update({ it.id == id }) { it.copy(msgs = it.msgs + ChatMsg("user", "✦ $title\n$text"), time = System.currentTimeMillis()) }
    ChatState.job?.cancel()
    ChatState.job = scope.launch {
        ChatState.busy = true; ChatState.streaming = ""; ChatState.error = null; ChatState.sources = emptyList()
        try {
            val (engine, flow) = app.ai.run(mode, text, preset)
            flow.collect { ChatState.streaming = it }
            val answer = ChatState.streaming.trim()
            store.update({ it.id == id }) { it.copy(msgs = it.msgs + ChatMsg("assistant", answer.ifBlank { "…" })) }
            if (app.settings.historyEnabled && answer.isNotBlank())
                app.stores.history.add(HistoryItem(newId(), System.currentTimeMillis(), title, text.take(4000), answer.take(8000), engine))
            if (mode == Mode.EVENT) CalendarHelper.parse(answer)?.let { CalendarHelper.open(app, it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { ErrorLog.add("Чат: $title", e); ChatState.error = e.message } finally { ChatState.busy = false; ChatState.streaming = "" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val view = rememberHaptics()
    val store = app.stores.chats
    val chat = ChatState.currentId?.let { id -> store.items.firstOrNull { it.id == id } }
    var notesMode by remember(chat?.id) { mutableStateOf(chat?.notes ?: false) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(ChatState.draft) { if (ChatState.draft.isNotEmpty()) { input = ChatState.draft; ChatState.draft = "" } }
    var showList by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val dict = remember { Dictation(ctx, app.settings.speechLang) }
    DisposableEffect(Unit) { onDispose { dict.stop() } }
    LaunchedEffect(dict.text) { if (dict.listening || dict.text.isNotBlank()) input = dict.text }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) dict.start() }
    var attachMenu by remember { mutableStateOf(false) }
    val nanoActive by produceState(false, app.settings.activeModel, app.settings.engine) { value = app.ai.useNanoNow() }
    val who = if (nanoActive) "Gemini Nano" else (ModelCatalog.byFile(app.settings.activeModel)?.name
        ?: app.settings.activeModel.substringBeforeLast('.').ifBlank { "нет модели" })
    var personaMenu by remember { mutableStateOf(false) }
    var customPersona by remember { mutableStateOf(false) }
    var remindText by remember { mutableStateOf<String?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { ChatState.image = saveChatImage(ctx, uri) }
    }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val (name, text) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DocReader.read(ctx, uri) }
                var c = ChatState.currentId?.let { id -> store.items.firstOrNull { it.id == id } }
                if (c == null) { c = Chat(newId(), System.currentTimeMillis(), "📄 $name".take(40), false, emptyList()); store.add(c); ChatState.currentId = c.id }
                val cid = c.id
                store.update({ it.id == cid }) { it.copy(docName = name, doc = text.take(400_000)) }
                SnackHost.show(ctx, "«$name» прикреплён · ${text.length / 1000} тыс. знаков")
                if (input.isBlank()) input = "Кратко перескажи этот документ"
            } catch (e: Throwable) { SnackHost.show(ctx, e.message ?: "Не удалось открыть документ") }
        }
    }

    val msgs = chat?.msgs ?: emptyList()
    // Прокрутка вниз: при новых сообщениях — плавно, во время печати ответа — без анимации и только если ты внизу
    LaunchedEffect(msgs.size) {
        val n = msgs.size + (if (ChatState.busy) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    LaunchedEffect(ChatState.streaming.length / 120) {
        val n = msgs.size + 1
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (ChatState.busy && n > 0 && last >= n - 2) { list.scrollToItem(n - 1); list.scrollBy(100_000f) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Чипы листаются вбок, если не помещаются
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
            FilterChip(notesMode, { notesMode = !notesMode; view.tick() }, { Text("Заметки") },
                leadingIcon = { Icon(Icons.Rounded.MenuBook, null, Modifier.size(18.dp)) })
            // Кто сейчас отвечает — нажми, чтобы сменить модель
            AssistChip({ ChatState.openModels = true }, { Text(who, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(if (nanoActive) Icons.Rounded.AutoAwesome else Icons.Rounded.Memory, null, Modifier.size(18.dp)) },
                modifier = Modifier.widthIn(max = 140.dp))
            if (supportsThinking(app.settings.activeModel) && !nanoActive) FilterChip(app.settings.think, {
                app.settings.think = !app.settings.think; view.tick()
                SnackHost.show(ctx, if (app.settings.think) "Буду думать перед ответом — дольше, но точнее" else "Отвечаю сразу")
            }, { Text("Думать") }, leadingIcon = { Icon(Icons.Rounded.Psychology, null, Modifier.size(18.dp)) })
            Box {
                val p = Personas.current(app.settings)
                AssistChip({ personaMenu = true }, { Text("${p.emoji} ${p.name}") })
                DropdownMenu(personaMenu, { personaMenu = false }) {
                    Personas.all.forEachIndexed { i, pp ->
                        DropdownMenuItem({ Text("${pp.emoji}  ${pp.name}") }, {
                            app.settings.persona = i; personaMenu = false
                            if (pp.name == "Своя роль") customPersona = true
                        }, trailingIcon = if (app.settings.persona == i) ({ Icon(Icons.Rounded.Check, null) }) else null)
                    }
                }
            }
            }
            IconButton({ showList = true }) { Icon(Icons.Rounded.History, "Все чаты") }
            FilledTonalIconButton({ ChatState.job?.cancel(); ChatState.currentId = null; input = "" }) {
                Icon(Icons.Rounded.AddComment, "Новый чат")
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty() && !ChatState.busy) item {
                val nano by produceState(NanoState.UNKNOWN) { value = app.ai.nano.cachedState() }
                var have by remember { mutableStateOf(hasAnyModel(ctx)) }
                if (!have && nano != NanoState.AVAILABLE && nano != NanoState.UNKNOWN) {
                    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Скачай модель, чтобы ИИ заработал", style = MaterialTheme.typography.titleMedium)
                            Text("Один раз через интернет — дальше всё работает офлайн.", style = MaterialTheme.typography.bodySmall)
                            ModelStore(compact = true, onMore = { ChatState.openModels = true }) { have = hasAnyModel(ctx) }
                        }
                    }
                }
                ChatEmpty(notesMode) { input = it }
            }
            itemsIndexed(msgs, key = { i, m -> "${chat?.id}-$i-${m.role}" }) { i, m ->
                Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(240),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 600f), fadeOutSpec = androidx.compose.animation.core.tween(150))) {
                val last = i == msgs.lastIndex && m.role == "assistant" && !ChatState.busy
                Bubble(m.role == "user", m.text, m.image, id = "${chat?.id}-$i", onRemind = { remindText = it },
                    actions = if (!last) null else ({
                        AssistChip({ regenerate(app, scope) }, { Text("Ещё раз") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)) })
                        AssistChip({ editLastQuestion(app)?.let { input = it } }, { Text("Изменить вопрос") }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) })
                        AssistChip({ sendChat(app, scope, "Продолжи с того места, где остановился.", notesMode) }, { Text("Продолжить") },
                            leadingIcon = { Icon(Icons.Rounded.FastForward, null, Modifier.size(16.dp)) })
                    }))
                }
            }
            if (ChatState.busy) item {
                Column {
                    if (ChatState.streaming.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        NanoLoader(28.dp); Spacer(Modifier.width(10.dp))
                        Column {
                            Text(when {
                                ChatState.thinking -> "Размышляю над ответом…"
                                notesMode -> "Ищу в заметках и думаю…"
                                else -> "Думаю…"
                            }, style = MaterialTheme.typography.bodySmall)
                            if (ChatState.thinking) Text("Режим размышлений: дольше, но точнее", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Bubble(false, ChatState.streaming)
                }
            }
            if (ChatState.sources.isNotEmpty() && notesMode) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Источники", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChatState.sources.distinctBy { it.title + it.date }.forEach { d ->
                        AssistChip({}, { Text("${d.title} · ${fmtDate(d.date)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(if (d.kind == "статья") Icons.Rounded.Article else Icons.Rounded.Notes, null, Modifier.size(16.dp)) })
                    }
                }
            }
            ChatState.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }

        // Длинный текст в поле → быстрые режимы обработки
        androidx.compose.animation.AnimatedVisibility(input.length > 25 && !ChatState.busy && !notesMode) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                items(Mode.entries.filter { it.inChips }) { m ->
                    AssistChip({
                        view.tick(); val t = input.trim(); input = ""; dict.reset()
                        sendMode(app, scope, m, t)
                    }, { Text(m.title) }, leadingIcon = { Icon(m.icon, null, Modifier.size(16.dp)) })
                }
                items(app.stores.presets.items.toList()) { p ->
                    AssistChip({
                        view.tick(); val t = input.trim(); input = ""; dict.reset()
                        sendMode(app, scope, Mode.CUSTOM, t, p)
                    }, { Text(p.name) }, leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp)) })
                }
            }
        }
        // Прикреплённое: документ чата и фото к следующему сообщению
        if (chat?.docName != null || ChatState.image != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            chat?.docName?.let { n ->
                InputChip(true, {}, { Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis) }, Modifier.weight(1f, fill = false),
                    leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Убрать", Modifier.size(16.dp).clickable {
                        store.update({ it.id == chat.id }) { it.copy(docName = null, doc = null) } }) })
            }
            ChatState.image?.let { p ->
                val bmp = remember(p) { android.graphics.BitmapFactory.decodeFile(p)?.asImageBitmap() }
                if (bmp != null) Box {
                    androidx.compose.foundation.Image(bmp, null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Icon(Icons.Rounded.Cancel, "Убрать фото", Modifier.align(Alignment.TopEnd).size(20.dp).clickable { ChatState.image = null })
                }
            }
        }
        Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton({ attachMenu = true }) { Icon(Icons.Rounded.AddCircleOutline, "Прикрепить") }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem({ Text("Фото") }, {
                            attachMenu = false
                            pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, leadingIcon = { Icon(Icons.Rounded.Image, null) })
                        DropdownMenuItem({ Text("Документ (PDF, Word, TXT)") }, {
                            attachMenu = false
                            pickDoc.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*"))
                        }, leadingIcon = { Icon(Icons.Rounded.Description, null) })
                    }
                }
                IconButton({
                    if (dict.listening) dict.stop()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { dict.reset(); dict.start() }
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(if (dict.listening) Icons.Rounded.Stop else Icons.Rounded.Mic, "Голосом",
                    tint = if (dict.listening) MaterialTheme.colorScheme.error else LocalContentColor.current) }
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), maxLines = 5,
                    shape = RoundedCornerShape(24.dp), placeholder = { Text(if (notesMode) "Спроси свои заметки…" else "Сообщение") })
                Spacer(Modifier.width(8.dp))
                if (ChatState.busy) FilledIconButton({ ChatState.job?.cancel(); app.ai.local.cancelActive() }, Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Stop, "Стоп")
                } else FilledIconButton(
                    onClick = {
                        dict.stop()
                        val img = ChatState.image
                        val t = input.trim().ifEmpty { if (img != null) "Что на фото?" else "" }; if (t.isEmpty()) return@FilledIconButton
                        view.confirm(); input = ""; dict.reset(); ChatState.image = null
                        sendChat(app, scope, t, notesMode, img)
                    },
                    modifier = Modifier.size(52.dp), enabled = input.isNotBlank() || ChatState.image != null
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Отправить") }
            }
        }
    }

    remindText?.let { t -> RemindDialog(t) { remindText = null } }
    if (customPersona) {
        var v by remember { mutableStateOf(app.settings.customPersona) }
        AlertDialog({ customPersona = false }, confirmButton = { TextButton({ app.settings.customPersona = v; customPersona = false }) { Text("Сохранить") } },
            title = { Text("Своя роль") },
            text = { OutlinedTextField(v, { v = it }, minLines = 3, placeholder = { Text("Например: ты юрист, отвечай со ссылками на статьи закона") }) })
    }

    if (showList) ModalBottomSheet(onDismissRequest = { showList = false }) {
        Text("Чаты", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            if (store.items.isEmpty()) item { Text("Пока нет чатов", Modifier.padding(8.dp)) }
            items(store.items.toList(), key = { it.id }) { c -> Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = androidx.compose.animation.core.spring(stiffness = 500f), fadeOutSpec = androidx.compose.animation.core.tween(160))) {
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${fmtTime(c.time)} · ${c.msgs.size} сообщ." + if (c.notes) " · заметки" else "") },
                    leadingContent = { Icon(if (c.notes) Icons.Rounded.MenuBook else Icons.Rounded.ChatBubbleOutline, null) },
                    trailingContent = {
                        IconButton({ store.remove { it.id == c.id }; if (ChatState.currentId == c.id) ChatState.currentId = null }) {
                            Icon(Icons.Rounded.DeleteOutline, "Удалить")
                        }
                    },
                    modifier = Modifier.padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { ChatState.currentId = c.id; showList = false },
                    colors = ListItemDefaults.colors(
                        containerColor = if (c.id == ChatState.currentId) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }}
        }
    }
}

@Composable
private fun ChatEmpty(notes: Boolean, onPick: (String) -> Unit) {
    val ideas = if (notes) listOf("Какие задачи у меня не закрыты?", "Что я писал про работу на этой неделе?", "О чём была последняя отложенная статья?")
    else listOf("Придумай 5 идей для выходных", "Объясни, как работает инфляция", "Помоги составить список покупок на неделю")
    val hint = if (notes) null else "Вставь длинный текст — появятся кнопки «Исправить», «Деловой стиль», «Перевести» и другие."
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NanoLoaderContained(64.dp)
        Text(if (notes) "Спроси свой дневник и статьи" else "Офлайн-чат", style = MaterialTheme.typography.headlineSmall)
        Text(if (notes) "Ищу по заметкам на телефоне и отвечаю по ним" else "Никакого интернета — всё на телефоне",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ideas.forEach { SuggestionChip({ onPick(it) }, { Text(it) }) }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)) }
    }
}

@Composable
private fun Bubble(mine: Boolean, text: String, image: String? = null, id: String = "", onRemind: ((String) -> Unit)? = null,
                   actions: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = if (mine) RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp),
            modifier = Modifier.widthIn(max = 330.dp).animateContentSize()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                image?.let { p ->
                    val bmp = remember(p) { android.graphics.BitmapFactory.decodeFile(p)?.asImageBitmap() }
                    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(14.dp)).padding(bottom = 6.dp))
                }
                if (mine) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) } else MdText(text)
                if (!mine && id.isNotEmpty()) Row(Modifier.align(Alignment.End)) {
                    val speaking = Speaker.speakingId == id
                    IconButton({ Speaker.toggle(ctx, id, text) }, Modifier.size(32.dp)) {
                        Icon(if (speaking) Icons.Rounded.StopCircle else Icons.Rounded.VolumeUp, "Прочитать вслух", Modifier.size(16.dp))
                    }
                    onRemind?.let { IconButton({ it(text) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Alarm, "Напомнить", Modifier.size(16.dp)) } }
                    IconButton({ ctx.copyText(text) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.ContentCopy, "Копировать", Modifier.size(16.dp)) }
                }
            }
        }
        if (actions != null) Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            actions()
        }
    }
}
