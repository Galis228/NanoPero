package com.nanopero.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings as SysSettings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Результаты сводки живут, пока жив процесс — чтобы не терялись при переключении вкладок. */
object Digest {
    var overall by mutableStateOf("")
    val perChat = mutableStateMapOf<String, String>()
    var progress by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var job: Job? = null
}

data class ChatGroup(val key: String, val pkg: String, val app: String, val title: String, val items: List<NotifItem>)

@Composable
fun SummaryTab() {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(sub == 0, { sub = 0 }, SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.Notifications, null, Modifier.size(18.dp)) }) { Text("Уведомления") }
            SegmentedButton(sub == 1, { sub = 1 }, SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.Bookmarks, null, Modifier.size(18.dp)) }) { Text("Прочитать потом") }
        }
        if (sub == 0) NotifDigest() else ReadList()
    }
}

@Composable
private fun NotifDigest() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(NotificationCollector.isEnabled(ctx)) }
    // Перепроверяем разрешение при возврате из настроек
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) enabled = NotificationCollector.isEnabled(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var period by rememberSaveable { mutableIntStateOf(0) }
    val since = when (period) {
        0 -> Reminder.startOfToday()
        1 -> System.currentTimeMillis() - 86_400_000L
        else -> System.currentTimeMillis() - 3 * 86_400_000L
    }
    val all = app.stores.notifs.items
    val groups = remember(all.size, since) {
        all.filter { it.time >= since }
            .groupBy { NotificationCollector.chatKey(it.pkg, it.title) }
            .map { (k, v) -> ChatGroup(k, v.first().pkg, v.first().app, v.first().title, v.sortedBy { it.time }) }
            .sortedByDescending { g -> g.items.maxOf { it.time } }
    }
    // Фильтр и сортировка по приложениям
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var byApp by rememberSaveable { mutableStateOf(true) }
    val apps = remember(groups) {
        groups.groupBy { it.pkg }.map { (pkg, gs) -> Triple(pkg, gs.first().app, gs.sumOf { it.items.size }) }.sortedByDescending { it.third }
    }
    val shownGroups = groups.filter { appFilter == null || it.pkg == appFilter }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Собирать сообщения", style = MaterialTheme.typography.titleSmall)
                    Text(if (app.settings.digestEnabled) "Сводка включена" else "Выключено — новые сообщения не сохраняются",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(app.settings.digestEnabled, { app.settings.digestEnabled = it })
            }
        }
        if (!enabled) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нужен доступ к уведомлениям", style = MaterialTheme.typography.titleMedium)
                    Text("НаноПеро будет сохранять текст сообщений из мессенджеров только на телефоне, " +
                            "чтобы вечером сделать сводку. Ничего не уходит в интернет.")
                    Text("Если переключатель серый («Ограниченная настройка»): О приложении → ⋮ → " +
                            "«Разрешить ограниченные настройки», потом вернись сюда.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Открыть настройки") }
                        OutlinedButton({
                            ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("О приложении") }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Сегодня", "24 часа", "3 дня").forEachIndexed { i, t ->
                    FilterChip(period == i, { period = i }, { Text(t) })
                }
                Spacer(Modifier.weight(1f))
                Text("${groups.sumOf { it.items.size }} сообщ.", style = MaterialTheme.typography.labelMedium)
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Главное", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (Digest.busy) {
                            NanoLoader(20.dp)
                            TextButton({ Digest.job?.cancel(); Digest.busy = false; app.ai.local.cancelActive() }) { Text("Стоп") }
                        } else {
                            Button(
                                onClick = { Digest.job = scope.launch { runDigest(app, shownGroups) } },
                                enabled = groups.isNotEmpty()
                            ) { Text(if (Digest.overall.isBlank()) "Сделать сводку" else "Обновить") }
                        }
                    }
                    if (Digest.progress.isNotBlank() && Digest.busy) Text(Digest.progress, style = MaterialTheme.typography.bodySmall)
                    Digest.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (Digest.overall.isNotBlank()) SelectionContainer { Text(Digest.overall, style = MaterialTheme.typography.bodyLarge) }
                    else if (!Digest.busy) Text(
                        if (groups.isEmpty()) "Пока нет сообщений за этот период." else
                            "${groups.size} чатов. Сводка делается на телефоне и может занять пару минут.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (apps.size > 1) item {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(appFilter == null, { appFilter = null }, { Text("Все · ${groups.sumOf { it.items.size }}") })
                }
                items(apps, key = { it.first }) { (pkg, name, n) ->
                    FilterChip(appFilter == pkg, { appFilter = if (appFilter == pkg) null else pkg }, { Text("$name · $n") },
                        leadingIcon = { AppIcon(pkg) })
                }
            }
        }
        if (appFilter == null && apps.size > 1) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Группировать по приложениям", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(byApp, { byApp = it })
            }
        }
        if (appFilter == null && byApp && apps.size > 1) {
            apps.forEach { (pkg, name, n) ->
                item(key = "h_$pkg") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, 22.dp); Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text("$n сообщ.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(groups.filter { it.pkg == pkg }, key = { it.key }) { g -> Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = androidx.compose.animation.core.spring(stiffness = 500f), fadeOutSpec = androidx.compose.animation.core.tween(160))) { ChatCard(g) }}
            }
        } else items(shownGroups, key = { it.key }) { g -> Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = androidx.compose.animation.core.spring(stiffness = 500f), fadeOutSpec = androidx.compose.animation.core.tween(160))) { ChatCard(g) }}

        if (all.isNotEmpty()) item {
            var confirm by remember { mutableStateOf(false) }
            TextButton({ confirm = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text("Удалить все сохранённые сообщения")
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Удалить все сообщения?") },
                text = { Text("Сводка очистится. Новые сообщения будут собираться дальше.") },
                confirmButton = { TextButton({ app.stores.notifs.clear(); Digest.overall = ""; Digest.perChat.clear(); confirm = false }) { Text("Удалить") } },
                dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } }
            )
        }
    }
}

private suspend fun runDigest(app: App, groups: List<ChatGroup>) {
    Digest.busy = true; Digest.error = null; Digest.overall = ""; Digest.perChat.clear()
    try {
        val max = app.ai.chunkChars()
        val top = groups.sortedByDescending { it.items.size }.take(12)
        val parts = mutableListOf<String>()
        top.forEachIndexed { i, g ->
            Digest.progress = "Читаю чат ${i + 1} из ${top.size}: ${g.title}"
            val lines = g.items.joinToString("\n") { it.text }
            val sum = if (g.items.size == 1 && lines.length < 160) "• $lines"
            else app.ai.complete(Prompts.chat(g.app, g.title, lines.takeLast(max)))
            Digest.perChat[g.key] = sum
            parts += "${g.app} · ${g.title}:\n$sum"
        }
        Digest.progress = "Собираю главное за день…"
        var joined = parts.joinToString("\n\n")
        if (joined.length > max) joined = app.ai.condense(joined) { a, b -> Digest.progress = "Сжимаю: часть $a из $b" }
        Digest.overall = app.ai.complete(Prompts.day(joined))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ErrorLog.add("Сводка", e)
        Digest.error = e.message
    } finally {
        Digest.busy = false; Digest.progress = ""
    }
}

@Composable
private fun ChatCard(g: ChatGroup) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var variants by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val canReply = NotificationCollector.replies.containsKey(g.key)

    OutlinedCard(Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.app} · ${g.items.size} сообщ. · ${fmtTime(g.items.last().time)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Удалить этот чат") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = {
                            menu = false
                            val removed = g.items.toList()
                            app.stores.notifs.remove { it.pkg == g.pkg && it.title == g.title }
                            Digest.perChat.remove(g.key)
                            SnackHost.undo("Чат «${g.title.take(24)}» удалён") { app.stores.notifs.items.addAll(removed); app.stores.notifs.items.sortByDescending { it.time }; app.stores.notifs.save() }
                        })
                        DropdownMenuItem({ Text("Удалить всё из ${g.app}") }, leadingIcon = { Icon(Icons.Rounded.Apps, null) }, onClick = {
                            menu = false
                            val removed = app.stores.notifs.items.filter { it.pkg == g.pkg }
                            app.stores.notifs.remove { it.pkg == g.pkg }
                            SnackHost.undo("Удалено ${removed.size} сообщ. из ${g.app}") { app.stores.notifs.items.addAll(removed); app.stores.notifs.items.sortByDescending { it.time }; app.stores.notifs.save() }
                        })
                        DropdownMenuItem({ Text("Не собирать из этого чата") }, leadingIcon = { Icon(Icons.Rounded.NotificationsOff, null) }, onClick = {
                            menu = false
                            app.settings.mute(g.key)
                            val removed = g.items.toList()
                            app.stores.notifs.remove { it.pkg == g.pkg && it.title == g.title }
                            SnackHost.undo("«${g.title.take(24)}» больше не попадёт в сводку") {
                                app.settings.unmute(g.key); app.stores.notifs.items.addAll(removed); app.stores.notifs.items.sortByDescending { it.time }; app.stores.notifs.save()
                            }
                        })
                    }
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            Digest.perChat[g.key]?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (open) {
                HorizontalDivider()
                g.items.takeLast(15).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmtTime(m.time)}  ${m.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton({
                            app.stores.notifs.remove { it.id == m.id }
                            SnackHost.undo("Сообщение удалено") { app.stores.notifs.items.add(m); app.stores.notifs.items.sortByDescending { it.time }; app.stores.notifs.save() }
                        }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Удалить сообщение", Modifier.size(16.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true; err = null
                            try {
                                val last = g.items.takeLast(6).joinToString("\n") { it.text }
                                val out = app.ai.complete(Prompts.build(Mode.REPLY, last, app.settings))
                                variants = out.lines().map { it.trim() }.filter { it.isNotBlank() }
                                    .map { it.replace(Regex("^\\d[).]\\s*"), "") }.take(3)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e
                            } catch (e: Throwable) { err = e.message } finally { busy = false }
                        }
                    }) {
                        if (busy) NanoLoader(18.dp)
                        else Icon(Icons.Rounded.Reply, null)
                        Spacer(Modifier.width(6.dp)); Text("Варианты ответа")
                    }
                    OutlinedButton({
                        ctx.packageManager.getLaunchIntentForPackage(g.pkg)?.let { ctx.startActivity(it) }
                    }) { Text("Открыть") }
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                variants.forEach { v ->
                    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(v, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            IconButton({ ctx.copyText(v) }) { Icon(Icons.Rounded.ContentCopy, "Копировать") }
                            if (canReply) IconButton({
                                val ok = NotificationCollector.sendReply(ctx, g.key, v)
                                SnackHost.show(ctx, if (ok) "Отправлено" else "Не получилось — скопируй и ответь вручную")
                            }) { Icon(Icons.Rounded.Send, "Отправить", tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
                if (variants.isNotEmpty() && !canReply)
                    Text("Отправка напрямую доступна, пока уведомление этого чата не смахнули.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReadList() {
    val ctx = LocalContext.current
    val app = ctx.app
    val items = app.stores.reads.items
    var url by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Ссылка или текст") })
                Spacer(Modifier.width(8.dp))
                FilledIconButton({ if (url.isNotBlank()) { ReadLater.add(ctx, url, null); url = "" } }) {
                    Icon(Icons.Rounded.Add, "Добавить")
                }
            }
            Text("Или в браузере: Поделиться → НаноПеро → «Отложить».",
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (items.isEmpty()) item {
            Text("Список пуст.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(items.toList(), key = { it.id }) { r -> Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = androidx.compose.animation.core.spring(stiffness = 500f), fadeOutSpec = androidx.compose.animation.core.tween(160))) { ReadCard(r) }}
    }
}

@Composable
private fun ReadCard(r: ReadItem) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val host = runCatching { Uri.parse(r.url).host }.getOrNull()
                    Text(listOfNotNull(host, fmtTime(r.time), if (r.text.isNotBlank()) "${r.text.length / 1000} тыс. симв." else null)
                        .joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ app.stores.reads.remove { it.id == r.id } }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
            }
            if (r.error.isNotBlank()) Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val summary = live.ifBlank { r.summary }
            if (summary.isNotBlank()) SelectionContainer { Text(summary, style = MaterialTheme.typography.bodyMedium) }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.text.isNotBlank()) FilledTonalButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; err = null; live = ""
                        try {
                            val (_, f) = app.ai.run(Mode.SUMMARY, r.text)
                            f.collect { live = it }
                            val done = live.trim()
                            app.stores.reads.update({ it.id == r.id }) { it.copy(summary = done) }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Throwable) { err = e.message } finally { busy = false; live = "" }
                    }
                }) {
                    if (busy) NanoLoader(18.dp) else Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.width(6.dp)); Text(if (r.summary.isBlank()) "Выжимка" else "Заново")
                }
                if (r.text.isNotBlank()) OutlinedButton({ full = !full }) { Text(if (full) "Свернуть" else "Читать") }
                if (r.text.isNotBlank()) OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; err = null
                        try { quizFromText(app, r.title, r.text) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Throwable) { err = e.message } finally { busy = false }
                    }
                }) { Text("Тест") }
                if (r.url.isNotBlank() && r.text.isBlank()) OutlinedButton({ ReadLater.refetch(ctx, r.id, r.url) }) { Text("Загрузить") }
                if (r.url.isNotBlank()) IconButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }) {
                    Icon(Icons.Rounded.OpenInBrowser, "Открыть в браузере")
                }
            }
            if (full) SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Значок приложения-мессенджера (из системы). */
@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 18.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b); d.setBounds(0, 0, 64, 64); d.draw(c)
            b.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.size(size))
    else Icon(Icons.Rounded.Notifications, null, Modifier.size(size))
}
