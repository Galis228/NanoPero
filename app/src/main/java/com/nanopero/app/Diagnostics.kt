package com.nanopero.app

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.compose.setContent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Журнал ошибок ----------

object ErrorLog {
    private lateinit var file: File
    private lateinit var crashFlag: File
    var hadCrash by mutableStateOf(false)

    fun init(app: App) {
        file = File(app.filesDir, "errors.log")
        crashFlag = File(app.filesDir, "crashed.flag")
        hadCrash = crashFlag.exists()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("СБОЙ", e, "поток ${t.name}"); crashFlag.writeText("1") }
            prev?.uncaughtException(t, e)
        }
    }

    fun ackCrash() { hadCrash = false; runCatching { crashFlag.delete() } }

    @Synchronized private fun write(tag: String, e: Throwable?, msg: String) {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        val trace = e?.stackTraceToString()?.lines()?.take(25)?.joinToString("\n").orEmpty()
        file.appendText("[$time] $tag: $msg ${e?.let { "— ${it.javaClass.simpleName}: ${it.message}" } ?: ""}\n$trace\n\n")
        if (file.length() > 200_000) file.writeText(file.readText().takeLast(120_000))
    }

    /** Ошибка, которую приложение поймало и показало пользователю. */
    fun add(where: String, e: Throwable) { if (::file.isInitialized) runCatching { write(where, e, "") } }

    fun read(): String = if (::file.isInitialized && file.exists()) file.readText() else ""
    fun clear() { if (::file.isInitialized) file.delete() }

    fun report(ctx: Context): String = buildString {
        val s = ctx.app.settings
        append("НаноПеро ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Модель: ${s.activeModel.ifBlank { "—" }}, движок: ${s.engine}, GPU: ${s.useGpu}, контекст: ${s.maxTokens}\n")
        val rt = Runtime.getRuntime()
        append("Память приложения: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} МБ\n\n")
        append(read().takeLast(30_000).ifBlank { "Ошибок нет." })
    }
}

@Composable
fun ErrorLogTab() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(ErrorLog.read()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Если что-то пошло не так — нажми «Скопировать отчёт» и отправь мне в чат. " +
                "В отчёте только техническая информация: версия, телефон, модель и тексты ошибок — без твоих сообщений.",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ ctx.copyText(ErrorLog.report(ctx)); SnackHost.show(ctx, "Отчёт скопирован") }) {
                Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Скопировать отчёт")
            }
            OutlinedButton({ ctx.shareText(ErrorLog.report(ctx)) }) { Icon(Icons.Rounded.Share, null) }
            TextButton({ ErrorLog.clear(); ErrorLog.ackCrash(); text = "" }) { Text("Очистить") }
        }
        Surface(Modifier.fillMaxWidth().weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer {
                    Text(text.ifBlank { "Ошибок нет 🎉" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------- Тест скорости модели ----------

data class SpeedResult(val label: String, val charsPerSec: Float?, val error: String? = null)

/** Генерирует один и тот же текст на CPU и GPU, считает скорость после первого символа. */
suspend fun speedTest(app: App, onStage: (String) -> Unit = {}, onResult: (SpeedResult) -> Unit = {}, onProgress: (String) -> Unit): List<SpeedResult> {
    val s = app.settings
    val wasGpu = s.useGpu
    val prompt = "Напиши 6 коротких предложений о море и маяке."
    val out = ArrayList<SpeedResult>()
    for (gpu in listOf(false, true)) {
        val label = if (gpu) "GPU" else "CPU"
        onStage(label)
        onProgress("Загружаю модель на $label…")
        s.useGpu = gpu
        try {
            var first = 0L; var chars = 0; var last = 0L
            withTimeoutOrNull(45_000) {
                app.ai.stream(prompt, temperature = 0.3f).second.collect { t ->
                    val now = System.currentTimeMillis()
                    if (first == 0L && t.isNotEmpty()) { first = now; onProgress("Генерирую на $label…") }
                    chars = t.length; last = now
                    if (first > 0 && now - first > 8_000) app.ai.local.cancelActive()
                }
            }
            val sec = (last - first) / 1000f
            val r = if (first == 0L || sec <= 0.2f) SpeedResult(label, null, "не запустилась") else SpeedResult(label, chars / sec)
            out += r; onResult(r)
        } catch (e: kotlinx.coroutines.CancellationException) { s.useGpu = wasGpu; throw e
        } catch (e: Throwable) {
            ErrorLog.add("Тест скорости $label", e)
            val r = SpeedResult(label, null, e.message?.take(80) ?: "ошибка")
            out += r; onResult(r)
        }
        // Ждём полной остановки генерации перед выгрузкой (release ждёт освобождения модели)
        app.ai.local.release()
    }
    s.useGpu = wasGpu
    return out
}

/**
 * Тест скорости в отдельном процессе «:bench»: если движок упадёт на GPU (это нативный сбой,
 * его нельзя перехватить), умрёт только этот процесс, а приложение покажет «GPU не поддерживается».
 */
class BenchActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "bench.json")
        file.delete()
        val obj = org.json.JSONObject()
        fun save() = runCatching { file.writeText(obj.toString()) }
        setContent {
            NanoTheme {
                var stage by remember { mutableStateOf("Готовлюсь…") }
                LaunchedEffect(Unit) {
                    try {
                        speedTest(app,
                            onStage = { obj.put("stage", it); save() },
                            onResult = { r -> obj.put(r.label, r.charsPerSec?.toDouble() ?: -1.0); r.error?.let { obj.put(r.label + "_err", it) }; save() },
                            onProgress = { stage = it })
                        obj.put("done", true); save()
                    } catch (e: Throwable) { obj.put("error", e.message ?: "ошибка"); save() }
                    setResult(RESULT_OK); finish()
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        NanoLoaderContained(72.dp)
                        Spacer(Modifier.height(20.dp))
                        Text("Тест скорости", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(stage, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("Не закрывай экран — около минуты", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** История тестов скорости по моделям: filesDir/bench_history.json */
object BenchHistory {
    data class Run(val model: String, val ts: Long, val cpu: Float?, val gpu: Float?)
    private fun file(ctx: android.content.Context) = File(ctx.filesDir, "bench_history.json")
    fun all(ctx: android.content.Context): List<Run> = runCatching {
        val a = org.json.JSONArray(file(ctx).readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Run(o.getString("m"), o.getLong("t"), o.optDouble("c", -1.0).takeIf { it > 0 }?.toFloat(), o.optDouble("g", -1.0).takeIf { it > 0 }?.toFloat())
        } }
    }.getOrDefault(emptyList())
    fun add(ctx: android.content.Context, r: Run) {
        val l = (all(ctx) + r).takeLast(200)
        val a = org.json.JSONArray()
        l.forEach { x -> a.put(org.json.JSONObject().put("m", x.model).put("t", x.ts).apply { x.cpu?.let { put("c", it.toDouble()) }; x.gpu?.let { put("g", it.toDouble()) } }) }
        file(ctx).writeText(a.toString())
    }
    fun of(ctx: android.content.Context, model: String) = all(ctx).filter { it.model == model }
    fun last(ctx: android.content.Context, model: String) = of(ctx, model).lastOrNull()
    fun best(r: Run) = listOfNotNull(r.cpu, r.gpu).maxOrNull()
}

@Composable
fun SpeedTestCard() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<List<SpeedResult>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var hist by remember { mutableStateOf(BenchHistory.all(ctx)) }
    var showHist by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        busy = false
        val o = runCatching { org.json.JSONObject(File(ctx.filesDir, "bench.json").readText()) }.getOrNull()
        val list = ArrayList<SpeedResult>()
        for (l in listOf("CPU", "GPU")) {
            when {
                o?.has(l) == true -> { val v = o.getDouble(l); list += if (v > 0) SpeedResult(l, v.toFloat()) else SpeedResult(l, null, o.optString(l + "_err", "не запустилась")) }
                o?.optString("stage") == l -> list += SpeedResult(l, null, "движок упал — $l не поддерживается этой моделью")
            }
        }
        res = list
        if (list.any { it.charsPerSec != null }) BenchHistory.add(ctx, BenchHistory.Run(app.settings.activeModel, System.currentTimeMillis(),
            list.firstOrNull { it.label == "CPU" }?.charsPerSec, list.firstOrNull { it.label == "GPU" }?.charsPerSec))
        hist = BenchHistory.all(ctx)
        val best = list.filter { it.charsPerSec != null }.maxByOrNull { it.charsPerSec!! }
        if (best != null) { app.settings.useGpu = best.label == "GPU"; note = "Включено: ${best.label}" }
        else note = "Не удалось измерить — оставил как было"
        if (list.any { it.label == "GPU" && it.charsPerSec == null }) app.settings.useGpu = false
        if (o == null || !o.optBoolean("done")) ErrorLog.add("Тест скорости", IllegalStateException("Процесс теста завершился на этапе ${o?.optString("stage") ?: "старт"}"))
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, null); Spacer(Modifier.width(8.dp))
                Text("Тест скорости", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (busy) NanoLoader(22.dp)
                else FilledTonalButton({
                    scope.launch {
                        busy = true; res = emptyList(); note = null
                        app.ai.local.release()   // освобождаем память — тест загрузит модель в своём процессе
                        launcher.launch(android.content.Intent(ctx, BenchActivity::class.java))
                    }
                }, enabled = app.settings.activeModel.isNotBlank()) { Text("Проверить") }
            }
            Text("Сравнит процессор и видеочип на твоём телефоне (~1 минута) и включит более быстрый вариант. " +
                    "Тест идёт отдельно: даже если видеочип не справится, приложение не закроется.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            res.forEach { r ->
                Row {
                    Text(r.label, Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
                    Text(r.charsPerSec?.let { "≈ ${"%.0f".format(it)} символов/с (~${"%.0f".format(it / 6)} слов/с)" } ?: r.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
            if (hist.isNotEmpty()) {
                TextButton({ showHist = !showHist }) {
                    Icon(Icons.Rounded.History, null); Spacer(Modifier.width(6.dp))
                    Text(if (showHist) "Скрыть историю" else "История тестов (${hist.size})")
                }
                if (showHist) BenchHistoryView(hist)
            }
        }
    }
}

/** Сравнение моделей по лучшему результату + график всех замеров. */
@Composable
fun BenchHistoryView(hist: List<BenchHistory.Run>) {
    val cs = MaterialTheme.colorScheme
    val byModel = hist.groupBy { it.model }
    val maxV = hist.mapNotNull { BenchHistory.best(it) }.maxOrNull() ?: 1f
    val names = ModelCatalog.all.associate { it.file to it.name }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Таблица: модель — последний CPU/GPU — лучший
        byModel.forEach { (m, runs) ->
            val last = runs.last()
            val best = runs.mapNotNull { BenchHistory.best(it) }.maxOrNull() ?: 0f
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(names[m] ?: m.substringBeforeLast('.'), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text("CPU ${last.cpu?.let { "%.0f".format(it) } ?: "—"} · GPU ${last.gpu?.let { "%.0f".format(it) } ?: "—"}",
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                // Полоса лучшего результата
                Box(Modifier.fillMaxWidth().height(8.dp).background(cs.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)) {
                    Box(Modifier.fillMaxWidth(best / maxV).fillMaxHeight().background(cs.primary, MaterialTheme.shapes.extraSmall))
                }
                // Мини-график замеров во времени
                if (runs.size > 1) androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp)) {
                    val pts = runs.takeLast(20).map { BenchHistory.best(it) ?: 0f }
                    val stepX = size.width / (pts.size - 1).coerceAtLeast(1)
                    val path = androidx.compose.ui.graphics.Path()
                    pts.forEachIndexed { i, v ->
                        val x = i * stepX; val y = size.height * (1f - v / maxV)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        drawCircle(cs.primary, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
                    }
                    drawPath(path, cs.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }
        Text("Числа — символов в секунду. Видеочип выбирается для каждой модели отдельно по последнему тесту.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

// ---------- Знакомство при первом запуске ----------

@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(24.dp))
                    when (page) {
                        0 -> {
                            NanoLoaderContained(96.dp)
                            Text("НаноПеро", style = MaterialTheme.typography.displaySmall)
                            Text("Твой ИИ-помощник, который работает прямо на телефоне — без интернета и без отправки данных в облако.",
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                            Text("Чат • клавиатура ✦ • сводка сообщений • голосовой дневник",
                                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                        }
                        1 -> {
                            Icon(Icons.Rounded.Memory, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Скачай модель", style = MaterialTheme.typography.headlineSmall)
                            Text("Один раз через интернет — дальше всё офлайн. Можно и позже: «Ещё → Модель».",
                                textAlign = TextAlign.Center)
                            ModelStore(compact = true, onMore = {})
                        }
                        2 -> {
                            Icon(Icons.Rounded.Keyboard, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Клавиатура ✦", style = MaterialTheme.typography.headlineSmall)
                            Text("Подсказки, автоисправление, эмодзи, свайп-набор и ИИ прямо в любом поле ввода.", textAlign = TextAlign.Center)
                            StepCard(1, "Включить", "Включи «НаноПеро ✦» в списке клавиатур", isImeEnabled(ctx)) {
                                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            StepCard(2, "Выбрать", "Сделать основной клавиатурой", false) {
                                ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
                            }
                        }
                        else -> {
                            Icon(Icons.Rounded.Notifications, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Сводка сообщений", style = MaterialTheme.typography.headlineSmall)
                            Text("Чтобы вечером получить главное из чатов, НаноПеро нужен доступ к уведомлениям. " +
                                    "Тексты остаются только на телефоне. Это можно включить и позже.", textAlign = TextAlign.Center)
                            OutlinedButton({
                                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Дать доступ") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDone) { Text("Пропустить") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    repeat(4) { i ->
                        Surface(Modifier.padding(3.dp).size(if (i == pager.currentPage) 10.dp else 7.dp), shape = CircleShape,
                            color = if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
                    }
                }
                Button({ if (pager.currentPage == 3) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(if (pager.currentPage == 3) "Начать" else "Далее")
                }
            }
        }
    }
}
