package com.nanopero.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class NanoState(val title: String) {
    UNKNOWN("Проверяю…"),
    AVAILABLE("Готова"),
    DOWNLOADABLE("Можно скачать"),
    DOWNLOADING("Скачивается"),
    UNAVAILABLE("Не поддерживается на этом телефоне"),
    ERROR("Ошибка проверки")
}

class EngineException(msg: String) : Exception(msg)

/** Запрос к модели: системная роль, история диалога, сообщение пользователя и параметры. */
data class AiReq(
    val system: String,
    val user: String,
    val history: List<ChatMsg> = emptyList(),
    val temperature: Float = 0.3f,
    val maxOut: Int = 1024,
    /** JPEG/PNG для моделей, понимающих картинки (Gemma 4 через LiteRT-LM). */
    val image: ByteArray? = null,
    /** Режим размышлений: модель сначала рассуждает про себя (дольше, но точнее на сложных задачах). */
    val think: Boolean = false,
)

/** Умеет ли модель «думать перед ответом». */
fun supportsThinking(model: String): Boolean {
    val n = model.lowercase()
    return "qwen3" in n || "gemma-4" in n
}

/** Видимая часть ответа: без блока размышлений (и без незакрытого начала). */
fun afterThink(sb: CharSequence): String {
    val s = sb.toString()
    val end = s.lastIndexOf("</think>")
    if (end >= 0) return s.substring(end + 8)
    return if (s.trimStart().startsWith("<think>")) "" else s
}

/** Признаки того, что модель начала «болтать сама с собой» — обрезаем по ним. */
private val stopMarkers = listOf(
    "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<end_of_turn>", "<start_of_turn>", "<eos>", "<|eot_id|>",
    "<|start_header_id|>", "\nuser\n", "\nUser:", "\nПользователь:", "\nТекст:\n", "\nИнструкция:"
)

internal fun stopIndex(sb: CharSequence): Int {
    var best = -1
    for (m in stopMarkers) {
        val i = sb.indexOf(m)
        if (i >= 0 && (best < 0 || i < best)) best = i
    }
    return best
}

/** Зацикливание: последние 60 символов уже встречались раньше в ответе. */
internal fun looping(sb: CharSequence): Boolean {
    if (sb.length < 360) return false
    val tail = sb.subSequence(sb.length - 60, sb.length).toString()
    return sb.subSequence(0, sb.length - 60).toString().contains(tail)
}

/** Gemini Nano через ML Kit GenAI Prompt API (AICore). */
class NanoEngine {
    private var model: GenerativeModel? = null
    private fun client() = model ?: Generation.getClient().also { model = it }
    private var cached: NanoState? = null
    private var cachedAt = 0L
    /** Подробности последней проверки (код AICore или текст ошибки) — показываем на экране модели. */
    var lastDetail by mutableStateOf("")
    private var warmed = false

    /** «Готова» запоминаем навсегда, остальное перепроверяем раз в 20 с: Nano могли включить, пока приложение открыто. */
    suspend fun cachedState(): NanoState {
        val c = cached
        if (c == NanoState.AVAILABLE || (c != null && System.currentTimeMillis() - cachedAt < 20_000)) return c
        return state()
    }
    suspend fun state(): NanoState = stateRaw().also { cached = it; cachedAt = System.currentTimeMillis() }

    private suspend fun stateRaw(): NanoState = try {
        val st = client().checkStatus()
        lastDetail = "статус AICore: $st"
        when (st) {
            FeatureStatus.AVAILABLE -> NanoState.AVAILABLE.also { warmup() }
            FeatureStatus.DOWNLOADABLE -> NanoState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoState.DOWNLOADING
            else -> NanoState.UNAVAILABLE
        }
    } catch (t: Throwable) {
        // Клиент мог «зависнуть» в старом состоянии — пересоздаём при следующей проверке
        runCatching { model?.close() }; model = null
        lastDetail = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(300)
        NanoState.ERROR
    }

    /** Заранее подгружаем модель в AICore, чтобы первый ответ был быстрее. */
    private suspend fun warmup() {
        if (warmed) return
        warmed = true
        runCatching { client().warmup() }
    }

    fun download(): Flow<String> = flow {
        client().download().collect { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> emit("Загрузка началась…")
                is DownloadStatus.DownloadProgress -> emit("Скачано ${s.totalBytesDownloaded / 1_048_576} МБ")
                is DownloadStatus.DownloadCompleted -> emit("Готово ✓")
                is DownloadStatus.DownloadFailed -> emit("Ошибка: ${s.e.message}")
                else -> {}
            }
        }
    }

    fun generate(r: AiReq): Flow<String> = flow {
        val prompt = buildString {
            append(r.system).append("\n\n")
            r.history.forEach { m -> append(if (m.role == "user") "Пользователь: " else "Ассистент: ").append(m.text).append('\n') }
            if (r.history.isNotEmpty()) append("Пользователь: ")
            append(r.user)
        }
        val req = generateContentRequest(TextPart(prompt)) {
            temperature = r.temperature
            topK = 16
            maxOutputTokens = 256
        }
        val sb = StringBuilder()
        client().generateContentStream(req).collect { chunk ->
            sb.append(chunk.candidates.firstOrNull()?.text.orEmpty())
            emit(sb.toString())
        }
    }
}

/**
 * Локальная модель-файл:
 *  • .litertlm — через LiteRT-LM (Gemma 4, Qwen 3 …): правильные роли, штраф за повторы, без «размышлений»;
 *  • .task — через MediaPipe LLM Inference с явным шаблоном диалога под модель.
 */
class LocalEngine(private val ctx: Context) {
    @Volatile private var llm: LlmInference? = null
    @Volatile private var lt: Engine? = null
    @Volatile private var loadedKey: String? = null
    @Volatile private var activeSession: LlmInferenceSession? = null
    @Volatile private var activeConv: Conversation? = null
    private val loadLock = Mutex()
    /** Одновременно идёт только одна генерация — иначе две копии модели жгут CPU. */
    private val genLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var unloadJob: Job? = null

    @Volatile private var gg: Long = 0L   // llama.cpp (GGUF)
    val isLoaded get() = llm != null || lt != null || gg != 0L
    val modelsDir: File get() = File(ctx.filesDir, "models").apply { mkdirs() }
    /** Папка для моделей, скачанных прямо в приложении (DownloadManager пишет только во внешнюю папку приложения). */
    val downloadDir: File? get() = if (SharedModels.active(ctx)) SharedModels.ensure() else ctx.getExternalFilesDir("models")?.apply { mkdirs() }
    /** Все места, где могут лежать модели: общая папка с «Персоной» и папки приложения. */
    private fun dirs(): List<File> = listOfNotNull(SharedModels.readable(), modelsDir, ctx.getExternalFilesDir("models"))
    fun models(): List<File> = dirs()
        .flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
        .filter { SharedModels.isModel(it) }
        .distinctBy { it.name }
        .sortedBy { it.name }
    fun find(name: String): File? = dirs().map { File(it, name) }.firstOrNull { it.exists() }

    init {
        scope.launch { modelsDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
    }

    private fun file(s: Settings): File {
        if (s.activeModel.isBlank()) throw EngineException("Не выбран файл модели. Открой «Ещё → Модель».")
        return find(s.activeModel) ?: throw EngineException("Файл модели не найден. Открой «Ещё → Модель» и скачай её заново.")
    }

    private fun unloadLocked() {
        runCatching { llm?.close() }; llm = null
        runCatching { lt?.close() }; lt = null
        if (gg != 0L) { runCatching { LlamaNative.free(gg) }; gg = 0L }
        loadedKey = null
    }

    private suspend fun ensureMp(s: Settings): LlmInference = loadLock.withLock {
        val f = file(s)
        val key = "mp|${f.path}|${s.maxTokens}|${s.useGpu}"
        llm?.takeIf { loadedKey == key }?.let { return it }
        withContext(Dispatchers.IO) {
            unloadLocked()
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(f.absolutePath)
                .setMaxTokens(s.maxTokens)
                .setMaxTopK(64)
                .setPreferredBackend(if (s.useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
                .build()
            LlmInference.createFromOptions(ctx, opts).also { llm = it; loadedKey = key }
        }
    }

    private suspend fun ensureLt(s: Settings, vision: Boolean = false): Engine = loadLock.withLock {
        val f = file(s)
        // Движок с «глазами» подходит и для обычного текста, поэтому перезагружаем только когда нужна картинка
        lt?.takeIf { loadedKey == "lt|${f.path}|${s.maxTokens}|${s.useGpu}|v" }?.let { return it }
        val key = "lt|${f.path}|${s.maxTokens}|${s.useGpu}" + if (vision) "|v" else ""
        lt?.takeIf { loadedKey == key }?.let { return it }
        withContext(Dispatchers.IO) {
            unloadLocked()
            val backend: Backend = if (s.useGpu) Backend.GPU() else Backend.CPU()
            fun make(vb: Backend?): Engine {
                val e = Engine(EngineConfig(f.absolutePath, backend, vb, null, s.maxTokens, if (vb != null) 1 else null, ctx.cacheDir.absolutePath))
                e.initialize(); return e
            }
            if (vision) {
                val e = runCatching { make(Backend.GPU()) }.recoverCatching { make(Backend.CPU()) }.getOrElse {
                    throw EngineException("Эта модель не умеет смотреть на фото (${it.message}). Фото понимают Gemma 4 E2B/E4B.")
                }
                return@withContext e.also { lt = it; loadedKey = key }
            }
            val cfg = EngineConfig(f.absolutePath, backend, null, null, s.maxTokens, null, ctx.cacheDir.absolutePath)
            val e = Engine(cfg)
            try { e.initialize() } catch (t: Throwable) {
                runCatching { e.close() }
                if (s.useGpu) throw EngineException("Модель не запустилась на GPU (${t.message}). Выключи «Ускорение на GPU».")
                throw EngineException("Не удалось загрузить модель: ${t.message}")
            }
            e.also { lt = it; loadedKey = key }
        }
    }

    fun cancelActive() {
        if (gg != 0L) runCatching { LlamaNative.stop() }
        activeSession?.let { runCatching { it.cancelGenerateResponseAsync() } }
        activeConv?.let { runCatching { it.cancelProcess() } }
    }

    suspend fun release() {
        unloadJob?.cancel()
        cancelActive()
        genLock.withLock { loadLock.withLock { unloadLocked() } }
    }

    fun releaseAsync() { scope.launch { release() } }

    fun scheduleUnload(delayMs: Long) {
        unloadJob?.cancel()
        unloadJob = scope.launch { delay(delayMs); if (!genLock.isLocked) release() }
    }

    fun generate(r: AiReq, s: Settings): Flow<String> = when {
        s.activeModel.endsWith(".gguf", true) -> genGguf(r, s)
        s.activeModel.endsWith(".litertlm", true) -> genLiteRt(r, s)
        else -> genMediaPipe(r, s)
    }

    // ---------- llama.cpp (GGUF) ----------

    private suspend fun ensureGguf(s: Settings): Long = loadLock.withLock {
        val f = file(s)
        val key = "gg|${f.path}|${s.maxTokens}"
        if (gg != 0L && loadedKey == key) return gg
        withContext(Dispatchers.IO) {
            unloadLocked()
            if (!LlamaNative.available) throw EngineException("Движок для моделей GGUF не загрузился на этом телефоне.")
            val h = LlamaNative.load(f.absolutePath, s.maxTokens, 0)
            if (h == 0L) throw EngineException("Не удалось загрузить ${f.name}. Возможно, файл повреждён или не хватает памяти — " +
                    "закрой другие приложения и попробуй снова.")
            gg = h; loadedKey = key; h
        }
    }

    private fun genGguf(r: AiReq, s: Settings): Flow<String> = channelFlow {
        unloadJob?.cancel()
        genLock.withLock {
            val h = ensureGguf(s)
            val roles = ArrayList<String>(); val contents = ArrayList<String>()
            roles += "system"; contents += r.system
            r.history.forEach { roles += if (it.role == "user") "user" else "assistant"; contents += it.text }
            roles += "user"; contents += r.user
            val sb = StringBuilder()
            val stopped = AtomicBoolean(false)
            val maxChars = (r.user.length * 2).coerceIn(1500, 6000)
            var lastEmit = 0L
            val rc = withContext(Dispatchers.IO) {
                LlamaNative.generate(h, roles.toTypedArray(), contents.toTypedArray(),
                    r.temperature.coerceIn(0.05f, 1.5f), if (r.think) maxOf(r.maxOut, 3072) else r.maxOut, 1.1f, r.think) { piece ->
                    if (stopped.get()) return@generate false
                    sb.append(piece)
                    // Проверки на «зацикливание» и стоп-слова — только по самому ответу, не по размышлениям
                    val vis = if (r.think) afterThink(sb) else null
                    val visLen = vis?.length ?: sb.length
                    val cut = if (vis == null) stopIndex(sb) else stopIndex(StringBuilder(vis))
                    if (cut >= 0 || visLen > maxChars || (if (vis == null) looping(sb) else looping(StringBuilder(vis)))) {
                        if (cut >= 0) { if (vis == null) sb.setLength(cut) else sb.setLength(sb.length - vis.length + cut) }
                        stopped.set(true); return@generate false
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastEmit > 80) { lastEmit = now; trySend(sb.toString()) }
                    !isClosedForSend
                }
            }
            when (rc) {
                1 -> throw EngineException("Не удалось подготовить запрос для этой модели (неизвестный формат диалога).")
                2 -> throw EngineException("Текст слишком длинный для модели. Сократи его или увеличь контекст в «Ещё → Модель».")
                3 -> if (sb.isEmpty()) throw EngineException("Модель не смогла ответить — возможно, не хватило памяти.")
            }
            send(sb.toString())
        }
        scheduleUnload(ctx.app.settings.unloadSec * 1000L)
    }.onCompletion { if (it != null) runCatching { LlamaNative.stop() } }

    // ---------- LiteRT-LM ----------

    private fun genLiteRt(r: AiReq, s: Settings): Flow<String> = channelFlow {
        unloadJob?.cancel()
        genLock.withLock {
            val engine = ensureLt(s, vision = r.image != null)
            val history = r.history.map { if (it.role == "user") Message.user(it.text) else Message.model(it.text) }
            val conv = engine.createConversation(
                ConversationConfig(Contents.of(r.system), history, emptyList(),
                    SamplerConfig(40, 0.95, r.temperature.toDouble().coerceIn(0.05, 1.5), 0))
            )
            activeConv = conv
            val stopped = AtomicBoolean(false)
            val sb = StringBuilder()
            var lastEmit = 0L
            val maxChars = (r.user.length * 2).coerceIn(1500, 6000)
            try {
                val msg = if (r.image != null) Contents.of(Content.ImageBytes(r.image), Content.Text(r.user)) else Contents.of(r.user)
                conv.sendMessageAsync(
                    msg, emptyMap(),
                    RepetitionPenaltyConfig(1.12f, null, null, 256), null, null,
                    if (r.think) maxOf(r.maxOut, 3072) else r.maxOut, ThinkingConfig(r.think, if (r.think) 2048 else 0), null
                ).collect { m ->
                    if (stopped.get()) return@collect
                    m.contents.contents.forEach { c -> if (c is Content.Text) sb.append(c.text) }
                    val cut = stopIndex(sb)
                    if (cut >= 0 || sb.length > maxChars || looping(sb)) {
                        stopped.set(true)
                        if (cut >= 0) sb.setLength(cut)
                        runCatching { conv.cancelProcess() }
                        return@collect
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastEmit > 80) { lastEmit = now; send(sb.toString()) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!stopped.get() && sb.isEmpty()) throw EngineException(e.message ?: "Ошибка модели")
            } finally {
                activeConv = null
                withContext(NonCancellable) { runCatching { conv.cancelProcess() }; runCatching { conv.close() } }
            }
            send(sb.toString())
        }
        scheduleUnload(ctx.app.settings.unloadSec * 1000L)
    }

    // ---------- MediaPipe ----------

    private fun template(s: Settings): Template = when (s.templateChoice) {
        Template.AUTO -> {
            val n = s.activeModel.lowercase()
            when {
                "qwen" in n || "smol" in n || "chatml" in n -> Template.CHATML
                "gemma" in n -> Template.GEMMA
                "llama" in n -> Template.LLAMA
                else -> Template.NONE
            }
        }
        else -> s.templateChoice
    }

    private fun format(r: AiReq, t: Template): String = when (t) {
        Template.CHATML -> buildString {
            append("<|im_start|>system\n").append(r.system).append("<|im_end|>\n")
            r.history.forEach { m ->
                append(if (m.role == "user") "<|im_start|>user\n" else "<|im_start|>assistant\n").append(m.text).append("<|im_end|>\n")
            }
            append("<|im_start|>user\n").append(r.user).append("<|im_end|>\n<|im_start|>assistant\n")
        }
        Template.GEMMA -> buildString {
            // У Gemma нет роли system — кладём инструкции в первый ход пользователя
            var first = true
            r.history.forEach { m ->
                if (m.role == "user") {
                    append("<start_of_turn>user\n"); if (first) { append(r.system).append("\n\n"); first = false }
                    append(m.text).append("<end_of_turn>\n")
                } else append("<start_of_turn>model\n").append(m.text).append("<end_of_turn>\n")
            }
            append("<start_of_turn>user\n"); if (first) append(r.system).append("\n\n")
            append(r.user).append("<end_of_turn>\n<start_of_turn>model\n")
        }
        Template.LLAMA -> buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n").append(r.system).append("<|eot_id|>")
            r.history.forEach { m ->
                append("<|start_header_id|>").append(if (m.role == "user") "user" else "assistant")
                    .append("<|end_header_id|>\n\n").append(m.text).append("<|eot_id|>")
            }
            append("<|start_header_id|>user<|end_header_id|>\n\n").append(r.user)
                .append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
        }
        else -> buildString {
            append(r.system).append("\n\n")
            r.history.forEach { m -> append(if (m.role == "user") "Пользователь: " else "Ассистент: ").append(m.text).append('\n') }
            if (r.history.isNotEmpty()) append("Пользователь: ")
            append(r.user).append(if (r.history.isNotEmpty()) "\nАссистент:" else "\n")
        }
    }

    private fun genMediaPipe(r: AiReq, s: Settings): Flow<String> = channelFlow {
        unloadJob?.cancel()
        genLock.withLock {
            val engine = ensureMp(s)
            val t = template(s)
            val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(r.temperature.coerceAtLeast(0.05f))
                .setTopK(40)
                .setTopP(0.95f)
            // Свой шаблон → отключаем встроенный, чтобы не было двойной разметки
            if (t != Template.NONE) opts.setPromptTemplates(
                PromptTemplates.builder().setUserPrefix("").setUserSuffix("").setModelPrefix("").setModelSuffix("")
                    .setSystemPrefix("").setSystemSuffix("").build()
            )
            val session = LlmInferenceSession.createFromOptions(engine, opts.build())
            activeSession = session
            try {
                val full = format(r, t)
                val tokens = runCatching { session.sizeInTokens(full) }.getOrDefault(0)
                if (tokens > s.maxTokens - 64)
                    throw EngineException("Текст слишком длинный для модели (~$tokens токенов, лимит ${s.maxTokens}). " +
                            "Сократи его или увеличь контекст в «Ещё → Модель».")
                session.addQueryChunk(full)

                val maxChars = (r.user.length * 2).coerceIn(1200, 5000)
                val sb = StringBuilder()
                val finished = CompletableDeferred<Unit>()
                val stoppedFlag = AtomicBoolean(false)
                var lastEmit = 0L

                val future = session.generateResponseAsync { partial, done ->
                    if (stoppedFlag.get()) return@generateResponseAsync
                    sb.append(partial)
                    val cut = stopIndex(sb)
                    if (cut >= 0 || sb.length > maxChars || looping(sb)) {
                        stoppedFlag.set(true)
                        if (cut >= 0) sb.setLength(cut)
                        trySend(sb.toString())
                        runCatching { session.cancelGenerateResponseAsync() }
                        return@generateResponseAsync
                    }
                    val now = SystemClock.uptimeMillis()
                    if (done || now - lastEmit > 80) { lastEmit = now; trySend(sb.toString()) }
                }
                future.addListener({ finished.complete(Unit) }, { it.run() })
                try {
                    finished.await()
                } finally {
                    if (!future.isDone) {
                        stoppedFlag.set(true)
                        runCatching { session.cancelGenerateResponseAsync() }
                        withContext(NonCancellable) { withTimeoutOrNull(5_000) { finished.await() } }
                    }
                }
                val err = runCatching { future.get() }.exceptionOrNull()
                if (err != null && !stoppedFlag.get() && sb.isEmpty()) throw EngineException(err.cause?.message ?: err.message ?: "Ошибка модели")
                send(sb.toString())
            } finally {
                activeSession = null
                runCatching { session.close() }
            }
        }
        scheduleUnload(ctx.app.settings.unloadSec * 1000L)
    }

    /** Копирует выбранный файл модели в приватную папку приложения. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        var name = "model.task"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val isGguf = name.endsWith(".gguf", true)
        if (!name.endsWith(".task", true) && !name.endsWith(".litertlm", true) && !isGguf)
            throw EngineException("Нужен файл модели с расширением .litertlm, .task или .gguf")
        if (isGguf && ("mmproj" in name.lowercase()))
            throw EngineException("Это вспомогательный файл для картинок (mmproj), а не сама модель. Нужен файл вроде Qwen3.5-9B-Q4_K_M.gguf.")
        if ("f32" in name.lowercase() || "bf16" in name.lowercase() || size > (if (isGguf) 7_000L else 4_500L) * 1024 * 1024)
            throw EngineException("Это неквантованная или слишком большая версия — она будет очень тормозить. " +
                    "Возьми файл с int4 / q8 в названии или gemma-4-E2B-it.litertlm.")
        // Импорт сразу в общую папку, если она включена
        val target = if (SharedModels.active(ctx)) SharedModels.ensure() ?: modelsDir else modelsDir
        val free = target.usableSpace
        if (size > 0 && free < size + 500L * 1024 * 1024)
            throw EngineException("Не хватает места: нужно ${size / 1_048_576} МБ, свободно ${free / 1_048_576} МБ.")
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(target, "$name.part")
        val dst = File(target, name)
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var total = 0L
                    var lastPct = -1
                    while (true) {
                        ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n
                        if (size > 0) {
                            val pct = (total * 100 / size).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
        } catch (t: Throwable) { tmp.delete(); throw t }
        if (dst.name == ctx.app.settings.activeModel) release()
        dst.delete(); tmp.renameTo(dst)
        dst
    }
}

/** Выбор движка по настройкам + длинные тексты по частям + очистка ответов. */
class AiRouter(private val ctx: Context, val settings: Settings) {
    val nano = NanoEngine()
    val local = LocalEngine(ctx)

    /** Сейчас ответит Gemini Nano (без исключений — для интерфейса). */
    suspend fun useNanoNow(): Boolean = runCatching { useNano() }.getOrDefault(false)

    private suspend fun useNano(): Boolean = when (settings.engineChoice) {
        EngineChoice.NANO -> {
            val st = nano.cachedState()
            if (st != NanoState.AVAILABLE)
                throw EngineException("Gemini Nano недоступна: ${st.title}. Выбери «Файл-модель» в «Ещё → Модель».")
            true
        }
        EngineChoice.LOCAL -> false
        EngineChoice.AUTO -> nano.cachedState() == NanoState.AVAILABLE
    }

    private fun checkLocalReady() {
        if (settings.activeModel.isBlank())
            throw EngineException("На этом телефоне Gemini Nano недоступна, а файл-модель ещё не выбран. " +
                    "Открой «Ещё → Модель» и импортируй файл модели.")
    }

    private fun checkForeground() {
        if (!(ctx.applicationContext as App).isForeground)
            throw EngineException("Приложение свёрнуто — генерация остановлена, чтобы не грузить телефон.")
    }

    suspend fun chunkChars(): Int =
        if (useNano()) 2800 else ((settings.maxTokens - 600) * 1.6).toInt().coerceIn(500, 9000)

    suspend fun label(): String = if (useNano()) "Gemini Nano" else settings.activeModel.substringBeforeLast('.')

    /** Поток накопленного текста по запросу + название движка. */
    suspend fun stream(r: AiReq, allowBackground: Boolean = false): Pair<String, Flow<String>> {
        if (!allowBackground) checkForeground()
        if (r.image != null) {
            checkLocalReady()
            if (!settings.activeModel.endsWith(".litertlm", true))
                throw EngineException("Фото понимают только модели Gemma 4 (E2B/E4B). Выбери её в «Ещё → Модель».")
            return label() to local.generate(r, settings).flowOn(Dispatchers.IO)
        }
        val app = ctx.applicationContext as App
        // AICore не отвечает приложениям в фоне (клавиатура, пузырь) — там берём файл-модель, если она есть
        val nanoOk = useNano() && (app.isForeground || settings.activeModel.isBlank() || settings.engineChoice == EngineChoice.NANO)
        if (nanoOk) return "Gemini Nano" to nano.generate(r).flowOn(Dispatchers.Default)
        checkLocalReady()
        return label() to local.generate(r, settings).flowOn(Dispatchers.IO)
    }

    /** Простой текстовый запрос с системной ролью редактора. */
    suspend fun stream(prompt: String, allowBackground: Boolean = false, temperature: Float = 0.3f) =
        stream(AiReq(Prompts.SYSTEM_TEXT, prompt, temperature = temperature), allowBackground)

    suspend fun complete(prompt: String, allowBackground: Boolean = false, temperature: Float = 0.3f): String {
        var last = ""
        stream(prompt, allowBackground, temperature).second.collect { last = it }
        return Clean.output(last, null)
    }

    fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        val pieces = text.split(Regex("(?<=\\n)|(?<=[.!?…])\\s+"))
        for (p in pieces) {
            if (sb.length + p.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (p.length > max) p.chunked(max).forEach { out += it } else sb.append(p).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    suspend fun condense(text: String, allowBackground: Boolean = false, onStep: suspend (Int, Int) -> Unit = { _, _ -> }): String {
        val max = chunkChars()
        var cur = text
        var guard = 0
        if (cur.length > max && settings.heavyOnCharge && !isCharging(ctx))
            throw EngineException("Длинный текст разбирается по частям — это долго и тратит батарею. Поставь телефон на зарядку " +
                    "или выключи «Тяжёлое — только на зарядке» в настройках.")
        while (cur.length > max && guard++ < 3) {
            val parts = split(cur, max)
            val res = parts.mapIndexed { i, part ->
                onStep(i + 1, parts.size)
                complete(Prompts.partSummary(part), allowBackground, 0.2f)
            }
            cur = res.joinToString("\n")
        }
        return cur.take(max)
    }

    /** Режим + текст → поток очищенного результата. */
    suspend fun run(mode: Mode, text: String, preset: Preset? = null, allowBackground: Boolean = false): Pair<String, Flow<String>> {
        val t = text.trim()
        val max = chunkChars()
        val temp = Prompts.temperature(mode)
        if (t.length > max && (mode == Mode.SUMMARY || mode == Mode.SIMPLE || mode == Mode.CARDS)) {
            val f = flow {
                val condensed = condense(t, allowBackground) { i, n -> emit("Читаю часть $i из $n…") }
                emit("Собираю итог…")
                emitAll(stream(Prompts.build(mode, condensed, settings, preset), allowBackground, temp).second
                    .map { Clean.output(it, mode) })
            }
            return label() to f
        }
        if (t.length > max && mode != Mode.EVENT && mode != Mode.SCAM)
            throw EngineException("Текст длинный (${t.length} симв.), модель за раз берёт ~$max. " +
                    "Для длинных текстов используй «Главное» или «Объяснить проще» — они читают по частям.")
        val (l, f) = stream(Prompts.build(mode, t.take(max), settings, preset), allowBackground, temp)
        return l to f.map { Clean.output(it, mode) }
    }
}

/** Убирает типичный мусор маленьких моделей из ответа. */
object Clean {
    private val think = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)
    private val preamble = Regex(
        "^\\s*(конечно|разумеется|хорошо|вот|ниже|итак|sure|here is|here's)[^\\n]{0,90}?:\\s*\\n?",
        RegexOption.IGNORE_CASE
    )
    private val label = Regex(
        "^\\s*(исправленный|переписанный|переведённый|переведенный|сокращённый|сокращенный|итоговый|новый)?\\s*" +
                "(текст|вариант|перевод|результат|ответ)\\s*:\\s*", RegexOption.IGNORE_CASE
    )
    private val fence = Regex("^```[a-zA-Z]*\\s*|\\s*```\\s*$")

    fun output(raw: String, mode: Mode?): String {
        var s = raw.replace(think, "")
        if (mode == Mode.EVENT) return s.trim()
        s = s.replace(fence, "")
        repeat(2) { s = s.replace(preamble, "").replace(label, "") }
        s = s.trim()
        // Снимаем кавычки, если ими обёрнут весь ответ
        if (s.length > 2 && ((s.first() == '«' && s.last() == '»') || (s.first() == '"' && s.last() == '"')) &&
            s.count { it == s.first() } == 1) s = s.substring(1, s.length - 1).trim()
        // Повторяющиеся подряд строки (частый признак зацикливания)
        val lines = s.lines()
        val out = ArrayList<String>(lines.size)
        for (l in lines) if (out.isEmpty() || l.isBlank() || out.last().trim() != l.trim()) out += l
        return out.joinToString("\n").trim()
    }
}


fun isCharging(ctx: Context): Boolean = ctx.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
