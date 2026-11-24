package com.nanopero.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class EngineChoice(val title: String) { AUTO("Авто"), NANO("Gemini Nano"), LOCAL("Файл-модель") }
enum class Template(val title: String) { AUTO("Авто"), NONE("Без шаблона"), GEMMA("Gemma"), CHATML("ChatML / Qwen"), LLAMA("Llama 3") }

/** Простые настройки в SharedPreferences, наблюдаемые из Compose. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var engine by pref("engine", EngineChoice.AUTO.name) { v -> v }
    var activeModel by pref("activeModel", "") { it }
    var template by pref("template", Template.AUTO.name) { it }
    var maxTokens by prefInt("maxTokens", 2048)
    var useGpuGlobal by prefBool("useGpu", false)
    private var gpuMap by pref("gpuMap", "") { it }
    /** Видеочип выбирается отдельно для каждой модели (по результатам теста скорости). */
    var useGpu: Boolean
        get() = gpuMap.split(';').firstOrNull { it.substringBefore('=') == activeModel && activeModel.isNotBlank() }
            ?.substringAfter('=')?.let { it == "1" } ?: useGpuGlobal
        set(v) {
            if (activeModel.isBlank()) { useGpuGlobal = v; return }
            gpuMap = (gpuMap.split(';').filter { it.isNotBlank() && it.substringBefore('=') != activeModel } + "$activeModel=${if (v) 1 else 0}").joinToString(";")
        }
    var temperature by prefFloat("chatTemp", 0.7f)
    var translateTo by pref("translateTo", "английский") { it }
    var customPrompt by pref("customPrompt", "Перепиши текст как пост для Telegram-канала с эмодзи.") { it }
    var lastMode by pref("lastMode", Mode.FIX.name) { it }
    var historyEnabled by prefBool("historyEnabled", true)
    var notifAllApps by prefBool("notifAllApps", false)
    var digestEnabled by prefBool("digestEnabled", true)
    var notifApps by pref("notifApps", DEFAULT_MESSENGERS.joinToString(",")) { it }
    var reminderOn by prefBool("reminderOn", false)
    var reminderHour by prefInt("reminderHour", 21)
    var lockEnabled by prefBool("lockEnabled", false)
    var smsGuard by prefBool("smsGuard", true)
    // --- клавиатура ---
    var kbHeight by prefFloat("kbHeight", 1.0f)
    var kbTextSize by prefInt("kbTextSize", 21)
    var kbBottom by prefInt("kbBottom", 10)
    var kbGap by prefInt("kbGap", 5)
    var kbNumberRow by prefBool("kbNumberRow", false)
    var kbHaptic by prefBool("kbHaptic", true)
    var kbSuggest by prefBool("kbSuggest", true)
    var kbAutocorrect by prefBool("kbAutocorrect", true)
    var kbLearn by prefBool("kbLearn", true)
    var kbAutoCaps by prefBool("kbAutoCaps", true)
    var kbDoubleSpace by prefBool("kbDoubleSpace", true)
    var kbLang by prefInt("kbLang", 0)
    var kbSide by prefInt("kbSide", 0)
    var kbRadius by prefInt("kbRadius", 8)
    var kbSound by prefBool("kbSound", false)
    var kbSmartQuotes by prefBool("kbSmartQuotes", true)
    var kbOneHand by prefInt("kbOneHand", 0)   // 0 — нет, 1 — слева, 2 — справа
    var kbGlide by prefBool("kbGlide", true)
    var kbObscene by prefBool("kbObscene", false)
    var kbEmojiSuggest by prefBool("kbEmojiSuggest", true)
    var kbAiContinue by prefBool("kbAiContinue", false)
    var kbTheme by prefInt("kbTheme", 0)        // 0 системная, 1 светлая, 2 тёмная, 3 чёрная (AMOLED), 4 картинка
    var sharedModels by prefBool("sharedModels", false) // общая папка моделей с «Персоной»
    // --- батарея ---
    var unloadSec by prefInt("unloadSec", 90)          // через сколько выгружать модель из памяти
    var heavyOnCharge by prefBool("heavyOnCharge", false) // тяжёлое (сводки, тесты) — только на зарядке
    // --- чат ---
    var think by prefBool("think", false)   // «Думать перед ответом» (режим размышлений)
    var persona by prefInt("persona", 0)
    var customPersona by pref("customPersona", "") { it }
    var kbBgStamp by prefLong("kbBgStamp", 0L)
    var kbBorders by prefBool("kbBorders", false)
    var kbSplit by prefInt("kbSplit", 0)        // 0 авто (широкий экран), 1 всегда, 2 никогда
    var kbTrLang by pref("kbTrLang", "английский") { it }
    var snippets by pref("snippets", "") { it }
    val snippetList get() = snippets.split('\u0001').filter { it.isNotBlank() }
    fun setSnippets(l: List<String>) { snippets = l.joinToString("\u0001") }
    var kbPreview by prefBool("kbPreview", true)
    var onboarded by prefBool("onboarded", false)
    var speechLang by pref("speechLang", "ru-RU") { it }

    var notifMuted by pref("notifMuted", "") { it }
    val mutedSet get() = notifMuted.split('\u0001').filter { it.isNotEmpty() }.toSet()
    fun mute(key: String) { notifMuted = (mutedSet + key).joinToString("\u0001") }
    fun unmute(key: String) { notifMuted = (mutedSet - key).joinToString("\u0001") }

    val notifAppSet get() = notifApps.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        val DEFAULT_MESSENGERS = listOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.discord", "com.slack", "com.facebook.orca",
            "com.instagram.android", "com.google.android.gm", "com.vkontakte.android", "com.microsoft.teams"
        )
    }

    val engineChoice get() = runCatching { EngineChoice.valueOf(engine) }.getOrDefault(EngineChoice.AUTO)
    val templateChoice get() = runCatching { Template.valueOf(template) }.getOrDefault(Template.AUTO)

    // --- делегаты ---
    private fun pref(key: String, def: String, map: (String) -> String) =
        object : kotlin.properties.ReadWriteProperty<Any?, String> {
            var state = mutableStateOf(p.getString(key, def) ?: def)
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = map(state.value)
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
                state.value = value; p.edit().putString(key, value).apply()
            }
        }

    private fun prefInt(key: String, def: Int) =
        object : kotlin.properties.ReadWriteProperty<Any?, Int> {
            var state = androidx.compose.runtime.mutableIntStateOf(p.getInt(key, def))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.intValue
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) {
                state.value = value; p.edit().putInt(key, value).apply()
            }
        }

    private fun prefBool(key: String, def: Boolean) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            var state = mutableStateOf(p.getBoolean(key, def))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.value
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
                state.value = value; p.edit().putBoolean(key, value).apply()
            }
        }

    private fun prefLong(key: String, def: Long) =
        object : kotlin.properties.ReadWriteProperty<Any?, Long> {
            var state = androidx.compose.runtime.mutableLongStateOf(p.getLong(key, def))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.value
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long) {
                state.value = value; p.edit().putLong(key, value).apply()
            }
        }

    private fun prefFloat(key: String, def: Float) =
        object : kotlin.properties.ReadWriteProperty<Any?, Float> {
            var state = androidx.compose.runtime.mutableFloatStateOf(p.getFloat(key, def))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.value
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) {
                state.value = value; p.edit().putFloat(key, value).apply()
            }
        }
}
