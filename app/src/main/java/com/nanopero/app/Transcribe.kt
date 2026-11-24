package com.nanopero.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Расшифровка голосовых: «Поделиться» аудио → декодирование в PCM → распознавание на устройстве.
 * Требует Android 13+ (передача аудио в распознаватель) и офлайн-пакет языка.
 */
/** Растущий массив short без упаковки в объекты (экономит сотни МБ на длинных записях). */
private class ShortBuf {
    private var a = ShortArray(1 shl 18)
    var size = 0; private set
    fun add(v: Short) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray() = a.copyOf(size)
}

object VoiceFile {
    private const val TARGET_RATE = 16_000
    private const val MAX_SECONDS = 15 * 60

    /** Декодирует любой поддерживаемый системой аудиофайл (ogg/opus, m4a, mp3…) в моно PCM 16 кГц. */
    fun decode(ctx: Context, uri: Uri, onProgress: (Float) -> Unit): ShortArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("В файле нет аудио")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val mono = ShortBuf()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = ob.remaining() / channels
                        for (f in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += ob.get()
                            mono.add((sum / channels).toShort())
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        if (mono.size > rate * MAX_SECONDS) outDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        // Линейный ресемплинг в 16 кГц
        if (rate == TARGET_RATE) return mono.toArray()
        val outLen = (mono.size.toLong() * TARGET_RATE / rate).toInt()
        val out = ShortArray(outLen)
        val step = rate.toDouble() / TARGET_RATE
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt(); val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val fr = pos - i0
            out[i] = (mono[i0] * (1 - fr) + mono[i1] * fr).toInt().toShort()
        }
        return out
    }

    /** Отдаёт PCM распознавателю на устройстве и собирает текст (по сегментам). */
    suspend fun recognize(ctx: Context, pcm: ShortArray, lang: String, onPartial: (String) -> Unit): String {
        if (Build.VERSION.SDK_INT < 33) throw IllegalStateException("Расшифровка файлов работает на Android 13 и новее")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            throw IllegalStateException("Нет распознавания речи на устройстве. Скачай язык: Настройки → Система → Языки → Распознавание речи на устройстве")
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CompletableDeferred<String>()
        val sb = StringBuilder()
        val rec = withContext(Dispatchers.Main) { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }
        withContext(Dispatchers.Main) {
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onPartial((sb.toString() + " " + it).trim()) }
                }
                override fun onSegmentResults(b: Bundle) {
                    b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        if (it.isNotBlank()) { sb.append(it.trim()).append(' '); onPartial(sb.toString().trim()) }
                    }
                }
                override fun onEndOfSegmentedSession() { done.complete(sb.toString().trim()) }
                override fun onResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { sb.append(it.trim()) }
                    done.complete(sb.toString().trim())
                }
                override fun onError(code: Int) {
                    if (sb.isNotBlank()) done.complete(sb.toString().trim())
                    else done.completeExceptionally(IllegalStateException(
                        if (code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                            "Язык $lang не скачан для офлайн-распознавания" else "Ошибка распознавания ($code)"))
                }
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            rec.startListening(i)
        }
        // Пишем звук в трубу в фоне
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                val bytes = ByteArray(8192)
                var idx = 0
                while (idx < pcm.size) {
                    var b = 0
                    while (b < bytes.size && idx < pcm.size) {
                        val v = pcm[idx++].toInt()
                        bytes[b++] = (v and 0xff).toByte(); bytes[b++] = ((v shr 8) and 0xff).toByte()
                    }
                    out.write(bytes, 0, b)
                }
            }
        }
        return try { done.await() } finally {
            withContext(Dispatchers.Main) { runCatching { rec.destroy() } }
            runCatching { pipe[0].close() }
        }
    }
}

class TranscribeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        val uri: Uri? = intent?.getParcelableExtra(Intent.EXTRA_STREAM) ?: intent?.data
        setContent { NanoTheme { TranscribeSheet(uri) { finish() } } }
    }
}

@Composable
private fun TranscribeSheet(uri: Uri?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val r = rememberRunner()
    var stage by remember { mutableStateOf("Готовлю файл…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    var permOk by remember { mutableStateOf(
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        permOk = ok; if (!ok) { err = "Распознавателю речи нужен доступ к микрофону (сам микрофон не используется)"; busy = false }
    }
    LaunchedEffect(Unit) { if (!permOk) perm.launch(android.Manifest.permission.RECORD_AUDIO) }

    LaunchedEffect(uri, permOk) {
        if (!permOk) return@LaunchedEffect
        if (uri == null) { err = "Файл не получен"; busy = false; return@LaunchedEffect }
        try {
            stage = "Декодирую аудио…"
            val pcm = withContext(Dispatchers.Default) { VoiceFile.decode(ctx, uri) { progress = it } }
            stage = "Распознаю речь на телефоне (${pcm.size / 16_000} с)…"
            text = VoiceFile.recognize(ctx, pcm, app.settings.speechLang) { text = it }
            if (text.isBlank()) err = "Речь не распознана"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { err = e.message ?: "Не удалось расшифровать" } finally { busy = false }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Расшифровка голосового", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть") }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) { NanoLoader(28.dp); Spacer(Modifier.width(12.dp)); Text(stage) }
                if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (text.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    SelectionContainer { Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                if (!busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ r.run(Mode.SUMMARY, text) }, enabled = !r.busy) {
                            Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Главное")
                        }
                        FilledTonalButton({
                            app.stores.diary.add(DiaryEntry(newId(), System.currentTimeMillis(), text))
                            SnackHost.show(ctx, "Сохранено в дневник")
                        }) { Text("В дневник") }
                        FilledTonalIconButton({ ctx.copyText(text) }) { Icon(Icons.Rounded.ContentCopy, "Копировать") }
                    }
                }
            }
            ResultCard(r, onCopy = { ctx.copyText(it) }, onShare = { ctx.shareText(it) })
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(SnackHost.state) }
}
