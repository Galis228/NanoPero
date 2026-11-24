package com.nanopero.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File

/** Модель, которую можно скачать прямо в приложении. Оценки 1–5 — примерные, для сравнения между собой. */
data class ModelInfo(
    val id: String, val name: String, val file: String, val bytes: Long, val note: String,
    val url: String, val ctxTokens: Int,
    val quality: Int, val speed: Int, val light: Int,
    val pros: List<String>, val cons: List<String>,
    val best: Boolean = false, val by: String = ""
) {
    val sizeText get() = if (bytes >= 1_000_000_000L) "%.1f ГБ".format(bytes / 1_073_741_824.0).replace('.', ',')
    else "${bytes / 1_048_576} МБ"
}

private const val HF = "https://huggingface.co/litert-community"
private const val HFU = "https://huggingface.co"

object ModelCatalog {
    val all = listOf(
        // --- Большие модели (формат GGUF, движок llama.cpp): умнее, но заметно медленнее ---
        ModelInfo("qwen35_9b", "Qwen 3.5 9B", "Qwen3.5-9B-Q4_K_M.gguf", 5_680_522_464L,
            "Самая умная, что вообще влезает в телефон. Для сложных вопросов.",
            "$HFU/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf", 4096,
            quality = 5, speed = 1, light = 1, by = "Alibaba",
            pros = listOf("Самые умные ответы: рассуждения, математика, код", "Хорошо понимает сложные и длинные просьбы",
                "По тестам обходит модели в разы крупнее"),
            cons = listOf("Медленная: ответ печатается неспешно", "Нужно ~7 ГБ оперативки — закрой тяжёлые игры",
                "Только процессор, телефон греется", "Не для клавиатуры — слишком долго")),
        ModelInfo("qwen3_8b", "Qwen 3 8B", "Qwen3-8B-Q4_K_M.gguf", 5_027_783_488L,
            "Проверенная большая модель от Alibaba.",
            "$HFU/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf", 4096,
            quality = 5, speed = 1, light = 1, by = "Alibaba",
            pros = listOf("Сильная в логике и коде", "Надёжная, хорошо изучена"),
            cons = listOf("Медленная, нужно ~6,5 ГБ оперативки", "Русский чуть хуже, чем у Gemma", "Не для клавиатуры")),
        ModelInfo("qwen35_4b", "Qwen 3.5 4B", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888L,
            "Новое поколение Qwen в среднем размере.",
            "$HFU/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf", 4096,
            quality = 4, speed = 2, light = 2, by = "Alibaba",
            pros = listOf("Умнее Qwen 3 4B при том же размере", "Хорошо рассуждает"),
            cons = listOf("Только процессор — медленнее Gemma 4 E2B", "Русский менее естественный, чем у Gemma")),
        // --- Модели Google AI Edge: быстрые, с ускорением ---
        ModelInfo("gemma4e4", "Gemma 4 E4B", "gemma-4-E4B-it.litertlm", 3_930_000_000L,
            "Самая умная из быстрых. Понимает фото.", "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", 4096,
            quality = 5, speed = 2, light = 1, by = "Google",
            pros = listOf("Лучшее качество ответов и текстов", "Отлично пишет по-русски и по-украински", "Хорошо держит длинный диалог"),
            cons = listOf("Медленнее всех — ответ печатается заметно дольше", "Занимает ~5–6 ГБ оперативки во время работы", "Сильнее греет телефон")),
        ModelInfo("gemma4", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm", 2_780_000_000L,
            "Лучший баланс ума и скорости. Рекомендую на каждый день.", "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 4096,
            quality = 4, speed = 3, light = 2, best = true, by = "Google",
            pros = listOf("Умная и при этом достаточно быстрая", "Отличный русский и украинский", "Хороша для клавиатуры, чата и сводок"),
            cons = listOf("Слабее E4B в сложных рассуждениях", "Файл 2,6 ГБ")),
        ModelInfo("qwen3_4b", "Qwen 3 4B", "qwen3_4b_mixed_int4.litertlm", 2_860_000_000L,
            "Сильна в логике, математике и коде.", "$HF/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm", 4096,
            quality = 4, speed = 2, light = 2, by = "Alibaba",
            pros = listOf("Хорошо рассуждает и считает", "Сильна в коде и английском"),
            cons = listOf("Русский текст звучит менее естественно, чем у Gemma", "Довольно медленная")),
        ModelInfo("granite3b", "Granite 4.2 3B", "granite-4.2-3b_int4.litertlm", 2_350_000_000L,
            "Деловая модель: аккуратно следует инструкциям.", "$HF/granite-4.2-3b/resolve/main/granite-4.2-3b_int4.litertlm", 4096,
            quality = 3, speed = 3, light = 2, by = "IBM",
            pros = listOf("Чётко выполняет инструкции", "Хороша для кратких выжимок и форматирования"),
            cons = listOf("Слабый русский — лучше для английского", "Суховатый стиль")),
        ModelInfo("lfm2_26", "LFM2 2.6B", "LFM2-2.6B_int4.litertlm", 1_640_000_000L,
            "Очень быстрая для своего размера.", "$HF/LFM2-2.6B/resolve/main/LFM2-2.6B_int4.litertlm", 4096,
            quality = 3, speed = 4, light = 3, by = "Liquid AI",
            pros = listOf("Быстро отвечает при неплохом качестве", "Экономна по памяти"),
            cons = listOf("Русский средний, иногда ошибается в словах", "Лицензия бесплатна для личного использования")),
        ModelInfo("qwen15", "Qwen 2.5 1.5B", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task", 1_720_000_000L,
            "Проверенная лёгкая модель.", "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task", 4096,
            quality = 2, speed = 4, light = 3, by = "Alibaba",
            pros = listOf("Быстрая", "Справляется с простыми правками и переводом"),
            cons = listOf("Часто упрощает и ошибается в длинных ответах", "Старое поколение")),
        ModelInfo("qwen3_06", "Qwen 3 0.6B", "Qwen3-0.6B.litertlm", 644_000_000L,
            "Маленькая, но современная.", "$HF/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm", 2048,
            quality = 2, speed = 5, light = 5, by = "Alibaba",
            pros = listOf("Очень быстрая и лёгкая", "Подходит для исправления опечаток и коротких фраз"),
            cons = listOf("Слабая для чата и сводок", "Часто путается в русском")),
        ModelInfo("qwen05", "Qwen 2.5 0.5B", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 574_000_000L,
            "Для совсем слабых телефонов.", "$HF/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 1024,
            quality = 1, speed = 5, light = 5, by = "Alibaba",
            pros = listOf("Мгновенные ответы", "Почти не занимает память"),
            cons = listOf("Низкое качество", "Маленький контекст — только короткие тексты")),
        ModelInfo("lfm25_350", "LFM2.5 350M", "LFM2.5-350M_int4.litertlm", 260_000_000L,
            "Самая крошечная — для экспериментов.", "$HF/LFM2.5-350M/resolve/main/LFM2.5-350M_int4.litertlm", 2048,
            quality = 1, speed = 5, light = 5, by = "Liquid AI",
            pros = listOf("Всего ~250 МБ", "Работает даже на старых телефонах"),
            cons = listOf("Только простейшие задачи", "Плохо понимает русский")),
    )
    fun byFile(name: String) = all.firstOrNull { it.file == name }
}

sealed class DlState {
    data object None : DlState()
    data class Running(val done: Long, val total: Long, val speed: Long, val waiting: String?) : DlState()
    data class Failed(val reason: String) : DlState()
    data object Installed : DlState()
}

/** Загрузка моделей через системный DownloadManager: продолжает качать в фоне, сам докачивает после обрыва сети. */
object ModelDownloads {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Начать загрузку. Возвращает текст ошибки или null. */
    fun start(ctx: Context, m: ModelInfo, allowMobile: Boolean): String? {
        val dir = ctx.app.ai.local.downloadDir ?: return "Нет доступа к памяти телефона"
        if (dir.usableSpace < m.bytes + 300L * 1024 * 1024)
            return "Не хватает места: нужно ${m.sizeText}, свободно ${"%.1f".format(dir.usableSpace / 1_073_741_824.0)} ГБ"
        cancel(ctx, m)
        File(dir, m.file + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("НаноПеро: ${m.name}")
            .setDescription("Модель ИИ для работы без интернета")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .apply {
                // Общая папка с «Персоной» — в «Загрузках», иначе — своя папка приложения
                if (SharedModels.active(ctx)) setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "${SharedModels.FOLDER}/${m.file}.part")
                else setDestinationInExternalFilesDir(ctx, "models", m.file + ".part")
            }
            .setAllowedOverMetered(allowMobile)
            .setAllowedOverRoaming(false)
            .setRequiresCharging(false)
        val id = dm(ctx).enqueue(req)
        prefs(ctx).edit().putLong(m.id, id).apply()
        return null
    }

    fun cancel(ctx: Context, m: ModelInfo) {
        val id = prefs(ctx).getLong(m.id, -1)
        if (id >= 0) runCatching { dm(ctx).remove(id) }
        prefs(ctx).edit().remove(m.id).apply()
        ctx.app.ai.local.downloadDir?.let { File(it, m.file + ".part").delete() }
    }

    fun state(ctx: Context, m: ModelInfo): DlState {
        if (ctx.app.ai.local.find(m.file) != null) return DlState.Installed
        val id = prefs(ctx).getLong(m.id, -1)
        if (id < 0) return DlState.None
        dm(ctx).query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) { prefs(ctx).edit().remove(m.id).apply(); return DlState.None }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: m.bytes
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> { finish(ctx, m); if (ctx.app.ai.local.find(m.file) != null) DlState.Installed else DlState.None }
                DownloadManager.STATUS_FAILED -> DlState.Failed(failText(reason))
                DownloadManager.STATUS_PAUSED -> DlState.Running(done, total, 0, pausedText(reason))
                else -> DlState.Running(done, total, 0, if (status == DownloadManager.STATUS_PENDING) "Ожидание…" else null)
            }
        }
        return DlState.None
    }

    /** Переименовать готовый файл и сделать модель активной, если другой ещё нет. */
    fun finish(ctx: Context, m: ModelInfo) {
        val dir = ctx.app.ai.local.downloadDir ?: return
        val part = listOfNotNull(dir, SharedModels.readable(), ctx.getExternalFilesDir("models"))
            .map { File(it, m.file + ".part") }.firstOrNull { it.exists() } ?: File(dir, m.file + ".part")
        val dst = File(part.parentFile, m.file)
        if (part.exists()) { dst.delete(); part.renameTo(dst) }
        prefs(ctx).edit().remove(m.id).apply()
        val s = ctx.app.settings
        if (dst.exists() && (s.activeModel.isBlank() || ctx.app.ai.local.find(s.activeModel) == null)) {
            s.activeModel = m.file
            s.maxTokens = m.ctxTokens
            ctx.app.ai.local.releaseAsync()
        }
    }

    /** Вызывается системой по завершении загрузки, даже если приложение закрыто. */
    fun onComplete(ctx: Context, id: Long) {
        val p = prefs(ctx)
        ModelCatalog.all.firstOrNull { p.getLong(it.id, -2) == id }?.let { state(ctx, it) }
    }

    private fun pausedText(r: Int) = when (r) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Ждёт Wi-Fi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Нет сети — продолжит сама"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Повтор через минуту…"
        else -> "Пауза"
    }

    private fun failText(r: Int) = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Не хватило места"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "Обрыв загрузки — попробуй снова"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Сервер не отдал файл — попробуй позже"
        in 400..599 -> "Ошибка сервера ($r)"
        else -> "Не удалось скачать ($r)"
    }
}

class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id >= 0) ModelDownloads.onComplete(ctx, id)
    }
}

/** Карточки моделей с загрузкой прямо в приложении. */
@Composable
fun ModelStore(compact: Boolean = false, onMore: () -> Unit = {}, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val s = ctx.app.settings
    var tick by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<ModelInfo?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var del by remember { mutableStateOf<ModelInfo?>(null) }
    // Обновляем прогресс, пока экран открыт
    val states = remember(tick) { ModelCatalog.all.associateWith { ModelDownloads.state(ctx, it) } }
    val speeds = remember { mutableStateMapOf<String, Pair<Long, Long>>() }
    LaunchedEffect(Unit) {
        var prevInstalled = states.count { it.value == DlState.Installed }
        while (true) {
            delay(1000); tick++
            val inst = ModelCatalog.all.count { ModelDownloads.state(ctx, it) == DlState.Installed }
            if (inst != prevInstalled) { prevInstalled = inst; onChanged() }
        }
    }

    fun begin(m: ModelInfo) {
        err = null
        if (!ModelDownloads.isOnline(ctx)) { err = "Нет интернета. Подключись к сети и попробуй снова."; return }
        if (ModelDownloads.isMetered(ctx)) { ask = m; return }
        err = ModelDownloads.start(ctx, m, allowMobile = false); tick++
    }

    val compare = remember { mutableStateListOf<String>() }
    val nanoWins by produceState(false, s.engine, s.activeModel) {
        value = s.engineChoice != EngineChoice.LOCAL && ctx.app.ai.useNanoNow()
    }
    var showCompare by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (compare.isEmpty()) "Отметь «Сравнить» у 2–3 моделей" else "Выбрано для сравнения: ${compare.size}/3",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ info = true }) { Icon(Icons.Rounded.Info, "Что значат характеристики") }
            if (compare.size >= 2) FilledTonalButton({ showCompare = true }) { Text("Сравнить") }
        }
        ModelCatalog.all.filter { !compact || it.best || states[it] != DlState.None }.forEach { m ->
            val st = states[m] ?: DlState.None
            // В режиме «Авто» с готовой Gemini Nano отвечает она — файл тогда не «используется», а только выбран
            val active = s.activeModel == m.file && !nanoWins
            var open by remember { mutableStateOf(false) }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = when {
                    active -> MaterialTheme.colorScheme.secondaryContainer
                    m.best -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                onClick = { open = !open }
            ) {
                Column(Modifier.padding(14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, style = MaterialTheme.typography.titleMedium)
                                if (m.best) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                                        Text("Рекомендую", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                            Text("${m.by} · ${m.sizeText}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Подробнее")
                    }
                    Text(m.note, style = MaterialTheme.typography.bodyMedium)
                    Traits(m, measured = remember(tick) { BenchHistory.last(ctx, m.file) }?.let { BenchHistory.best(it) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Подходит для: " + m.goodFor.joinToString(", "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val sel = m.id in compare
                        FilterChip(sel, { if (sel) compare.remove(m.id) else if (compare.size < 3) compare.add(m.id) },
                            { Text("Сравнить") }, leadingIcon = if (sel) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                    if (open) {
                        m.pros.forEach { Text("＋ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        m.cons.forEach { Text("－ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    when (st) {
                        DlState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (active) "Используется" else "Скачана", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            if (!active) FilledTonalButton({
                                // Явный выбор файла-модели: отвечать должна она, а не Gemini Nano
                                s.activeModel = m.file; s.maxTokens = m.ctxTokens; s.engine = EngineChoice.LOCAL.name
                                ctx.app.ai.local.releaseAsync(); tick++; onChanged()
                            }) { Text("Использовать") }
                            IconButton({ del = m }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                        }
                        is DlState.Running -> {
                            val prev = speeds[m.id]
                            val now = System.currentTimeMillis()
                            val speed = if (prev != null && now > prev.second) ((st.done - prev.first) * 1000 / (now - prev.second)).coerceAtLeast(0) else 0
                            SideEffect { speeds[m.id] = st.done to now }
                            LinearWavyProgressIndicatorOrPlain((st.done.toFloat() / st.total).coerceIn(0f, 1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    st.waiting ?: ("%.2f / %.2f ГБ".format(st.done / 1_073_741_824.0, st.total / 1_073_741_824.0) +
                                            if (speed > 0) " · ${fmtBytes(speed)}/с" + eta(st.total - st.done, speed) else ""),
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                                )
                                TextButton({ ModelDownloads.cancel(ctx, m); tick++ }) { Text("Отмена") }
                            }
                        }
                        is DlState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(st.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Button({ begin(m) }) { Text("Повторить") }
                        }
                        DlState.None -> Button({ begin(m) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Скачать ${m.sizeText}")
                        }
                    }
                }
            }
        }
        if (compact) TextButton({ onMore() }) { Text("Все модели и сравнение →") }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("Нажми на карточку — плюсы и минусы. «Лёгкость» — насколько модель бережёт память и батарею. Оценки примерные, для сравнения. Загрузка идёт в фоне и продолжится, " +
                "даже если закрыть приложение; после неё модель включится сама.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (info) AlertDialog(onDismissRequest = { info = false }, confirmButton = { TextButton({ info = false }) { Text("Понятно") } },
        icon = { Icon(Icons.Rounded.Info, null) }, title = { Text("Как читать характеристики") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🧠 Ум — насколько хорошо модель понимает просьбы, пишет по-русски/украински и не выдумывает.")
            Text("⚡ Скорость — как быстро появляется ответ. Если ты запускал тест скорости, рядом показано реальное число на твоём телефоне.")
            Text("💾 Память — сколько оперативки займёт модель во время работы. На Pixel 8 Pro 12 ГБ, но система и другие приложения тоже её используют.")
            Text("Чем умнее модель, тем она обычно тяжелее и медленнее. Для клавиатуры важнее скорость, для чата и сводок — ум.")
        } })

    if (showCompare) {
        val ms = ModelCatalog.all.filter { it.id in compare }
        AlertDialog(onDismissRequest = { showCompare = false }, confirmButton = { TextButton({ showCompare = false }) { Text("Закрыть") } },
            title = { Text("Сравнение") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    @Composable fun row(label: String, f: (ModelInfo) -> String) {
                        Row { Text(label, Modifier.width(78.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ms.forEach { Text(f(it), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
                        HorizontalDivider()
                    }
                    row("") { it.name }
                    row("Ум") { qualityWord(it.quality) }
                    row("Скорость") { speedWord(it.speed) }
                    row("Замер") { m -> BenchHistory.last(ctx, m.file)?.let { BenchHistory.best(it) }?.let { "%.0f симв/с".format(it) } ?: "нет" }
                    row("Память") { "~" + ramText(it) }
                    row("Файл") { it.sizeText }
                    row("Контекст") { "${it.ctxTokens} ток." }
                    row("Для чего") { it.goodFor.joinToString(", ") }
                    row("Плюсы") { it.pros.joinToString("; ") }
                    row("Минусы") { it.cons.joinToString("; ") }
                }
            })
    }

    del?.let { m ->
        DeleteModelDialog(m.name, ctx.app.ai.local.find(m.file), m.sizeText, onConfirm = {
            if (s.activeModel == m.file) { s.activeModel = ""; ctx.app.ai.local.releaseAsync() }
            ctx.app.ai.local.find(m.file)?.delete(); del = null; tick++; onChanged()
        }, onDismiss = { del = null })
    }

    ask?.let { m ->
        AlertDialog(
            onDismissRequest = { ask = null },
            icon = { Icon(Icons.Rounded.SignalCellularAlt, null) },
            title = { Text("Ты в мобильной сети") },
            text = { Text("${m.name} весит ${m.sizeText}. Скачать сейчас через мобильный интернет или подождать Wi-Fi?") },
            confirmButton = { TextButton({ err = ModelDownloads.start(ctx, m, allowMobile = false); ask = null; tick++ }) { Text("Ждать Wi-Fi") } },
            dismissButton = { TextButton({ err = ModelDownloads.start(ctx, m, allowMobile = true); ask = null; tick++ }) { Text("Качать сейчас") } }
        )
    }
}

private fun eta(left: Long, speed: Long): String {
    if (speed <= 0) return ""
    val sec = left / speed
    return when {
        sec < 60 -> " · ~${sec} с"
        sec < 3600 -> " · ~${sec / 60} мин"
        else -> " · ~${sec / 3600} ч ${sec % 3600 / 60} мин"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinearWavyProgressIndicatorOrPlain(p: Float) {
    LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
}

/** Есть ли вообще чем отвечать (Nano или скачанная модель). */
fun hasAnyModel(ctx: Context): Boolean =
    ctx.app.settings.activeModel.isNotBlank() && ctx.app.ai.local.find(ctx.app.settings.activeModel) != null

fun qualityWord(v: Int) = when (v) { 5 -> "Очень умная"; 4 -> "Умная"; 3 -> "Средняя"; 2 -> "Простая"; else -> "Базовая" }
fun speedWord(v: Int) = when (v) { 5 -> "Мгновенная"; 4 -> "Быстрая"; 3 -> "Средняя"; 2 -> "Неспешная"; else -> "Медленная" }
/** Примерная оперативка при работе: веса + кэш контекста. */
fun ramText(m: ModelInfo): String { val gb = m.bytes * 1.2 / 1_073_741_824.0 + m.ctxTokens / 4096.0 * 0.25
    return if (gb < 1) "${(gb * 1024).toInt()} МБ ОЗУ" else "%.1f ГБ ОЗУ".format(gb) }

val ModelInfo.goodFor: List<String> get() = buildList {
    if (id.startsWith("gemma4")) add("фото")
    if (quality >= 4) { add("чат"); add("сводки"); add("перевод") }
    else if (quality == 3) { add("правка текста"); add("короткие ответы") }
    else add("простые правки")
    if (speed >= 4) add("клавиатура")
    if (light >= 4) add("слабая батарея")
}

@Composable
private fun Traits(m: ModelInfo, measured: Float?) {
    val cs = MaterialTheme.colorScheme
    @Composable fun chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, good: Int) {
        val (bg, fg) = when {
            good >= 4 -> cs.primaryContainer to cs.onPrimaryContainer
            good == 3 -> cs.secondaryContainer to cs.onSecondaryContainer
            else -> cs.tertiaryContainer to cs.onTertiaryContainer
        }
        Surface(shape = MaterialTheme.shapes.small, color = bg) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = fg); Spacer(Modifier.width(4.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chip(Icons.Rounded.Psychology, qualityWord(m.quality), m.quality)
        chip(Icons.Rounded.Bolt, speedWord(m.speed) + (measured?.let { " · %.0f симв/с".format(it) } ?: ""), m.speed)
        chip(Icons.Rounded.Memory, "~" + ramText(m), m.light)
    }
}

@Suppress("unused")
@Composable
private fun Rating(label: String, v: Int) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { i ->
                Surface(Modifier.size(width = 12.dp, height = 6.dp), shape = MaterialTheme.shapes.extraSmall,
                    color = if (i < v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
            }
        }
    }
}
