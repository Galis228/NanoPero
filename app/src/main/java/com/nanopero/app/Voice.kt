package com.nanopero.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Непрерывная диктовка: системный распознаватель останавливается после паузы,
 * поэтому перезапускаем его, пока пользователь не нажмёт «Стоп».
 * На Android 12+ при наличии используется полностью офлайн-распознаватель на устройстве.
 */
class Dictation(private val ctx: Context, private val lang: String) {
    var listening by mutableStateOf(false); private set
    var committed by mutableStateOf(""); private set
    var partial by mutableStateOf(""); private set
    var level by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null)
    var onDevice by mutableStateOf(false); private set

    private var rec: SpeechRecognizer? = null
    private var wanted = false
    private var errorsInRow = 0

    val text get() = (committed + " " + partial).trim()

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx) &&
            !(Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))) {
            error = "На телефоне нет службы распознавания речи. Установи/включи «Google» или «Распознавание речи Google»."
            return
        }
        error = null; wanted = true; errorsInRow = 0
        listening = true
        launch()
    }

    private fun create(): SpeechRecognizer {
        val dev = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        onDevice = dev
        return if (dev) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    private fun launch() {
        if (!wanted) return
        val r = rec ?: create().also { rec = it; it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        runCatching { r.startListening(i) }.onFailure { error = it.message; stop() }
    }

    fun stop() {
        wanted = false
        listening = false
        if (partial.isNotBlank()) { committed = (committed + " " + partial).trim(); partial = "" }
        rec?.runCatching { stopListening(); destroy() }
        rec = null
        level = 0f
    }

    fun reset() { committed = ""; partial = "" }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level = 0f }
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
        }
        override fun onResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) committed = (committed + " " + t).trim()
            partial = ""; errorsInRow = 0
            launch()
        }
        override fun onError(code: Int) {
            partial = ""
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Тишина — просто слушаем дальше, но не бесконечно
                    if (++errorsInRow > 6) { stop(); return }
                    launch()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { error = "Нет доступа к микрофону"; stop() }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    error = "Язык $lang недоступен офлайн. Скачай его: Настройки телефона → Система → Языки → " +
                            "Распознавание речи на устройстве."
                    stop()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    rec?.runCatching { destroy() }; rec = null
                    if (++errorsInRow > 3) { error = "Распознаватель занят"; stop() } else launch()
                }
                else -> { error = "Ошибка распознавания ($code)"; stop() }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
