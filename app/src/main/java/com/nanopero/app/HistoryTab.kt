package com.nanopero.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun HistoryTab(onReuse: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val items = app.stores.history.items
    var q by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val shown = if (q.isBlank()) items.toList()
    else items.filter { it.input.contains(q, true) || it.output.contains(q, true) || it.mode.contains(q, true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Поиск по истории") })
        }
        if (!app.settings.historyEnabled) item {
            Text("История выключена в настройках — новые результаты не сохраняются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isEmpty()) item { Text("Здесь будут все результаты.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.id }) { h -> Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = androidx.compose.animation.core.spring(stiffness = 500f), fadeOutSpec = androidx.compose.animation.core.tween(160))) {
            var open by remember { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.mode, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(fmtTime(h.time), style = MaterialTheme.typography.labelSmall)
                    }
                    if (open) {
                        Text("Было:", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(h.input, style = MaterialTheme.typography.bodySmall) }
                        Text("Стало (${h.engine}):", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(h.output, style = MaterialTheme.typography.bodyMedium) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ ctx.copyText(h.output) }) { Text("Копировать") }
                            TextButton({ onReuse(h.output) }) { Text("В чат") }
                            Spacer(Modifier.weight(1f))
                            IconButton({ app.stores.history.remove { it.id == h.id } }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                        }
                    } else {
                        Text(h.output, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }}
        if (items.isNotEmpty()) item {
            TextButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("Очистить историю") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton({ app.stores.history.clear(); confirm = false }) { Text("Очистить") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } },
        title = { Text("Удалить всю историю?") }
    )
}
