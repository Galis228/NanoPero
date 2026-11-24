package com.nanopero.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

object GboardImport {
    const val GBOARD = "com.google.android.inputmethod.latin"

    /** Прочитать файл словаря: .zip от Gboard (внутри dictionary.txt) или сам .txt. */
    fun readText(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".txt", true)) return z.readBytes().decodeToString()
                    e = z.nextEntry
                }
            }
            throw IllegalArgumentException("В архиве нет файла словаря")
        }
        return bytes.decodeToString()
    }

    fun import(ctx: Context, uri: Uri): UserDict.ImportResult {
        val text = readText(ctx, uri)
        if (!text.lineSequence().take(3).any { it.startsWith("# Gboard Dictionary") } && !text.contains('\t'))
            throw IllegalArgumentException("Это не похоже на словарь Gboard")
        return ctx.app.userDict.importGboard(text)
    }

    fun gboardInstalled(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(GBOARD, 0); true }.getOrDefault(false)

    /** Открыть настройки Gboard напрямую (через описание клавиатуры в системе). */
    fun openGboardSettings(ctx: Context): Boolean {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        val info = imm.inputMethodList.firstOrNull { it.packageName == GBOARD }
        val act = info?.settingsActivity
        val intent = if (act != null) Intent().setClassName(GBOARD, act)
        else ctx.packageManager.getLaunchIntentForPackage(GBOARD)
        return runCatching { ctx.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Раз в сутки забираем слова из системного словаря Android (работает, когда НаноПеро — текущая клавиатура). */
    fun autoSyncSystem(ctx: Context) {
        val p = ctx.getSharedPreferences("dictsync", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("t", 0) < 86_400_000L) return
        val n = ctx.app.userDict.syncSystem(ctx)
        p.edit().putLong("t", System.currentTimeMillis()).putInt("n", n).apply()
    }
}

/** Принимает файл словаря из «Поделиться» (Gboard → Экспорт) и импортирует автоматически. */
class DictImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        val uri: Uri? = intent?.getParcelableExtra(Intent.EXTRA_STREAM) ?: intent?.data
        setContent {
            NanoTheme {
                var result by remember { mutableStateOf<String?>(null) }
                var ok by remember { mutableStateOf(false) }
                LaunchedEffect(uri) {
                    result = if (uri == null) "Файл не получен" else runCatching {
                        val r = withContext(Dispatchers.IO) { GboardImport.import(this@DictImportActivity, uri) }
                        ok = true
                        "Добавлено слов: ${r.words}\nСокращений: ${r.shortcuts}" + if (r.skipped > 0) "\nПропущено: ${r.skipped}" else ""
                    }.getOrElse { it.message ?: "Не удалось прочитать файл" }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        if (result == null) NanoLoaderContained(64.dp)
                        else Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(64.dp),
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text(if (result == null) "Импортирую словарь…" else if (ok) "Словарь перенесён" else "Не получилось",
                            style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(result.orEmpty(), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        if (result != null) Button({ finish() }) { Text("Готово") }
                    }
                }
            }
        }
    }
}

/** Экран переноса словаря из Gboard. */
@Composable
fun GboardMigration(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var sysCount by remember { mutableStateOf<Int?>(null) }
    val enabled = remember { isImeEnabled(ctx) }
    LaunchedEffect(Unit) { sysCount = withContext(Dispatchers.IO) { ctx.app.userDict.syncSystem(ctx) } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                val r = withContext(Dispatchers.IO) { GboardImport.import(ctx, uri) }
                "Готово ✓ Слов: ${r.words}, сокращений: ${r.shortcuts}"
            }.getOrElse { it.message ?: "Ошибка" }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(ctx.app.userDict.exportGboard().toByteArray()) } }
                "Сохранено. В Gboard: Словарь → Личный словарь → ⋮ → Импорт"
            }.getOrElse { it.message ?: "Ошибка" }
        }
    }
    androidx.activity.compose.BackHandler { onBack() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Перенос из Gboard", style = MaterialTheme.typography.titleLarge)
        }
        // 1. Системный словарь — автоматически
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Sync, null) },
                headlineContent = { Text("Системный словарь Android") },
                supportingContent = {
                    Text(when {
                        sysCount == null -> "Проверяю…"
                        sysCount!! > 0 -> "Перенесено автоматически: $sysCount слов. Новые слова будут подтягиваться сами раз в день."
                        !enabled -> "Станет доступен, когда НаноПеро будет выбрана клавиатурой — дальше перенос пойдёт сам."
                        else -> "Пусто или недоступен — переходи к шагу ниже."
                    })
                }
            )
        }
        // 2. Личный словарь Gboard — один экспорт, дальше всё само
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Личный словарь Gboard", style = MaterialTheme.typography.titleMedium)
                Text("Google не даёт другим приложениям читать его напрямую, поэтому нужен один экспорт. " +
                        "Я открою нужный экран — останется нажать «Экспорт» и выбрать НаноПеро в списке «Поделиться». " +
                        "Импорт произойдёт сам.", style = MaterialTheme.typography.bodyMedium)
                Text("В Gboard: Словарь → Личный словарь → Все языки → ⋮ → Экспорт → НаноПеро",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (GboardImport.gboardInstalled(ctx)) Button({
                    if (!GboardImport.openGboardSettings(ctx)) status = "Не удалось открыть Gboard — открой его настройки вручную"
                }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Открыть словарь Gboard") }
                else Text("Gboard на телефоне не найден.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/plain", "*/*")) },
                    Modifier.fillMaxWidth()) { Text("Уже есть файл? Выбрать dictionary.zip") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        TextButton({ save.launch("dictionary.txt") }) { Icon(Icons.Rounded.Upload, null); Spacer(Modifier.width(6.dp)); Text("Экспорт моего словаря для Gboard") }
        Text("Слова из Gboard сразу считаются «своими»: появляются в подсказках и не исправляются. " +
                "Сокращения (например «спс» → «спасибо») показываются в центре строки подсказок.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
