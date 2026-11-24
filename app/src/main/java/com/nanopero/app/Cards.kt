package com.nanopero.app

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Интервалы Лейтнера по «коробкам», дни. */
private val intervalsDays = listOf(0, 1, 2, 4, 8, 16, 32, 64)

fun parseCards(text: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var q: String? = null
    text.lines().map { it.trim() }.forEach { l ->
        val ql = Regex("^(В|Q|Вопрос)\\s*[:.)-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)
        val al = Regex("^(О|A|Ответ)\\s*[:.)-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)
        when {
            ql != null -> q = ql.groupValues[2]
            al != null && q != null -> { out += q!! to al.groupValues[2]; q = null }
        }
    }
    return out
}

/** Сохранить карточки из ответа модели в колоду. */
fun saveCardsFrom(ctx: Context, text: String, deck: String): Int {
    val list = parseCards(text)
    if (list.isEmpty()) { SnackHost.show(ctx, "Не нашёл карточек в формате «В: … / О: …»"); return 0 }
    val store = ctx.app.stores.cards
    list.forEach { (q, a) -> store.items.add(Card(newId(), deck.take(60), q, a)) }
    store.save()
    SnackHost.show(ctx, "Добавлено ${list.size} карточек в колоду «${deck.take(30)}»")
    return list.size
}

/** Тест по статье: сжать текст, сделать карточки, сохранить колоду. */
suspend fun quizFromText(app: App, title: String, text: String): Int {
    val condensed = if (text.length > app.ai.chunkChars()) app.ai.condense(text) else text
    val out = app.ai.complete(Prompts.build(Mode.CARDS, condensed, app.settings))
    return saveCardsFrom(app, out, title)
}

@Composable
fun CardsTab() {
    val ctx = LocalContext.current
    val store = ctx.app.stores.cards
    var reviewing by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val decks = store.items.groupBy { it.deck }

    var exportDeck by remember { mutableStateOf<String?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(ankiExport(store.items.filter { c -> exportDeck == null || c.deck == exportDeck }).toByteArray()) }
            SnackHost.show(ctx, "Сохранено. В Anki: Файл → Импорт → выбери этот файл")
        }.onFailure { SnackHost.show(ctx, "Не удалось сохранить: ${it.message}") }
    }
    reviewing?.let { deck ->
        Review(deck) { reviewing = null }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val dueAll = store.items.count { it.due <= now }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сегодня к повторению", style = MaterialTheme.typography.titleMedium)
                Text("$dueAll", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("Карточки появляются из режима «Карточки» и из теста по статье в «Прочитать потом». " +
                        "Правильный ответ откладывает карточку на всё больший срок.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dueAll > 0) Button({ reviewing = "" }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Повторить всё") }
                    if (store.items.isNotEmpty()) OutlinedButton({ exportDeck = null; save.launch("НаноПеро-карточки.txt") }) {
                        Icon(Icons.Rounded.IosShare, null); Spacer(Modifier.width(6.dp)); Text("В Anki")
                    }
                }
            }
        }
        if (decks.isEmpty()) Text("Колод пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        decks.forEach { (deck, cards) ->
            val due = cards.count { it.due <= now }
            var confirm by remember { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(deck) },
                    supportingContent = { Text("${cards.size} карточек · к повторению $due") },
                    leadingContent = { Icon(Icons.Rounded.Style, null) },
                    trailingContent = {
                        Row {
                            if (due > 0) FilledTonalIconButton({ reviewing = deck }) { Icon(Icons.Rounded.PlayArrow, "Повторить") }
                            IconButton({ exportDeck = deck; save.launch("$deck.txt") }) { Icon(Icons.Rounded.IosShare, "Экспорт в Anki") }
                            IconButton({ confirm = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                        }
                    }
                )
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                confirmButton = { TextButton({ store.remove { it.deck == deck }; confirm = false }) { Text("Удалить") } },
                dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } },
                title = { Text("Удалить колоду «$deck»?") }
            )
        }
    }
}

@Composable
private fun Review(deck: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val store = ctx.app.stores.cards
    val view = rememberHaptics()
    val queue = remember {
        val now = System.currentTimeMillis()
        store.items.filter { (deck.isEmpty() || it.deck == deck) && it.due <= now }.shuffled().take(50).map { it.id }.toMutableStateList()
    }
    var flipped by remember { mutableStateOf(false) }
    var right by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val rot by animateFloatAsState(if (flipped) 180f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "flip")

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val card = queue.firstOrNull()?.let { id -> store.items.firstOrNull { it.id == id } }
        LinearProgressIndicator(
            progress = { if (total + queue.size == 0) 1f else total.toFloat() / (total + queue.size) },
            Modifier.fillMaxWidth()
        )
        if (card == null) {
            Spacer(Modifier.height(40.dp))
            Icon(Icons.Rounded.Celebration, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Готово!", style = MaterialTheme.typography.headlineMedium)
            Text("Помню: $right из $total", style = MaterialTheme.typography.bodyLarge)
            Button(onDone) { Text("К колодам") }
            return@Column
        }
        ElevatedCard(
            Modifier.fillMaxWidth().weight(1f)
                .graphicsLayer { rotationY = rot; cameraDistance = 14f * density }
                .clickable { view.tick(); flipped = !flipped },
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Box(Modifier.fillMaxSize().padding(24.dp).graphicsLayer { rotationY = if (rot > 90f) 180f else 0f },
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (rot > 90f) "Ответ" else "Вопрос", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(if (rot > 90f) card.a else card.q, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    if (rot <= 90f) Text("нажми, чтобы перевернуть", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    view.tick()
                    store.update({ it.id == card.id }) { it.copy(box = 0, due = System.currentTimeMillis() + 10 * 60_000L) }
                    queue.removeAt(0); queue.add(card.id); total++; flipped = false
                },
                modifier = Modifier.weight(1f).height(56.dp)
            ) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("Не помню") }
            Button(
                onClick = {
                    view.confirm()
                    val nb = (card.box + 1).coerceAtMost(intervalsDays.lastIndex)
                    store.update({ it.id == card.id }) { it.copy(box = nb, due = System.currentTimeMillis() + intervalsDays[nb] * 86_400_000L) }
                    queue.removeAt(0); total++; right++; flipped = false
                },
                modifier = Modifier.weight(1f).height(56.dp)
            ) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Помню") }
        }
    }
}


/** Текстовый формат импорта Anki: вопрос, ответ, колода (НаноПеро::имя). */
fun ankiExport(cards: List<Card>): String = buildString {
    append("#separator:tab\n#html:false\n#notetype:Basic\n#deck column:3\n")
    cards.forEach { c ->
        fun esc(x: String) = x.replace('\t', ' ').replace("\r", "").replace("\n", " ")
        append(esc(c.q)).append('\t').append(esc(c.a)).append('\t').append("НаноПеро::" + esc(c.deck.ifBlank { "Общее" })).append('\n')
    }
}
