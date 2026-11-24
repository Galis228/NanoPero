package com.nanopero.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Строка поиска по настройкам; пункты, не подходящие под запрос, скрываются. */
val LocalSetQuery = compositionLocalOf { "" }
private val LocalSetSink = compositionLocalOf<MutableMap<String, Boolean>?> { null }

private fun matches(q: String, vararg t: String) = q.isBlank() || t.any { it.contains(q.trim(), ignoreCase = true) }

@Composable
private fun visible(vararg t: String): Boolean {
    val q = LocalSetQuery.current
    val ok = matches(q, *t)
    val sink = LocalSetSink.current
    val key = t.firstOrNull() ?: ""
    SideEffect { sink?.put(key, ok) }
    return ok
}

@Composable
fun SetSearch(q: String, onQ: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(q, onQ, modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
        placeholder = { Text("Поиск по настройкам") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (q.isNotEmpty()) IconButton({ onQ("") }) { Icon(Icons.Rounded.Close, "Очистить") } })
}

/** Быстрые переходы к разделам (чипы). */
@Composable
fun SetSectionChips(names: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(names) { n -> FilterChip(selected == n, { onSelect(if (selected == n) null else n) }, { Text(n) }) }
    }
}

/** Раздел-карточка. Сам прячется, если ни один пункт не подходит под поиск или выбран другой раздел. */
@Composable
fun SetSection(title: String, only: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val q = LocalSetQuery.current
    val hits = remember { mutableStateMapOf<String, Boolean>() }
    val hide = (only != null && only != title)
    if (hide) return
    val titleHit = matches(q, title) && q.isNotBlank()
    val show = q.isBlank() || titleHit || hits.values.any { it }
    CompositionLocalProvider(LocalSetSink provides hits, LocalSetQuery provides if (titleHit) "" else q) {
        if (show) ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                content()
            }
        } else Column { content() } // невидимые пункты всё равно сообщают о совпадениях
    }
}

@Composable
fun SetSwitch(icon: ImageVector, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    if (!visible(title, sub)) return
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Switch(value, onChange)
    }
}

/** Компактный ползунок: название и значение в одной строке, сам ползунок под ним, без лишних отступов. */
@Composable
fun SetSlider(icon: ImageVector, title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0,
              onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    if (!visible(title)) return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(v, onChange, valueRange = range, steps = steps, onValueChangeFinished = onFinished,
            modifier = Modifier.padding(start = 30.dp).height(32.dp))
    }
}

@Composable
fun <T> SetChoice(icon: ImageVector, title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    if (!visible(title, *options.map { it.second }.toTypedArray())) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        LazyRow(Modifier.padding(start = 34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { (v, label) -> FilterChip(v == selected, { onSelect(v) }, { Text(label) }) }
        }
    }
}

@Composable
fun SetLink(icon: ImageVector, title: String, sub: String = "", onClick: () -> Unit) {
    if (!visible(title, sub)) return
    ListItem(
        leadingContent = { Icon(icon, null, Modifier.size(20.dp)) },
        headlineContent = { Text(title) },
        supportingContent = if (sub.isNotBlank()) ({ Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis) }) else null,
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SetText(text: String) {
    if (!visible(text)) return
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SetHint(icon: ImageVector, gesture: String, result: String) {
    if (!visible(gesture, result)) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(gesture, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(result, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Произвольный пункт, участвующий в поиске. */
@Composable
fun SetCustom(vararg keys: String, content: @Composable () -> Unit) {
    if (!visible(*keys)) return
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
}

