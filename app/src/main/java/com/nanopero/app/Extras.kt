package com.nanopero.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Замок по отпечатку ----------

object AppLock {
    val locked = mutableStateOf(false)
    private var hiddenAt = 0L
    private var prompting = false

    fun init(app: App) { locked.value = app.settings.lockEnabled }

    fun onHidden() { hiddenAt = System.currentTimeMillis() }

    fun onShown(app: App) {
        if (app.settings.lockEnabled && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 30_000) locked.value = true
    }

    fun available(ctx: android.content.Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(act: FragmentActivity, onOk: () -> Unit = {}) {
        if (prompting) return
        if (!available(act)) { locked.value = false; onOk(); return }
        prompting = true
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false; locked.value = false; onOk()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { prompting = false }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("НаноПеро")
                .setSubtitle("Дневник и сводка защищены")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Rounded.Lock, null, Modifier.padding(24.dp).size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(24.dp))
            Text("НаноПеро заблокирован", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Подтверди, что это ты", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Button(onUnlock, Modifier.height(56.dp)) {
                Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Разблокировать")
            }
        }
    }
}

// ---------- Резервная копия ----------

@Composable
fun BackupTab() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                val data = app.stores.exportJson()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data.toByteArray()) } }
                "Резервная копия сохранена ✓"
            }.getOrElse { "Ошибка: ${it.message}" }
        }
    }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                val data = app.stores.diaryMarkdown()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data.toByteArray()) } }
                "Дневник сохранён в Markdown ✓"
            }.getOrElse { "Ошибка: ${it.message}" }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                val n = app.stores.importJson(text)
                "Импортировано записей: $n"
            }.getOrElse { "Не похоже на копию НаноПеро: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Все данные хранятся только на телефоне. Сделай копию перед переустановкой или сменой телефона.",
            style = MaterialTheme.typography.bodyMedium)
        BackupAction(Icons.Rounded.DataObject, "Полная копия (JSON)", "Дневник, история, статьи, пресеты, карточки") {
            saveJson.launch("nanopero-$stamp.json")
        }
        BackupAction(Icons.Rounded.Description, "Дневник в Markdown", "Читается в Obsidian, Notion, любом редакторе") {
            saveMd.launch("dnevnik-$stamp.md")
        }
        BackupAction(Icons.Rounded.Restore, "Восстановить из копии", "Добавит записи, которых ещё нет") {
            open.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Text("Сводка уведомлений и чаты в копию не входят — это временные данные.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BackupAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        ListItem(
            leadingContent = {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            },
            headlineContent = { Text(title) }, supportingContent = { Text(sub) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

// ---------- Итоги недели ----------

suspend fun weeklyRecap(app: App): String {
    val since = System.currentTimeMillis() - 7 * 86_400_000L
    val entries = app.stores.diary.items.filter { it.time >= since }.sortedBy { it.time }
    if (entries.isEmpty()) return "За последние 7 дней заметок нет."
    val f = SimpleDateFormat("EEE d MMM", Locale("ru"))
    var text = entries.joinToString("\n") { e ->
        "[${f.format(Date(e.time))}] ${e.title}: ${e.summary.ifBlank { e.text.take(300) }}" +
                if (e.tasks.isNotEmpty()) " Задачи: " + e.tasks.joinToString("; ") { (if (it.done) "✓ " else "☐ ") + it.text } else ""
    }
    if (text.length > app.ai.chunkChars()) text = app.ai.condense(text)
    return app.ai.complete(Prompts.week(text))
}

@Composable
fun WeeklyRecapCard() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarViewWeek, null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text("Итоги недели", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (busy) NanoLoader(22.dp)
                else FilledTonalButton({
                    scope.launch {
                        busy = true; err = null
                        try { result = weeklyRecap(app) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Throwable) { err = e.message } finally { busy = false }
                    }
                }) { Text(if (result.isBlank()) "Собрать" else "Заново") }
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (result.isNotBlank()) SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Задача из дневника → событие в календаре (модель вытаскивает дату). */
suspend fun taskToCalendar(app: App, task: String, entryTime: Long) {
    val ctx = "Заметка от ${SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(entryTime))}. Задача: $task"
    val raw = app.ai.complete(Prompts.build(Mode.EVENT, ctx, app.settings), temperature = 0.1f)
    val ev = CalendarHelper.parse(raw) ?: CalendarHelper.Ev(task, "", "", 30, "")
    CalendarHelper.open(app, ev.copy(title = ev.title.ifBlank { task }))
}
