package com.nanopero.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Разобрать заметку моделью: заголовок, суть, теги, задачи. */
suspend fun structureEntry(app: App, id: Long) {
    val e = app.stores.diary.items.firstOrNull { it.id == id } ?: return
    val raw = app.ai.complete(Prompts.diary(e.text.take(app.ai.chunkChars())), temperature = 0.1f)
    val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull()
    app.stores.diary.update({ it.id == id }) { old ->
        if (o == null) old.copy(processed = true, title = old.text.take(40))
        else {
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase().removePrefix("#") } }
                ?.filter { it.isNotBlank() && it.length < 25 }?.distinct()?.take(4) ?: emptyList()
            val tasks = o.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }
                ?.filter { it.length > 2 }?.take(10)?.map { DiaryTask(it, false) } ?: emptyList()
            old.copy(
                title = o.optString("title").trim().ifBlank { old.text.take(40) },
                summary = o.optString("summary").trim(),
                tags = tags, tasks = tasks, processed = true
            )
        }
    }
}

@Composable
fun DiaryTab(autoRecord: Boolean) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val dict = remember { Dictation(ctx, app.settings.speechLang) }
    var manual by rememberSaveable { mutableStateOf("") }
    var filterTag by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyTasks by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var processing by remember { mutableStateOf<Long?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { dict.stop() } }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) dict.start() else err = "Без доступа к микрофону голосовые заметки не работают"
    }
    fun startRec() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dict.start()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(autoRecord) { if (autoRecord) startRec() }

    fun save(text: String) {
        if (text.isBlank()) return
        val e = DiaryEntry(newId(), System.currentTimeMillis(), text.trim())
        app.stores.diary.add(e)
        scope.launch {
            processing = e.id; err = null
            try { structureEntry(app, e.id) }
            catch (x: kotlinx.coroutines.CancellationException) { throw x }
            catch (x: Throwable) { err = "Заметка сохранена, но разобрать её не удалось: ${x.message}" }
            finally { processing = null }
        }
    }

    val entries = app.stores.diary.items
    val allTags = remember(entries.size, entries.sumOf { it.tags.size }) {
        entries.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20)
    }
    val shown = entries.filter { e ->
        (filterTag == null || filterTag in e.tags) &&
                (!onlyTasks || e.tasks.any { !it.done }) &&
                (query.isBlank() || e.text.contains(query, true) || e.title.contains(query, true))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pulse by animateFloatAsState(1f + dict.level * 0.25f, label = "pulse")
                    Box(contentAlignment = Alignment.Center) {
                        if (dict.listening) Box(Modifier.size(84.dp).scale(pulse)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape))
                        LargeFloatingActionButton(
                            onClick = {
                                if (dict.listening) { dict.stop(); save(dict.text); dict.reset() } else { dict.reset(); startRec() }
                            },
                            shape = CircleShape,
                            containerColor = if (dict.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer
                        ) { Icon(if (dict.listening) Icons.Rounded.Stop else Icons.Rounded.Mic, "Запись", Modifier.size(36.dp)) }
                    }
                    Text(
                        when {
                            dict.listening -> "Говори… нажми ■ чтобы сохранить" + if (dict.onDevice) " · офлайн" else ""
                            else -> "Нажми и надиктуй мысль — НаноПеро сам добавит заголовок, теги и задачи"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dict.text.isNotBlank()) Text(dict.text, style = MaterialTheme.typography.bodyLarge)
                    if (dict.listening) TextButton({ dict.stop(); dict.reset() }) { Text("Отменить") }
                    (dict.error ?: err)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), placeholder = { Text("…или напиши текстом") }, maxLines = 4)
                        IconButton({ save(manual); manual = "" }, enabled = manual.isNotBlank()) { Icon(Icons.Rounded.Send, "Сохранить") }
                    }
                }
            }
        }

        if (entries.isNotEmpty()) item { WeeklyRecapCard() }
        if (entries.size >= 3) item { DiaryStats(entries.toList()) }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Поиск по дневнику") })
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(onlyTasks, { onlyTasks = !onlyTasks }, { Text("Открытые задачи") },
                        leadingIcon = { Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp)) })
                }
                items(allTags) { t -> FilterChip(filterTag == t, { filterTag = if (filterTag == t) null else t }, { Text("#$t") }) }
            }
        }
        if (shown.isEmpty()) item { Text("Пока нет заметок.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown.toList(), key = { it.id }) { e -> Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = androidx.compose.animation.core.spring(stiffness = 500f), fadeOutSpec = androidx.compose.animation.core.tween(160))) {
            DiaryCard(e, processing == e.id,
                onTag = { filterTag = it },
                onRetry = {
                    scope.launch {
                        processing = e.id
                        try { structureEntry(app, e.id) } catch (x: kotlinx.coroutines.CancellationException) { throw x }
                        catch (x: Throwable) { err = x.message } finally { processing = null }
                    }
                })
        }}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiaryCard(e: DiaryEntry, busy: Boolean, onTag: (String) -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val store = ctx.app.stores.diary
    val calScope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { e.text.take(40) }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(fmtTime(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) NanoLoader(20.dp)
                else Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (e.summary.isNotBlank()) Text(e.summary, style = MaterialTheme.typography.bodyMedium)
            if (e.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                e.tags.forEach { t -> AssistChip({ onTag(t) }, { Text("#$t", style = MaterialTheme.typography.labelSmall) }) }
            }
            e.tasks.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(t.done, { v ->
                        store.update({ it.id == e.id }) { old ->
                            old.copy(tasks = old.tasks.mapIndexed { j, k -> if (j == i) k.copy(done = v) else k })
                        }
                    })
                    Text(t.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        textDecoration = if (t.done) TextDecoration.LineThrough else null)
                    if (!t.done) {
                        var remind by remember { mutableStateOf(false) }
                        IconButton({ remind = true }) { Icon(Icons.Rounded.Alarm, "Напомнить") }
                        if (remind) RemindDialog(t.text) { remind = false }
                        var calBusy by remember { mutableStateOf(false) }
                        IconButton(enabled = !calBusy, onClick = {
                            calScope.launch {
                                calBusy = true
                                try { taskToCalendar(ctx.app, t.text, e.time) }
                                catch (x: kotlinx.coroutines.CancellationException) { throw x }
                                catch (x: Throwable) { SnackHost.show(ctx, x.message ?: "Ошибка") }
                                finally { calBusy = false }
                            }
                        }) { if (calBusy) NanoLoader(18.dp) else Icon(Icons.Rounded.EventAvailable, "В календарь", Modifier.size(20.dp)) }
                    }
                }
            }
            if (open) {
                HorizontalDivider()
                SelectionContainer { Text(e.text, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton({ ctx.copyText(e.text) }) { Text("Копировать") }
                    TextButton(onRetry, enabled = !busy) { Text("Разобрать заново") }
                    Spacer(Modifier.weight(1f))
                    IconButton({ confirmDel = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                }
            }
        }
    }
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false },
        confirmButton = { TextButton({ store.remove { it.id == e.id }; confirmDel = false }) { Text("Удалить") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Отмена") } },
        title = { Text("Удалить заметку?") }
    )
}


/** Статистика дневника: серия дней, заметок за неделю/месяц, активность по дням недели, частые теги, задачи. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiaryStats(entries: List<DiaryEntry>) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val st = remember(entries.size, entries.sumOf { it.tasks.count { t -> t.done } }) {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        fun dayKey(t: Long) = cal.apply { timeInMillis = t }.let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }
        val days = entries.map { dayKey(it.time) }.toSet()
        var streak = 0
        var t = now
        if (dayKey(t) !in days) t -= day
        while (dayKey(t) in days) { streak++; t -= day }
        val byDow = IntArray(7)
        entries.forEach { e -> cal.timeInMillis = e.time; byDow[(cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7]++ }
        val tasks = entries.flatMap { it.tasks }
        mapOf(
            "streak" to streak, "week" to entries.count { it.time > now - 7 * day }, "month" to entries.count { it.time > now - 30 * day },
            "tasks" to tasks.size, "done" to tasks.count { it.done },
            "words" to entries.sumOf { it.text.split(Regex("\\s+")).size }
        ) to (byDow to entries.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6))
    }
    val (n, rest) = st
    val (dow, tags) = rest
    ElevatedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Insights, null, tint = cs.primary); Spacer(Modifier.width(8.dp))
                Text("Статистика", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                @Composable fun num(v: Int, l: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$v", style = MaterialTheme.typography.headlineSmall, color = cs.primary); Text(l, style = MaterialTheme.typography.labelSmall)
                }
                num(n["streak"]!!, "дней подряд"); num(n["week"]!!, "за неделю"); num(n["month"]!!, "за месяц")
                num(n["done"]!!, "из ${n["tasks"]} задач")
            }
            if (open) {
                Text("Когда ты пишешь чаще", style = MaterialTheme.typography.labelLarge)
                val mx = (dow.maxOrNull() ?: 1).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { i, d ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height((50f * dow[i] / mx).dp.coerceAtLeast(2.dp))
                                .background(cs.primary, MaterialTheme.shapes.extraSmall))
                            Text(d, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    Text("Частые темы", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { (t, c) -> SuggestionChip({}, { Text("#$t · $c") }) } }
                }
                Text("Всего ${entries.size} заметок, ~${n["words"]} слов.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
