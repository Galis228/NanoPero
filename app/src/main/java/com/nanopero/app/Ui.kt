package com.nanopero.app

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Состояние одного запуска нейросети. */
class Runner(private val router: AiRouter, private val scope: kotlinx.coroutines.CoroutineScope) {
    var result by mutableStateOf("")
    var engine by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var mode by mutableStateOf<Mode?>(null)
    var millis by mutableLongStateOf(0L)
    var lastText = ""
        private set
    private var job: Job? = null

    var preset by mutableStateOf<Preset?>(null)

    fun run(m: Mode, text: String, p: Preset? = null) {
        if (text.isBlank()) { error = "Нет текста"; return }
        job?.cancel()
        lastText = text
        mode = m; preset = p; result = ""; error = null; busy = true; engine = ""
        if (m != Mode.CUSTOM) router.settings.lastMode = m.name
        val t0 = System.currentTimeMillis()
        job = scope.launch {
            try {
                val (label, flow) = router.run(m, text, p)
                engine = label
                flow.collect { result = it }
                result = result.trim()
                if (result.isBlank()) error = "Модель вернула пустой ответ"
                else onDone?.invoke(this@Runner)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ErrorLog.add("Режим ${m.title}", e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
                millis = System.currentTimeMillis() - t0
            }
        }
    }

    var onDone: ((Runner) -> Unit)? = null
    val title get() = if (mode == Mode.CUSTOM) preset?.name ?: "Пресет" else mode?.title ?: ""

    fun stop() { job?.cancel(); busy = false }
}

@Composable
fun rememberRunner(): Runner {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        Runner(ctx.app.ai, scope).apply {
            onDone = { r ->
                val app = ctx.app
                if (app.settings.historyEnabled)
                    app.stores.history.add(HistoryItem(newId(), System.currentTimeMillis(), r.title,
                        r.lastText.take(4000), r.result.take(8000), r.engine))
            }
        }
    }
}

@Composable
fun ModeChips(r: Runner, enabled: Boolean, onMode: (Mode) -> Unit, onPreset: (Preset) -> Unit) {
    val presets = LocalContext.current.app.stores.presets.items
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(Mode.entries.filter { it.inChips }) { m ->
            FilterChip(
                selected = m == r.mode, enabled = enabled, onClick = { onMode(m) },
                label = { Text(m.title) },
                leadingIcon = { Icon(m.icon, null, Modifier.size(18.dp)) }
            )
        }
        items(presets.toList(), key = { it.id }) { p ->
            FilterChip(
                selected = r.mode == Mode.CUSTOM && r.preset?.id == p.id, enabled = enabled, onClick = { onPreset(p) },
                label = { Text(p.name) },
                leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) }
            )
        }
    }
}

@Composable
fun ResultCard(
    r: Runner,
    onReplace: ((String) -> Unit)? = null,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSaveCards: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val visible = r.busy || r.result.isNotBlank() || r.error != null
    AnimatedVisibility(visible) {
        ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(r.mode?.icon ?: Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (r.busy) {
                        NanoLoader(20.dp)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { r.stop() }) { Text("Стоп") }
                    } else if (r.engine.isNotBlank()) {
                        AssistChip(onClick = {}, label = {
                            Text("${r.engine} · ${"%.1f".format(r.millis / 1000f)} с", style = MaterialTheme.typography.labelSmall)
                        }, leadingIcon = { Icon(Icons.Rounded.WifiOff, null, Modifier.size(14.dp)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                r.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                val isEvent = r.mode == Mode.EVENT
                val event = remember(r.result, r.busy, isEvent) { if (isEvent && !r.busy) CalendarHelper.parse(r.result) else null }
                val shown = when {
                    event != null -> CalendarHelper.pretty(event)
                    r.busy && r.result.isBlank() -> "Думаю…"
                    else -> r.result
                }
                if (shown.isNotBlank()) SelectionContainer {
                    Text(shown, style = MaterialTheme.typography.bodyLarge)
                }
                if (!r.busy && r.result.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (event != null) {
                            Button(onClick = { CalendarHelper.open(ctx, event) }) {
                                Icon(Icons.Rounded.EventAvailable, null); Spacer(Modifier.width(6.dp)); Text("В календарь")
                            }
                        } else if (r.mode == Mode.CARDS && onSaveCards != null) {
                            Button(onClick = { onSaveCards(r.result) }) {
                                Icon(Icons.Rounded.Style, null); Spacer(Modifier.width(6.dp)); Text("В колоду")
                            }
                        } else if (onReplace != null) {
                            Button(onClick = { onReplace(r.result) }) {
                                Icon(Icons.Rounded.Done, null); Spacer(Modifier.width(6.dp)); Text("Заменить")
                            }
                        }
                        FilledTonalIconButton(onClick = { onCopy(if (event != null) shown else r.result) }) {
                            Icon(Icons.Rounded.ContentCopy, "Копировать")
                        }
                        FilledTonalIconButton(onClick = { onShare(if (event != null) shown else r.result) }) {
                            Icon(Icons.Rounded.Share, "Поделиться")
                        }
                        r.mode?.let { m ->
                            FilledTonalIconButton(onClick = { r.run(m, r.lastText, r.preset) }) {
                                Icon(Icons.Rounded.Refresh, "Ещё вариант")
                            }
                        }
                    }
                }
            }
        }
    }
}
