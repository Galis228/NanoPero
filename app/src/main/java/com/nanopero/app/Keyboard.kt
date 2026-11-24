package com.nanopero.app

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.asImageBitmap
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.PushPin as PushPinOutlined
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Клавиатура НаноПеро: ввод с подсказками и автоисправлением, эмодзи и кнопка ✦ с ИИ. */
class NanoIme : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val ssrc = SavedStateRegistryController.create(this)
    private val vms = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore get() = vms
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val state = KbState()
    val s get() = app.settings
    private val user get() = app.userDict

    // --- состояние ввода слова ---
    private val composing = StringBuilder()
    private var prevWord: String? = null
    private data class Undo(val original: String, val corrected: String, val sep: String)
    private var undo: Undo? = null
    private var autoSpaced = false
    private var lastSpaceAt = 0L
    private var sugJob: Job? = null
    private var sugFor = ""
    private var privateField0 = false  // пароль и т.п.: не учимся и не подсказываем
    /** Поле приватное или включён режим инкогнито. */
    private val privateField get() = privateField0 || state.incognito
    private var noCorrect = false      // email/url: без автоисправления

    override fun onCreate() {
        super.onCreate()
        ssrc.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        state.lang = s.kbLang.coerceIn(0, kbLayouts.lastIndex)
        loadClips()
        loadTaps()
        runCatching { getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener) }
        val l = lang()
        scope.launch(Dispatchers.IO) {
            loadDicts(l)
            runCatching { Emojis.load(this@NanoIme); Emojis.buildIndex() }
            runCatching { GboardImport.autoSyncSystem(this@NanoIme) }
        }
    }

    override fun onCreateInputView(): View {
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.decorView.setViewTreeLifecycleOwner(this)
            w.decorView.setViewTreeViewModelStoreOwner(this)
            w.decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@NanoIme)
            setViewTreeViewModelStoreOwner(this@NanoIme)
            setViewTreeSavedStateRegistryOwner(this@NanoIme)
            // Нижний системный отступ (жестовая панель, кнопки «скрыть»/«сменить клавиатуру»)
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, ins ->
                val b = ins.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.tappableElement()).bottom
                state.navInsetPx = b
                ins
            }
            setContent { NanoTheme(snackbars = false, kbTheme = s.kbTheme) { KeyboardUi(this@NanoIme) } }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lc(Lifecycle.Event.ON_RESUME)
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        state.numpad = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        state.symbols = false
        privateField0 = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
                (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) ||
                ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        noCorrect = privateField || cls != InputType.TYPE_CLASS_TEXT || variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER
        )
        state.private = privateField0
        state.ai = AiPanel.CLOSED; state.emoji = false; state.emojiSearch = false
        composing.clear(); prevWord = null; undo = null; autoSpaced = false
        ime_haptic = s.kbHaptic
        updateShift(); refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (tapDirty > 0) { tapDirty = 0; val e = tapPrefs.edit(); tapOffsets.forEach { (k, v) -> e.putString(k.toString(), "${v.x},${v.y}") }; e.apply() }
        if (state.ai == AiPanel.VOICE) { stopVoice(); state.ai = AiPanel.CLOSED }
        state.job?.cancel()
        currentInputConnection?.finishComposingText()
        composing.clear()
        lc(Lifecycle.Event.ON_PAUSE)
    }

    /** Безопасный переход жизненного цикла: после DESTROYED система ещё может вызвать onFinishInputView. */
    private fun lc(e: Lifecycle.Event) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        if (e == Lifecycle.Event.ON_RESUME && lifecycleRegistry.currentState == Lifecycle.State.RESUMED) return
        runCatching { lifecycleRegistry.handleLifecycleEvent(e) }
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        // Пользователь переставил курсор — заканчиваем текущее слово
        if (composing.isNotEmpty() && (nss != ce || nse != ce)) {
            composing.clear(); currentInputConnection?.finishComposingText(); undo = null; autoSpaced = false
            refreshSuggestions()
        }
    }

    override fun onDestroy() {
        runCatching { getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener) }
        scope.cancel()
        runCatching { super.onDestroy() } // внутри вызывает onFinishInputView — до DESTROYED
        lc(Lifecycle.Event.ON_DESTROY)
        vms.clear()
    }

    fun lang() = Dicts.langs[state.lang]

    private fun loadDicts(l: String) {
        runCatching { Dicts.load(this, l) }
        Dicts.partnerOf(l)?.let { p -> runCatching { Dicts.load(this, p) } }
    }

    /** Слово с большой буквы не в начале предложения — скорее имя, не исправляем. */
    private fun midCap(w: String) = prevWord != null && w.firstOrNull()?.isUpperCase() == true

    // ---------- ввод ----------

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’'

    // ---------- пространственная модель и подстройка под пальцы ----------

    /** Центры букв и размер клавиш текущей раскладки (обновляет KeyCanvas). */
    var geo: Triple<Map<Char, androidx.compose.ui.geometry.Offset>, Float, Float>? = null
    private val composingPts = ArrayList<androidx.compose.ui.geometry.Offset?>()
    private val tapOffsets = HashMap<Char, androidx.compose.ui.geometry.Offset>()
    private val tapPrefs by lazy { getSharedPreferences("tapoff", Context.MODE_PRIVATE) }
    private var tapDirty = 0

    private fun loadTaps() {
        tapPrefs.all.forEach { (k, v) ->
            val p = (v as? String)?.split(',') ?: return@forEach
            if (k.length == 1 && p.size == 2) tapOffsets[k[0]] = androidx.compose.ui.geometry.Offset(p[0].toFloat(), p[1].toFloat())
        }
    }

    private fun spatial(): Spatial? {
        val g = geo ?: return null
        if (composingPts.size != composing.length || composingPts.none { it != null }) return null
        return Spatial(ArrayList(composingPts), g.first, g.second, g.third)
    }

    /**
     * Уточнить клавишу по личным привычкам: если касание у границы соседних букв,
     * выбираем ту, к чьему «твоему» центру (центр + средний сдвиг) оно ближе.
     */
    fun personalHit(keys: List<KeyRect>, p: androidx.compose.ui.geometry.Offset, base: KeyRect): KeyRect {
        if (!base.def.letter || tapOffsets.isEmpty()) return base
        val w = base.rect.width; val h = base.rect.height
        var best = base; var bestD = Float.MAX_VALUE
        for (k in keys) {
            if (!k.def.letter) continue
            if (kotlin.math.abs(k.rect.center.y - base.rect.center.y) > h * 1.1f) continue
            if (kotlin.math.abs(k.rect.center.x - base.rect.center.x) > w * 1.6f) continue
            val o = tapOffsets[k.def.label[0]] ?: androidx.compose.ui.geometry.Offset.Zero
            val dx = (p.x - (k.rect.center.x + o.x)) / w; val dy = (p.y - (k.rect.center.y + o.y)) / h
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Запоминаем средний сдвиг касаний для клавиши (плавно, с ограничением). */
    fun learnTap(c: Char, p: androidx.compose.ui.geometry.Offset, r: androidx.compose.ui.geometry.Rect) {
        if (!r.contains(p) || privateField) return
        val old = tapOffsets[c] ?: androidx.compose.ui.geometry.Offset.Zero
        val nx = (old.x * 0.96f + (p.x - r.center.x) * 0.04f).coerceIn(-r.width * 0.3f, r.width * 0.3f)
        val ny = (old.y * 0.96f + (p.y - r.center.y) * 0.04f).coerceIn(-r.height * 0.3f, r.height * 0.3f)
        tapOffsets[c] = androidx.compose.ui.geometry.Offset(nx, ny)
        if (++tapDirty >= 40) {
            tapDirty = 0
            val e = tapPrefs.edit(); tapOffsets.forEach { (k, v) -> e.putString(k.toString(), "${v.x},${v.y}") }; e.apply()
        }
    }

    fun resetTaps() { tapOffsets.clear(); tapPrefs.edit().clear().apply() }

    fun type(raw0: String, at: androidx.compose.ui.geometry.Offset? = null) {
        if (state.emojiSearch) { state.emojiQuery += raw0; return }
        if (state.ai == AiPanel.TRANSLATE) {
            state.trText += if (state.shift != Shift.OFF) raw0.uppercase() else raw0
            if (state.shift == Shift.ONCE) state.shift = Shift.OFF
            scheduleTranslate(); return
        }
        state.continuation = null; contJob?.cancel()
        val ic = currentInputConnection ?: return
        // «Ёлочки» в русском и украинском
        val raw = if (raw0 == "\"" && s.kbSmartQuotes && state.lang != 2) {
            val b = if (composing.isNotEmpty()) composing.last().toString() else ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (b.isEmpty() || b[0].isWhitespace() || b[0] in "([{«") "«" else "»"
        } else raw0
        lastGlide = null
        val out = if (state.shift != Shift.OFF) raw.uppercase() else raw
        val c = out[0]
        undo = null
        if (isWordChar(c) || (c == '-' && composing.isNotEmpty())) {
            if (composing.isEmpty()) composingPts.clear()
            composing.append(out)
            repeat(out.length) { composingPts += if (it == 0) at else null }
            ic.setComposingText(composing, 1)
            autoSpaced = false
            refreshSuggestions()
        } else {
            separator(out)
        }
        if (state.shift == Shift.ONCE) state.shift = Shift.OFF
        updateShift()
    }

    private fun separator(sep: String) {
        val ic = currentInputConnection ?: return
        // «слово␣» + «.» → «слово.» (как в Gboard)
        if (autoSpaced && sep in setOf(".", ",", "!", "?", ":", ";", ")", "…") &&
            ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
        autoSpaced = false
        if (composing.isNotEmpty()) {
            // Исправляем на пробеле и любом знаке-разделителе; на цифрах, @, # и т.п. — нет
            commitWord(sep, correct = sep in correctOn)
            return
        }
        // Двойной пробел → точка
        if (sep == " " && s.kbDoubleSpace && System.currentTimeMillis() - lastSpaceAt < 450) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); lastSpaceAt = 0; prevWord = null
                refreshSuggestions(); return
            }
        }
        if (sep == " ") lastSpaceAt = System.currentTimeMillis()
        ic.commitText(sep, 1)
        if (sep != " ") prevWord = null
        refreshSuggestions()
    }

    /** Слова, исправление которых ты отменил ⌫ в этой сессии, — больше не исправляем (как Gboard). */
    private val rejected = HashSet<String>()
    private val correctOn = setOf(" ", ".", ",", "!", "?", ":", ";", "…", ")", "]", "}", "»", "\"", "\n")

    private fun commitWord(sep: String, correct: Boolean) {
        val ic = currentInputConnection ?: return
        val typed = composing.toString()
        var final = typed
        if (correct && s.kbAutocorrect && !noCorrect) {
            sugJob?.cancel()
            val sg = if (sugFor == typed && state.sugs.typed == typed) state.sugs
            else Suggest.compute(lang(), Dicts.get(lang()), user, typed, prevWord, true, midCap(typed), s.kbObscene, spatial())
            if (typed.lowercase() !in rejected) sg.autocorrect?.let { final = it }
        }
        ic.commitText(final + sep, 1)
        if (final != typed) undo = Undo(typed, final, sep)
        final.split(' ').forEach { learn(it, confirmed = false) }
        prevWord = if (sep == " " || sep == "," || sep == ":" || sep == ";") final.substringAfterLast(' ') else null
        if (sep == " ") lastSpaceAt = System.currentTimeMillis()
        composing.clear()
        refreshSuggestions()
        if (sep == " ") scheduleContinuation()
    }

    private fun learn(w: String, confirmed: Boolean) {
        if (privateField || !s.kbLearn) return
        val inDict = (Dicts.get(lang())?.find(w.lowercase()) ?: -1) >= 0
        user.learn(lang(), w, prevWord, inDict, confirmed)
    }

    fun backspace() {
        if (state.emojiSearch) { state.emojiQuery = state.emojiQuery.dropLast(1); return }
        if (state.ai == AiPanel.TRANSLATE) { state.trText = state.trText.dropLast(1); scheduleTranslate(); return }
        state.continuation = null; contJob?.cancel()
        val ic = currentInputConnection ?: return
        // Отмена автоисправления
        undo?.takeIf { u -> ic.getTextBeforeCursor(u.corrected.length + u.sep.length, 0)?.toString() == u.corrected + u.sep }?.let { u ->
            // Как в Gboard: возвращаем набранное слово в «редактируемое» состояние, без пробела, и больше его не трогаем
            ic.deleteSurroundingText(u.corrected.length + u.sep.length, 0)
            rejected += u.original.lowercase()
            if (!privateField && s.kbLearn) user.learn(lang(), u.original, null, inDict = false, confirmed = true)
            composing.clear(); composing.append(u.original)
            ic.setComposingText(u.original, 1)
            undo = null; refreshSuggestions(); return
        }
        undo = null
        autoSpaced = false
        if (composing.isNotEmpty()) {
            val cp = Character.codePointBefore(composing, composing.length)
            composing.setLength(composing.length - Character.charCount(cp))
            while (composingPts.size > composing.length) composingPts.removeAt(composingPts.lastIndex)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
            refreshSuggestions(); updateShift(); return
        }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else {
            val before = ic.getTextBeforeCursor(2, 0)
            val n = if (before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
            if (before.isNullOrEmpty()) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)).also {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            } else {
                ic.deleteSurroundingText(n, 0)
                recomposeBeforeCursor()
            }
        }
        updateShift(); refreshSuggestions()
    }

    /**
     * Стёрли до слова — делаем его снова «текущим» целиком (как в Gboard):
     * подсказки и автоисправление снова работают по всему слову, а не по допечатанному кусочку.
     */
    private fun recomposeBeforeCursor() {
        if (privateField || !s.kbSuggest) return
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
        if (after.isNotEmpty() && isWordChar(after[0])) return
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        var i = before.length
        while (i > 0 && (isWordChar(before[i - 1]) || (before[i - 1] == '-' && i - 1 > 0 && isWordChar(before[i - 2])))) i--
        val word = before.substring(i)
        if (word.isEmpty() || word.length > 40 || word.any { it.isDigit() }) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.setComposingText(word, 1)
        ic.endBatchEdit()
        composing.clear(); composing.append(word)
        composingPts.clear(); repeat(word.length) { composingPts += null }
        // Предыдущее слово — для подсказок по контексту
        val rest = before.substring(0, i).trimEnd()
        prevWord = if (rest.isEmpty() || rest.last() in ".!?\n") null
        else rest.takeLastWhile { isWordChar(it) }.ifEmpty { null }
    }

    fun pick(word: String) {
        val ic = currentInputConnection ?: return
        lastGlide?.let { g ->
            if (ic.getTextBeforeCursor(g.length + 1, 0)?.toString() == "$g ") {
                ic.deleteSurroundingText(g.length + 1, 0); ic.commitText("$word ", 1)
                learn(word, confirmed = true); lastGlide = word; prevWord = word
                state.sugs = Suggestions(listOf(word) + state.glideAlts.filter { it != word } + listOfNotNull(g.takeIf { it != word }), null, true, word)
                return
            }
        }
        // Предсказание следующего слова: если курсор сразу после слова/знака без пробела — добавляем пробел перед ним
        val lead = if (composing.isEmpty()) ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            .let { if (it.isNotEmpty() && !it[0].isWhitespace() && it[0] !in "([{«\"'") " " else "" } else ""
        ic.commitText("$lead$word ", 1)
        learn(word, confirmed = true)
        prevWord = word; composing.clear(); undo = null; autoSpaced = true
        lastSpaceAt = 0
        if (state.shift == Shift.ONCE) state.shift = Shift.OFF
        updateShift(); refreshSuggestions()
    }

    fun enter() {
        val ic = currentInputConnection ?: return
        if (state.ai == AiPanel.TRANSLATE) { insertTranslation(); return }
        // Enter тоже завершает слово — с автоисправлением
        if (composing.isNotEmpty()) commitWord("", correct = true)
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiline = (ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!noEnterAction && !multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(action)
        else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        prevWord = null; autoSpaced = false
        updateShift(); refreshSuggestions()
    }

    /** Эмодзи из подсказок: после слова. */
    fun pickEmoji(e: String) {
        if (composing.isNotEmpty()) commitWord(" ", correct = true)
        else { val b = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty(); if (b.isNotEmpty() && b[0].isLetterOrDigit()) currentInputConnection?.commitText(" ", 1) }
        commitEmoji(e); currentInputConnection?.commitText(" ", 1); autoSpaced = true
        state.emojiSug = emptyList()
    }

    fun commitEmoji(e: String) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitWord("", correct = false)
        ic.commitText(e, 1)
        Emojis.use(this, e)
        autoSpaced = false; prevWord = null
    }

    fun updateShift() {
        if (state.shift == Shift.LOCK) return
        if (!s.kbAutoCaps || composing.isNotEmpty()) { if (state.shift == Shift.ONCE && composing.isNotEmpty()) state.shift = Shift.OFF; return }
        val ei = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0
        state.shift = if (caps != 0) Shift.ONCE else Shift.OFF
    }

    fun switchLang() {
        if (composing.isNotEmpty()) commitWord("", correct = false)
        state.lang = (state.lang + 1) % kbLayouts.size
        s.kbLang = state.lang
        val l = lang(); scope.launch(Dispatchers.IO) { loadDicts(l) }
        state.symbols = false
        refreshSuggestions()
        toast(kbLayouts[state.lang].title)
    }

    fun refreshSuggestions() {
        sugJob?.cancel()
        if (!s.kbSuggest || privateField) { state.sugs = Suggestions.EMPTY; state.emojiSug = emptyList(); return }
        val typed = composing.toString()
        val prev = prevWord
        val lang = lang()
        val sp = spatial()
        sugJob = scope.launch {
            val res = withContext(Dispatchers.Default) {
                val d = Dicts.get(lang) ?: runCatching { Dicts.load(this@NanoIme, lang) }.getOrNull()
                Suggest.compute(lang, d, user, typed, prev, s.kbAutocorrect && !noCorrect,
                    prev != null && typed.firstOrNull()?.isUpperCase() == true, s.kbObscene, sp)
            }
            sugFor = typed
            state.sugs = if (typed.lowercase() in rejected) res.copy(autocorrect = null) else res
            state.emojiSug = if (s.kbEmojiSuggest && !privateField) Emojis.forWord(typed.ifEmpty { prev.orEmpty() }) else emptyList()
        }
    }

    // ---------- курсор, удаление словами, свайп-набор, буфер ----------

    fun moveCursor(dir: Int) {
        if (composing.isNotEmpty()) { currentInputConnection?.finishComposingText(); composing.clear() }
        undo = null; autoSpaced = false
        sendDownUpKeyEvents(if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
    }

    fun deleteWordBefore() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) { composing.clear(); ic.commitText("", 1); refreshSuggestions(); return }
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText(before.length - i, 0)
        undo = null; prevWord = null; updateShift(); refreshSuggestions()
    }

    private var lastGlide: String? = null

    /** Набор свайпом: лучший кандидат вставляется сразу, остальные — в подсказки. */
    fun commitGlide(seq: String, centers: Map<Char, androidx.compose.ui.geometry.Offset>, pathLen: Float, keyW: Float) {
        val ic = currentInputConnection ?: return
        val lang = lang()
        val cands = Glide.decode(Dicts.get(lang), user, lang, seq, centers, pathLen, keyW, s.kbObscene, prevWord)
        if (cands.isEmpty()) return
        // Свайп в поиске эмодзи и в «переводе на лету» пишет туда, а не в поле
        if (state.emojiSearch) { state.emojiQuery += (if (state.emojiQuery.isEmpty() || state.emojiQuery.endsWith(" ")) "" else " ") + cands[0]; return }
        if (state.ai == AiPanel.TRANSLATE) {
            state.trText += (if (state.trText.isEmpty() || state.trText.endsWith(" ")) "" else " ") + cands[0] + " "; scheduleTranslate(); return
        }
        if (composing.isNotEmpty()) commitWord(" ", correct = true)
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val cap = state.shift != Shift.OFF
        fun cased(w: String) = if (state.shift == Shift.LOCK) w.uppercase() else if (cap) w.replaceFirstChar { it.uppercase() } else w
        val word = cased(cands[0])
        val lead = if (before.isNotEmpty() && before[0].isLetterOrDigit()) " " else ""
        ic.commitText(lead + word + " ", 1)
        lastGlide = word
        learn(cands[0], confirmed = false)
        prevWord = word; autoSpaced = true; undo = null
        if (state.shift == Shift.ONCE) state.shift = Shift.OFF
        state.glideAlts = cands.drop(1).map { cased(it) }
        state.sugs = Suggestions(listOf(word) + state.glideAlts, null, true, word)
        sugFor = "\u0000glide"
    }

    fun click(t: KType) {
        if (!s.kbSound) return
        val am = getSystemService(android.media.AudioManager::class.java)
        am.playSoundEffect(when (t) {
            KType.BACK -> android.media.AudioManager.FX_KEYPRESS_DELETE
            KType.SPACE -> android.media.AudioManager.FX_KEYPRESS_SPACEBAR
            KType.ENTER -> android.media.AudioManager.FX_KEYPRESS_RETURN
            else -> android.media.AudioManager.FX_KEYPRESS_STANDARD
        }, -1f)
    }

    private val clipPrefs by lazy { getSharedPreferences("clips", Context.MODE_PRIVATE) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClip() }

    private fun loadClips() {
        val arr = runCatching { org.json.JSONArray(clipPrefs.getString("list", "[]")) }.getOrNull() ?: return
        state.clips.clear()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
            state.clips += Clip(it.optString("x"), it.optLong("t"), it.optBoolean("p"))
        }
    }

    private fun saveClips() {
        val arr = org.json.JSONArray()
        state.clips.forEach { arr.put(org.json.JSONObject().put("x", it.text).put("t", it.time).put("p", it.pinned)) }
        clipPrefs.edit().putString("list", arr.toString()).apply()
    }

    private fun onClip() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return
        // Не сохраняем то, что помечено как секретное (пароли из менеджеров паролей)
        val sensitive = android.os.Build.VERSION.SDK_INT >= 33 &&
                clip.description?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        val t = clip.getItemAt(0)?.coerceToText(this)?.toString()?.take(5000) ?: return
        if (t.isBlank()) return
        val c = Clip(t, System.currentTimeMillis())
        if (!sensitive && !state.incognito) {
            state.clips.removeAll { it.text == t && !it.pinned }
            if (state.clips.none { it.text == t }) state.clips.add(0, c)
            val unpinned = state.clips.filter { !it.pinned }
            if (unpinned.size > 10) state.clips.remove(unpinned.last())
            saveClips()
        }
        state.freshClip = c
    }

    fun pasteText(t: String) {
        if (composing.isNotEmpty()) commitWord("", correct = false)
        currentInputConnection?.commitText(t, 1)
        state.freshClip = null
    }

    fun togglePin(c: Clip) {
        val i = state.clips.indexOf(c); if (i < 0) return
        state.clips[i] = c.copy(pinned = !c.pinned); saveClips()
    }

    fun removeClip(c: Clip) { state.clips.remove(c); saveClips() }
    fun clearClips() { state.clips.removeAll { !it.pinned }; saveClips() }

    // ---------- голосовой ввод ----------

    var voice: Dictation? = null
        private set
    private var voiceCommitted = ""
    private var voiceJob: Job? = null

    fun toggleVoice() {
        if (state.ai == AiPanel.VOICE) { stopVoice(); state.ai = AiPanel.CLOSED; return }
        if (composing.isNotEmpty()) commitWord(" ", correct = true)
        state.voiceError = null; state.voiceNeedsPermission = false
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            state.voiceError = "Нужен доступ к микрофону. Клавиатура не может спросить его сама — открой разрешения приложения."
            state.voiceNeedsPermission = true
            state.ai = AiPanel.VOICE; return
        }
        val lang = listOf("ru-RU", "uk-UA", "en-US")[state.lang]
        val d = Dictation(this, lang)
        voice = d; voiceCommitted = ""
        state.ai = AiPanel.VOICE
        d.start()
        // Как только распознаватель «закрепил» фразу — вставляем её в поле
        voiceJob?.cancel()
        voiceJob = scope.launch {
            while (true) {
                delay(150)
                val c = d.committed
                if (c.length > voiceCommitted.length && c.startsWith(voiceCommitted)) {
                    val add = c.substring(voiceCommitted.length).trim()
                    if (add.isNotEmpty()) {
                        val ic = currentInputConnection
                        val before = ic?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                        val lead = if (before.isNotEmpty() && !before[0].isWhitespace()) " " else ""
                        val text = if (before.isEmpty() || before.trimEnd().endsWith(".") || before.trimEnd().endsWith("!") || before.trimEnd().endsWith("?"))
                            add.replaceFirstChar { it.uppercase() } else add
                        ic?.commitText(lead + text, 1)
                    }
                    voiceCommitted = c
                }
                d.error?.let { state.voiceError = it; break }
                if (!d.listening) { state.ai = AiPanel.CLOSED; break }
            }
        }
    }

    private fun stopVoice() {
        voice?.stop()
        // stop() переносит недоговорённое в committed — даём циклу вставить остаток
        val d = voice ?: return
        val c = d.committed
        if (c.length > voiceCommitted.length) {
            val add = c.substring(voiceCommitted.length).trim()
            if (add.isNotEmpty()) currentInputConnection?.commitText((if (voiceCommitted.isNotEmpty()) " " else "") + add, 1)
        }
        voiceJob?.cancel(); voice = null
    }

    fun openMicPermission() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSettings() {
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MORE, "keyboard").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- перевод на лету ----------

    private var trJob: Job? = null
    fun startTranslate() {
        if (composing.isNotEmpty()) commitWord(" ", correct = true)
        state.trText = ""; state.trResult = ""; state.ai = AiPanel.TRANSLATE; state.shift = Shift.ONCE
    }

    private fun scheduleTranslate() {
        trJob?.cancel()
        val src = state.trText
        if (src.isBlank()) { state.trResult = ""; return }
        trJob = scope.launch {
            delay(900)
            state.trBusy = true
            try {
                val prompt = "Переведи текст на язык: ${s.kbTrLang}. Естественно, как написал бы носитель. " +
                        "Выведи только перевод.\n\nТекст:\n$src"
                app.ai.stream(prompt, allowBackground = true, temperature = 0.2f).second.collect { state.trResult = Clean.output(it, Mode.TRANSLATE) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { state.trResult = "⚠ " + (e.message ?: "ошибка") } finally { state.trBusy = false }
        }
    }

    fun insertTranslation() {
        val t = state.trResult.takeIf { it.isNotBlank() && !it.startsWith("⚠") } ?: return
        currentInputConnection?.commitText(t, 1)
        state.trText = ""; state.trResult = ""; state.ai = AiPanel.CLOSED
    }

    // ---------- ИИ-поиск эмодзи ----------

    fun aiEmoji(q: String) {
        if (state.aiEmojiBusy) return
        state.aiEmojiBusy = true
        scope.launch {
            try {
                var out = ""
                app.ai.stream("Подбери 8 самых подходящих эмодзи к запросу «$q». Выведи только эмодзи через пробел, без слов.",
                    allowBackground = true, temperature = 0.3f).second.collect { out = it }
                val bi = java.text.BreakIterator.getCharacterInstance()
                bi.setText(out)
                val list = ArrayList<String>()
                var st0 = bi.first(); var en = bi.next()
                while (en != java.text.BreakIterator.DONE) {
                    val g = out.substring(st0, en)
                    if (g.codePointAt(0) >= 0x2190 && g.isNotBlank()) list += g
                    st0 = en; en = bi.next()
                }
                state.aiEmoji = list.distinct().take(12)
                if (list.isEmpty()) toast("ИИ не нашёл эмодзи")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { toast(e.message ?: "Модель недоступна") } finally { state.aiEmojiBusy = false }
        }
    }

    // ---------- ИИ-продолжение фразы ----------

    private var contJob: Job? = null
    private fun scheduleContinuation() {
        contJob?.cancel(); state.continuation = null
        if (!s.kbAiContinue || privateField || composing.isNotEmpty()) return
        if (s.activeModel.isBlank()) return
        contJob = scope.launch {
            delay(1300)
            val before = currentInputConnection?.getTextBeforeCursor(400, 0)?.toString().orEmpty()
            if (before.trim().length < 12 || !before.endsWith(" ")) return@launch
            try {
                val prompt = "Продолжи текст 3–8 словами так, как его скорее всего продолжит автор. Выведи только продолжение, " +
                        "без повтора начала и без кавычек.\n\nТекст:\n${before.trimEnd()}"
                var out = ""
                app.ai.stream(prompt, allowBackground = true, temperature = 0.3f).second.collect { out = it }
                val c = Clean.output(out, null).lineSequence().firstOrNull()?.trim()?.split(' ')?.take(8)?.joinToString(" ")
                if (!c.isNullOrBlank() && composing.isEmpty()) state.continuation = c
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {}
        }
    }

    fun acceptContinuation() {
        val c = state.continuation ?: return
        currentInputConnection?.commitText("$c ", 1)
        state.continuation = null; autoSpaced = true; prevWord = c.substringAfterLast(' ')
        refreshSuggestions()
    }

    // ---------- панель редактирования ----------

    fun editKey(code: Int, meta: Int = 0) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) { ic.finishComposingText(); composing.clear() }
        val t = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    fun editAction(id: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) { ic.finishComposingText(); composing.clear() }
        ic.performContextMenuAction(id)
    }

    var selecting by mutableStateOf(false)

    // ---------- заготовки ----------

    fun addSnippetFromClipboard(): Boolean {
        val t = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (t.isNullOrBlank()) { toast("Буфер обмена пуст"); return false }
        s.setSnippets((s.snippetList + t).distinct().takeLast(40)); toast("Заготовка добавлена"); return true
    }

    fun pasteClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (composing.isNotEmpty()) commitWord("", correct = false)
        currentInputConnection?.commitText(t, 1)
    }

    // ---------- ИИ ----------

    fun fieldText(): Triple<String, Int, Int>? {
        val ic = currentInputConnection ?: return null
        if (composing.isNotEmpty()) { ic.finishComposingText(); composing.clear() }
        val sel = ic.getSelectedText(0)?.toString()
        if (!sel.isNullOrBlank()) return Triple(sel, -1, -1)
        val before = ic.getTextBeforeCursor(6000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(6000, 0)?.toString().orEmpty()
        return Triple(before + after, before.length, after.length)
    }

    fun runAi(mode: Mode, preset: Preset? = null) {
        val (text, b, a) = fieldText() ?: return
        if (text.isBlank()) { state.aiError = "Поле пустое — напиши черновик или выдели текст"; state.ai = AiPanel.RESULT; return }
        state.job?.cancel()
        state.ai = AiPanel.RESULT; state.aiBusy = true; state.aiResult = ""; state.aiError = null
        state.aiTitle = if (mode == Mode.CUSTOM) preset?.name ?: "" else mode.title
        state.replaceRange = b to a
        state.job = scope.launch {
            try {
                val (_, flow) = app.ai.run(mode, text, preset, allowBackground = true)
                flow.collect { state.aiResult = it }
                state.aiResult = state.aiResult.trim()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { ErrorLog.add("Клавиатура ИИ", e); state.aiError = e.message } finally { state.aiBusy = false }
        }
    }

    fun applyAi() {
        val ic = currentInputConnection ?: return
        val (b, a) = state.replaceRange
        ic.beginBatchEdit()
        if (b >= 0) ic.deleteSurroundingText(b, a)
        ic.commitText(state.aiResult, 1)
        ic.endBatchEdit()
        state.ai = AiPanel.CLOSED; state.aiResult = ""
    }

    fun switchToNextIme() {
        val ok = android.os.Build.VERSION.SDK_INT >= 28 && switchToNextInputMethod(false)
        if (!ok) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private var toastJob: Job? = null
    /** Короткое сообщение в полосе подсказок на 2 секунды. */
    fun toast(msg: String) {
        state.lastToast = msg; state.toast = msg
        toastJob?.cancel(); toastJob = scope.launch { delay(2000); state.toast = null }
    }

    fun haptic(v: View, long: Boolean = false) {
        if (s.kbHaptic) v.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

enum class Shift { OFF, ONCE, LOCK }
enum class AiPanel { CLOSED, MODES, RESULT, TOOLS, CLIPS, VOICE, SNIPPETS, EDIT, TRANSLATE }

class KbState {
    var shift by mutableStateOf(Shift.ONCE)
    var symbols by mutableStateOf(false)
    var symPage by mutableIntStateOf(0)
    var lang by mutableIntStateOf(0)
    var ai by mutableStateOf(AiPanel.CLOSED)
    var aiBusy by mutableStateOf(false)
    var aiResult by mutableStateOf("")
    var aiError by mutableStateOf<String?>(null)
    var aiTitle by mutableStateOf("")
    var replaceRange = -1 to -1
    var job: Job? = null
    var sugs by mutableStateOf(Suggestions.EMPTY)
    var emoji by mutableStateOf(false)
    var emojiSearch by mutableStateOf(false)
    var emojiQuery by mutableStateOf("")
    var emojiLoaded by mutableStateOf(false)
    var navInsetPx by mutableIntStateOf(0)
    var private by mutableStateOf(false)
    var numpad by mutableStateOf(false)
    var voiceError by mutableStateOf<String?>(null)
    var emojiSug by mutableStateOf<List<String>>(emptyList())
    var incognito by mutableStateOf(false)
    var aiEmoji by mutableStateOf<List<String>>(emptyList())
    var aiEmojiBusy by mutableStateOf(false)
    var trText by mutableStateOf("")
    var trResult by mutableStateOf("")
    var trBusy by mutableStateOf(false)
    var continuation by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var lastToast by mutableStateOf("")
    var voiceNeedsPermission by mutableStateOf(false)
    val clips = mutableStateListOf<Clip>()
    var freshClip by mutableStateOf<Clip?>(null)
    var glideAlts = emptyList<String>()
}

data class Clip(val text: String, val time: Long, val pinned: Boolean = false)

@Composable
private fun keyH(ime: NanoIme): Dp = (50f * ime.s.kbHeight).dp

@Composable
private fun KeyboardUi(ime: NanoIme) {
    val st = ime.state
    val s = ime.s
    val view = LocalView.current
    val density = LocalDensity.current
    val bottom = with(density) { st.navInsetPx.toDp() } + s.kbBottom.dp
    val rowsCount = if (st.numpad) 4 else 4 + (if (s.kbNumberRow && !st.symbols) 1 else 0)
    val panelH = (keyH(ime) + s.kbGap.dp) * rowsCount
    val ctx = LocalContext.current
    val bg = remember(s.kbTheme, s.kbBgStamp) {
        if (s.kbTheme != 4) null else runCatching {
            val f = java.io.File(ctx.filesDir, "kb_bg.jpg")
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            o.inSampleSize = maxOf(1, o.outWidth / 1080); o.inJustDecodeBounds = false
            android.graphics.BitmapFactory.decodeFile(f.path, o)?.asImageBitmap()
        }.getOrNull()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box {
        if (bg != null) {
            androidx.compose.foundation.Image(bg, null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = bottom)) {
            if (s.kbOneHand == 2) OneHandBar(ime, panelH + 44.dp)
            Column(Modifier.weight(1f)) {
                if (!st.emoji) Box {
                    TopStrip(ime)
                    // Короткие сообщения клавиатуры («Добавлено», ошибки ИИ) — поверх полосы подсказок
                    androidx.compose.animation.AnimatedVisibility(st.toast != null,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                        exit = androidx.compose.animation.fadeOut(), modifier = Modifier.matchParentSize()) {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(st.lastToast, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                val panel = if (st.emoji) "emoji" else st.ai.name
                androidx.compose.animation.AnimatedContent(panel, transitionSpec = {
                    if (initialState == "TRANSLATE" || targetState == "TRANSLATE" ||
                        (initialState == "CLOSED" && targetState == "CLOSED")) androidx.compose.animation.EnterTransition.None togetherWith
                            androidx.compose.animation.ExitTransition.None
                    else (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160, 40)) +
                            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(200), initialScale = 0.96f)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80))
                }, label = "kbPanel") { p ->
                when {
                    p == "emoji" -> EmojiPanel(ime, panelH + 44.dp)
                    p == "MODES" -> AiModes(ime, panelH)
                    p == "RESULT" -> AiResult(ime, panelH)
                    p == "TOOLS" -> ToolsPanel(ime, panelH)
                    p == "CLIPS" -> ClipsPanel(ime, panelH)
                    p == "VOICE" -> VoicePanel(ime, panelH)
                    p == "SNIPPETS" -> SnippetsPanel(ime, panelH)
                    p == "EDIT" -> EditPanel(ime, panelH)
                    p == "TRANSLATE" -> Column { TranslateBox(ime); KeyCanvas(ime) }
                    else -> KeyCanvas(ime)
                }
                }
            }
            if (s.kbOneHand == 1) OneHandBar(ime, panelH + 44.dp)
        }
        }
    }
    LaunchedEffect(st.ai) { if (st.ai != AiPanel.CLOSED) ime.haptic(view) }
}

@Composable
private fun VoicePanel(ime: NanoIme, h: Dp) {
    val v = ime.voice
    val st = ime.state
    val pulse by androidx.compose.animation.core.animateFloatAsState(1f + (v?.level ?: 0f) * 0.35f, label = "p")
    Column(Modifier.fillMaxWidth().height(h).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (st.voiceError != null) {
            Text(st.voiceError!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            if (st.voiceNeedsPermission) Button({ ime.openMicPermission() }) { Text("Разрешить микрофон") }
            TextButton({ st.ai = AiPanel.CLOSED; st.voiceError = null }) { Text("Назад к клавиатуре") }
            return@Column
        }
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape))
            FilledIconButton({ ime.toggleVoice() }, Modifier.size(72.dp)) { Icon(Icons.Rounded.Mic, "Стоп", Modifier.size(36.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(v?.partial?.ifBlank { null } ?: "Говори… (${kbLayouts[st.lang].title})", maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Text("Нажми ещё раз, чтобы закончить", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TranslateBox(ime: NanoIme) {
    val st = ime.state
    val langs = listOf("английский" to "EN", "украинский" to "UA", "русский" to "RU", "польский" to "PL", "немецкий" to "DE", "испанский" to "ES", "французский" to "FR")
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(langs) { (l, code) -> FilterChip(ime.s.kbTrLang == l, { ime.s.kbTrLang = l }, { Text(code) }) }
            }
            IconButton({ st.ai = AiPanel.CLOSED; st.trText = ""; st.trResult = "" }) { Icon(Icons.Rounded.Close, "Закрыть") }
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(st.trText.ifEmpty { "Пиши здесь на любом языке…" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (st.trText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.trResult.ifEmpty { if (st.trBusy) "Перевожу…" else "Перевод появится здесь" }, Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    if (st.trBusy) NanoLoader(18.dp)
                    else FilledTonalButton({ ime.insertTranslation() }, enabled = st.trResult.isNotBlank()) { Text("Вставить") }
                }
            }
        }
    }
}

@Composable
private fun EditPanel(ime: NanoIme, h: Dp) {
    val st = ime.state
    val meta = if (ime.selecting) KeyEvent.META_SHIFT_ON else 0
    @Composable
    fun B(icon: ImageVector, label: String, modifier: Modifier = Modifier, repeat: Boolean = false, click: () -> Unit) {
        if (repeat) RepeatKey(modifier.fillMaxHeight(), h = 0.dp, onFire = click) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, label); Text(label, fontSize = 10.sp) }
        } else Surface(onClick = click, modifier = modifier.fillMaxHeight(), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, label); Text(label, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    Column(Modifier.fillMaxWidth().height(h).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            B(Icons.Rounded.Undo, "Отменить", Modifier.weight(1f)) { ime.editKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON) }
            B(Icons.Rounded.KeyboardArrowUp, "Вверх", Modifier.weight(1f), repeat = true) { ime.editKey(KeyEvent.KEYCODE_DPAD_UP, meta) }
            B(Icons.Rounded.Redo, "Повторить", Modifier.weight(1f)) { ime.editKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) }
            B(Icons.Rounded.SelectAll, "Всё", Modifier.weight(1f)) { ime.editAction(android.R.id.selectAll) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            B(Icons.Rounded.KeyboardArrowLeft, "Влево", Modifier.weight(1f), repeat = true) { ime.editKey(KeyEvent.KEYCODE_DPAD_LEFT, meta) }
            Surface(onClick = { ime.selecting = !ime.selecting }, modifier = Modifier.weight(1f).fillMaxHeight(), shape = MaterialTheme.shapes.medium,
                color = if (ime.selecting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.HighlightAlt, "Выделение"); Text("Выделять", fontSize = 10.sp)
                }
            }
            B(Icons.Rounded.KeyboardArrowRight, "Вправо", Modifier.weight(1f), repeat = true) { ime.editKey(KeyEvent.KEYCODE_DPAD_RIGHT, meta) }
            B(Icons.Rounded.ContentCut, "Вырезать", Modifier.weight(1f)) { ime.editAction(android.R.id.cut) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            B(Icons.Rounded.FirstPage, "В начало", Modifier.weight(1f)) { ime.editKey(KeyEvent.KEYCODE_MOVE_HOME, meta or KeyEvent.META_CTRL_ON) }
            B(Icons.Rounded.KeyboardArrowDown, "Вниз", Modifier.weight(1f), repeat = true) { ime.editKey(KeyEvent.KEYCODE_DPAD_DOWN, meta) }
            B(Icons.Rounded.LastPage, "В конец", Modifier.weight(1f)) { ime.editKey(KeyEvent.KEYCODE_MOVE_END, meta or KeyEvent.META_CTRL_ON) }
            B(Icons.Rounded.ContentCopy, "Копировать", Modifier.weight(1f)) { ime.editAction(android.R.id.copy) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            B(Icons.Rounded.Keyboard, "Клавиатура", Modifier.weight(1f)) { ime.selecting = false; st.ai = AiPanel.CLOSED }
            B(Icons.AutoMirrored.Rounded.Backspace, "Стереть", Modifier.weight(2f), repeat = true) { ime.backspace() }
            B(Icons.Rounded.ContentPaste, "Вставить", Modifier.weight(1f)) { ime.editAction(android.R.id.paste) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SnippetsPanel(ime: NanoIme, h: Dp) {
    val st = ime.state
    val list = ime.s.snippetList
    Column(Modifier.fillMaxWidth().height(h).padding(horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Заготовки", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 6.dp))
            TextButton({ if (!ime.addSnippetFromClipboard()) Unit }) { Icon(Icons.Rounded.Add, null); Text("Из буфера") }
            IconButton({ st.ai = AiPanel.CLOSED }) { Icon(Icons.Rounded.Close, "Закрыть") }
        }
        if (list.isEmpty()) Text("Скопируй частую фразу (адрес, «Буду через 10 минут») и нажми «Из буфера». " +
                "Долгое нажатие на заготовку — удалить. Управлять — в настройках клавиатуры.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list) { t ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { ime.pasteText(t); st.ai = AiPanel.CLOSED },
                        onLongClick = { ime.s.setSnippets(list - t) })) {
                    Text(t, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Боковая панель режима одной руки: перенести на другую сторону / развернуть. */
@Composable
private fun OneHandBar(ime: NanoIme, h: Dp) {
    val s = ime.s
    Column(Modifier.width(52.dp).height(h), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton({ s.kbOneHand = if (s.kbOneHand == 1) 2 else 1 }) {
            Icon(if (s.kbOneHand == 1) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, "Другая сторона")
        }
        Spacer(Modifier.height(12.dp))
        IconButton({ s.kbOneHand = 0 }) { Icon(Icons.Rounded.OpenInFull, "Обычный режим") }
    }
}

@Composable
private fun ToolsPanel(ime: NanoIme, h: Dp) {
    val st = ime.state
    val s = ime.s
    @Composable
    fun Tile(icon: ImageVector, label: String, on: Boolean = false, click: () -> Unit) {
        Surface(onClick = click, shape = MaterialTheme.shapes.large,
            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(width = 104.dp, height = 84.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null); Spacer(Modifier.height(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().height(h).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Icons.Rounded.ContentPaste, "Буфер обмена") { st.ai = AiPanel.CLIPS }
        Tile(Icons.Rounded.TextSnippet, "Заготовки") { st.ai = AiPanel.SNIPPETS }
        Tile(Icons.Rounded.EditNote, "Редактирование") { st.ai = AiPanel.EDIT }
        Tile(Icons.Rounded.Translate, "Перевод на лету") { ime.startTranslate() }
        Tile(Icons.Rounded.VisibilityOff, "Инкогнито", st.incognito) {
            st.incognito = !st.incognito; st.ai = AiPanel.CLOSED; ime.refreshSuggestions()
            ime.toast(if (st.incognito) "Инкогнито: ничего не запоминаю" else "Инкогнито выключено")
        }
        Tile(Icons.Rounded.EmojiEmotions, "Эмодзи") { st.ai = AiPanel.CLOSED; st.emoji = true }
        Tile(Icons.Rounded.Dialpad, "Цифры") { st.ai = AiPanel.CLOSED; st.numpad = !st.numpad }
        Tile(Icons.Rounded.BackHand, "Одной рукой", s.kbOneHand != 0) { s.kbOneHand = if (s.kbOneHand == 0) 2 else 0; st.ai = AiPanel.CLOSED }
        Tile(Icons.Rounded.Gesture, "Набор свайпом", s.kbGlide) { s.kbGlide = !s.kbGlide; ime.toast(if (s.kbGlide) "Свайп включён" else "Свайп выключен") }
        Tile(Icons.Rounded.Settings, "Настройки") { st.ai = AiPanel.CLOSED; ime.openSettings() }
        Tile(Icons.Rounded.Mic, "Голосовой ввод") { ime.toggleVoice() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ClipsPanel(ime: NanoIme, h: Dp) {
    val st = ime.state
    Column(Modifier.fillMaxWidth().height(h).padding(horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Буфер обмена", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 6.dp))
            TextButton({ ime.clearClips() }) { Text("Очистить") }
            IconButton({ st.ai = AiPanel.CLOSED }) { Icon(Icons.Rounded.Close, "Закрыть") }
        }
        if (st.clips.isEmpty()) Text("Скопируй текст — он появится здесь. Хранится только на телефоне.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(st.clips.toList(), key = { it.time }) { c ->
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (c.pinned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { ime.pasteText(c.text); st.ai = AiPanel.CLOSED },
                        onLongClick = { ime.togglePin(c) })) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.text, Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        IconButton({ ime.togglePin(c) }) {
                            Icon(if (c.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPinOutlined, "Закрепить", Modifier.size(18.dp))
                        }
                        IconButton({ ime.removeClip(c) }) { Icon(Icons.Rounded.Close, "Удалить", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopStrip(ime: NanoIme) {
    val st = ime.state
    val sg = st.sugs
    val cs = MaterialTheme.colorScheme
    val fresh = st.freshClip?.takeIf { System.currentTimeMillis() - it.time < 90_000 && !st.private }
    val mode = when {
        st.ai == AiPanel.CLOSED && sg.items.isNotEmpty() -> 0
        st.continuation != null && st.ai == AiPanel.CLOSED -> 1
        fresh != null && st.ai == AiPanel.CLOSED -> 2
        else -> 3
    }
    // Смена режима полосы (подсказки ↔ панель значков) — мягкое проявление, сами подсказки меняются мгновенно
    androidx.compose.animation.AnimatedContent(mode, transitionSpec = {
        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90))
    }, label = "strip") { m ->
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        if (m == 0) {
            // Пока печатаешь — подсказки во всю ширину, как в Gboard; слева кнопка панели инструментов
            IconButton({ st.ai = AiPanel.TOOLS }) { Icon(Icons.Rounded.Apps, "Инструменты", tint = cs.onSurfaceVariant) }
            Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                if (st.incognito) Icon(Icons.Rounded.VisibilityOff, "Инкогнито", Modifier.padding(horizontal = 4.dp).size(18.dp), tint = cs.primary)
                sg.items.forEachIndexed { i, w ->
                    if (i > 0) VerticalDivider(Modifier.height(20.dp), color = cs.outlineVariant)
                    val main = (sg.autocorrect != null && w == sg.autocorrect) || (sg.autocorrect == null && i == 1)
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable { ime.pick(w) },
                        contentAlignment = Alignment.Center) {
                        Text(if (i == 0 && sg.items.size > 1 && (!sg.typedKnown || sg.autocorrect != null)) "«$w»" else w,
                            fontWeight = if (main) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sg.autocorrect != null && w == sg.autocorrect) cs.primary else cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                    }
                }
                st.emojiSug.take(2).forEach { e ->
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { ime.pickEmoji(e) }, contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 20.sp)
                    }
                }
            }
        } else if (m == 1 && st.continuation != null) {
            IconButton({ st.ai = AiPanel.TOOLS }) { Icon(Icons.Rounded.Apps, "Инструменты", tint = cs.onSurfaceVariant) }
            AssistChip({ ime.acceptContinuation() }, { Text("→ ${st.continuation}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp)) }, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            IconButton({ st.continuation = null }) { Icon(Icons.Rounded.Close, "Скрыть", tint = cs.onSurfaceVariant) }
        } else if (m == 2 && fresh != null) {
            IconButton({ st.ai = AiPanel.TOOLS }) { Icon(Icons.Rounded.Apps, "Инструменты", tint = cs.onSurfaceVariant) }
            InputChip(true, { ime.pasteText(fresh.text); st.freshClip = null },
                { Text(fresh.text.replace('\n', ' ').take(30), maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            IconButton({ st.freshClip = null }) { Icon(Icons.Rounded.Close, "Скрыть", tint = cs.onSurfaceVariant) }
        } else {
            // Панель иконок как в Gboard
            @Composable
            fun Tool(icon: ImageVector, desc: String, active: Boolean = false, click: () -> Unit) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    IconButton(click, colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = cs.primaryContainer,
                        contentColor = cs.onPrimaryContainer) else IconButtonDefaults.iconButtonColors(contentColor = cs.onSurfaceVariant)) {
                        Icon(icon, desc)
                    }
                }
            }
            Tool(Icons.Rounded.Apps, "Инструменты", st.ai == AiPanel.TOOLS) { st.ai = if (st.ai == AiPanel.TOOLS) AiPanel.CLOSED else AiPanel.TOOLS }
            Tool(Icons.Rounded.AutoAwesome, "ИИ", st.ai == AiPanel.MODES || st.ai == AiPanel.RESULT) {
                st.ai = if (st.ai == AiPanel.MODES || st.ai == AiPanel.RESULT) AiPanel.CLOSED else AiPanel.MODES
            }
            Tool(Icons.Rounded.Translate, "Перевести поле") { if (!st.private) ime.runAi(Mode.TRANSLATE) }
            Tool(Icons.Rounded.ContentPaste, "Буфер обмена", st.ai == AiPanel.CLIPS) { st.ai = if (st.ai == AiPanel.CLIPS) AiPanel.CLOSED else AiPanel.CLIPS }
            Tool(Icons.Rounded.Settings, "Настройки") { ime.openSettings() }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                FilledTonalIconButton({ ime.toggleVoice() },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (st.ai == AiPanel.VOICE) cs.error else cs.surfaceContainerHighest,
                        contentColor = if (st.ai == AiPanel.VOICE) cs.onError else cs.onSurface)) {
                    Icon(if (st.ai == AiPanel.VOICE) Icons.Rounded.Stop else Icons.Rounded.Mic, "Голосовой ввод")
                }
            }
        }
    }
}
}

@Composable
private fun AiModes(ime: NanoIme, h: Dp) {
    val ctx = LocalContext.current
    val presets = ctx.app.stores.presets.items
    Column(Modifier.fillMaxWidth().height(h).verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Что сделать с текстом в поле (или с выделенным)?", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Mode.entries.filter { it.inChips && it != Mode.EVENT && it != Mode.CARDS }.forEach { m ->
                FilterChip(false, { ime.runAi(m) }, { Text(m.title) }, leadingIcon = { Icon(m.icon, null, Modifier.size(18.dp)) })
            }
            presets.toList().forEach { p ->
                FilterChip(false, { ime.runAi(Mode.CUSTOM, p) }, { Text(p.name) }, leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) })
            }
        }
    }
}

@Composable
private fun AiResult(ime: NanoIme, h: Dp) {
    val st = ime.state
    Column(Modifier.fillMaxWidth().height(h).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.aiTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (st.aiBusy) NanoLoader(22.dp)
        }
        Surface(Modifier.fillMaxWidth().weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Box(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(st.aiError ?: st.aiResult.ifBlank { "Думаю…" },
                    color = if (st.aiError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ st.job?.cancel(); ime.app.ai.local.cancelActive(); st.ai = AiPanel.CLOSED }) { Text("Отмена") }
            Spacer(Modifier.weight(1f))
            if (!st.aiBusy && st.aiResult.isNotBlank()) {
                FilledTonalButton({ ime.currentInputConnection?.commitText(st.aiResult, 1); st.ai = AiPanel.CLOSED }) { Text("Добавить") }
                Button({ ime.applyAi() }) { Icon(Icons.Rounded.Done, null); Spacer(Modifier.width(4.dp)); Text("Заменить") }
            }
        }
    }
}

/** Клавиши (совместимость: панель поиска эмодзи). */
@Composable
fun Keys(ime: NanoIme, modifier: Modifier = Modifier) = KeyCanvas(ime, modifier)

/** Клавиша с автоповтором при удержании (⌫). */
@Composable
fun RepeatKey(modifier: Modifier, h: Dp = 44.dp, onFire: () -> Unit, content: @Composable () -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pressed = remember { mutableStateOf(false) }
    val fire by rememberUpdatedState(onFire)
    val onColor = MaterialTheme.colorScheme.secondaryContainer
    val offColor = onColor.copy(alpha = 0.6f)
    Box(
        modifier.height(h).clip(RoundedCornerShape(14.dp))
            .drawBehind { drawRect(if (pressed.value) onColor else offColor) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed.value = true
                    if (ime_haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fire()
                    val job = scope.launch {
                        delay(400)
                        var d = 70L
                        while (true) { fire(); delay(d); d = (d - 4).coerceAtLeast(30) }
                    }
                    tryAwaitRelease()
                    job.cancel(); pressed.value = false
                })
            },
        contentAlignment = Alignment.Center
    ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) { content() } }
}

// ---------- экран настройки клавиатуры в приложении ----------

fun isImeEnabled(ctx: Context): Boolean {
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
}

@Composable
fun KeyboardSetupTab() {
    val ctx = LocalContext.current
    val s = ctx.app.settings
    var enabled by remember { mutableStateOf(isImeEnabled(ctx)) }
    var showDict by remember { mutableStateOf(false) }
    var showMigrate by remember { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) enabled = isImeEnabled(ctx) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    var test by remember { mutableStateOf("") }
    if (showDict) { MyDictionary { showDict = false }; return }
    if (showMigrate) { GboardMigration { showMigrate = false }; return }

    var q by remember { mutableStateOf("") }
    var only by remember { mutableStateOf<String?>(null) }
    var showSnips by remember { mutableStateOf(false) }
    val pickBg = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { inp -> java.io.File(ctx.filesDir, "kb_bg.jpg").outputStream().use { inp.copyTo(it) } }
            s.kbTheme = 4; s.kbBgStamp = System.currentTimeMillis()
        }
    }
    if (showSnips) { SnippetsEditor { showSnips = false }; return }
    val sections = listOf("Размер", "Вид", "Ввод", "ИИ", "Словарь", "Жесты")

    Column(Modifier.fillMaxSize()) {
        // Закреплённая сверху мини-копия клавиатуры и поиск
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!enabled) StepCard(1, "Включить клавиатуру", "Включи «НаноПеро ✦» в списке клавиатур", false) {
                    ctx.startActivity(Intent(SysSettings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 170.dp)) { KeyboardPreview(s) }
                SetSearch(q, { q = it })
                if (q.isBlank()) SetSectionChips(sections, only) { only = it }
            }
        }
        CompositionLocalProvider(LocalSetQuery provides q) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (q.isBlank() && only == null) StepCard(if (enabled) 1 else 2, if (enabled) "Клавиатура включена" else "Выбрать её",
                "Нажми, чтобы переключить клавиатуру. Быстро: долгое нажатие 🌐", enabled) {
                ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
            val o = if (q.isBlank()) only else null
            SetSection("Размер", o) {
                SetSlider(Icons.Rounded.Height, "Высота клавиш", "${(s.kbHeight * 100).toInt()}%", s.kbHeight, 0.7f..1.5f) { s.kbHeight = (it * 100).toInt() / 100f }
                SetSlider(Icons.Rounded.FormatSize, "Размер букв", "${s.kbTextSize}", s.kbTextSize.toFloat(), 14f..32f) { s.kbTextSize = it.toInt() }
                SetSlider(Icons.Rounded.SpaceBar, "Расстояние между клавишами", "${s.kbGap} dp", s.kbGap.toFloat(), 0f..12f) { s.kbGap = it.toInt() }
                SetSlider(Icons.Rounded.SettingsEthernet, "Отступы по бокам", "${s.kbSide} dp", s.kbSide.toFloat(), 0f..40f) { s.kbSide = it.toInt() }
                SetSlider(Icons.Rounded.RoundedCorner, "Скругление клавиш", "${s.kbRadius} dp", s.kbRadius.toFloat(), 0f..20f) { s.kbRadius = it.toInt() }
                SetSlider(Icons.Rounded.VerticalAlignBottom, "Подъём над краем экрана", "${s.kbBottom} dp", s.kbBottom.toFloat(), 0f..64f) { s.kbBottom = it.toInt() }
                SetChoice(Icons.Rounded.VerticalSplit, "Разделённая клавиатура", listOf(0 to "Авто (планшет, раскладушка)", 1 to "Всегда", 2 to "Никогда"), s.kbSplit) { s.kbSplit = it }
                SetChoice(Icons.Rounded.BackHand, "Режим одной руки", listOf(0 to "Выкл", 1 to "Слева", 2 to "Справа"), s.kbOneHand) { s.kbOneHand = it }
                SetCustom("Сбросить размер") {
                    TextButton({ s.kbHeight = 1f; s.kbTextSize = 21; s.kbGap = 5; s.kbSide = 0; s.kbBottom = 10; s.kbRadius = 8 }) {
                        Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Сбросить размер")
                    }
                }
            }
            SetSection("Вид", o) {
                SetChoice(Icons.Rounded.Palette, "Тема", listOf(0 to "Как в системе", 1 to "Светлая", 2 to "Тёмная", 3 to "Чёрная AMOLED", 4 to "Картинка"), s.kbTheme) {
                    if (it == 4 && !java.io.File(ctx.filesDir, "kb_bg.jpg").exists())
                        pickBg.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else s.kbTheme = it
                }
                if (s.kbTheme == 4) SetLink(Icons.Rounded.Image, "Выбрать картинку", "Фото из галереи под клавишами") {
                    pickBg.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SetSwitch(Icons.Rounded.BorderOuter, "Рамки клавиш", "Тонкая обводка у каждой клавиши", s.kbBorders) { s.kbBorders = it }
                SetSwitch(Icons.Rounded.Pin, "Ряд цифр", "Цифры всегда над буквами", s.kbNumberRow) { s.kbNumberRow = it }
                SetSwitch(Icons.Rounded.ZoomIn, "Буква над пальцем", "Увеличенная подсказка при нажатии", s.kbPreview) { s.kbPreview = it }
                SetSwitch(Icons.Rounded.Vibration, "Вибрация", "Лёгкий отклик при нажатии", s.kbHaptic) { s.kbHaptic = it; ime_haptic = it }
                SetSwitch(Icons.Rounded.VolumeUp, "Звук нажатий", "Системный щелчок клавиш", s.kbSound) { s.kbSound = it }
            }
            SetSection("Ввод", o) {
                SetSwitch(Icons.Rounded.Lightbulb, "Подсказки слов", "Три варианта над клавиатурой", s.kbSuggest) { s.kbSuggest = it }
                SetSwitch(Icons.Rounded.EmojiEmotions, "Эмодзи в подсказках", "«кот» → 🐱 рядом со словами", s.kbEmojiSuggest) { s.kbEmojiSuggest = it }
                SetSwitch(Icons.Rounded.Spellcheck, "Автоисправление", "На пробеле, знаках и Enter. ⌫ сразу после — отменить", s.kbAutocorrect) { s.kbAutocorrect = it }
                SetSwitch(Icons.Rounded.School, "Учиться на моих словах", "Запоминать слова и что ты обычно пишешь дальше", s.kbLearn) { s.kbLearn = it }
                SetSwitch(Icons.Rounded.TextFields, "Заглавная в начале предложения", "После точки и в начале поля", s.kbAutoCaps) { s.kbAutoCaps = it }
                SetSwitch(Icons.Rounded.MoreHoriz, "Двойной пробел — точка", "Два пробела подряд ставят «. »", s.kbDoubleSpace) { s.kbDoubleSpace = it }
                SetSwitch(Icons.Rounded.FormatQuote, "Кавычки-«ёлочки»", "В русском и украинском", s.kbSmartQuotes) { s.kbSmartQuotes = it }
                SetSwitch(Icons.Rounded.Gesture, "Набор свайпом", "Веди пальцем по буквам, не отрывая", s.kbGlide) { s.kbGlide = it }
                SetSwitch(Icons.Rounded.Explicit, "Мат в подсказках и автоисправлении", "Иначе такие слова не подсказываются и не исправляются", s.kbObscene) { s.kbObscene = it }
                SetText("Русские и украинские слова понимаются на любой из двух раскладок — их не «исправит» в другой язык.")
            }
            SetSection("ИИ", o) {
                SetSwitch(Icons.Rounded.AutoAwesome, "ИИ-продолжение фразы", "После паузы предложит продолжение — нажми на него. Нужна модель, тратит батарею", s.kbAiContinue) { s.kbAiContinue = it }
                SetChoice(Icons.Rounded.Translate, "Перевод на лету: язык", listOf("английский" to "EN", "украинский" to "UA", "русский" to "RU",
                    "польский" to "PL", "немецкий" to "DE", "испанский" to "ES", "французский" to "FR"), s.kbTrLang) { s.kbTrLang = it }
                SetLink(Icons.Rounded.ShortText, "Заготовки", "${s.snippetList.size} фраз · добавить, изменить, удалить") { showSnips = true }
            }
            SetSection("Словарь", o) {
                SetLink(Icons.Rounded.MenuBook, "Мой словарь", "${ctx.app.userDict.words.count { it.value >= 3 }} выученных слов · посмотреть и удалить") { showDict = true }
                SetLink(Icons.Rounded.MoveDown, "Перенести словарь из Gboard", "Свои слова и сокращения — почти автоматически") { showMigrate = true }
                SetLink(Icons.Rounded.TouchApp, "Сбросить подстройку под мои касания", "Клавиатура учится, куда ты на самом деле попадаешь") {
                    ctx.getSharedPreferences("tapoff", Context.MODE_PRIVATE).edit().clear().apply(); SnackHost.show(ctx, "Подстройка под пальцы сброшена")
                }
            }
            SetSection("Жесты", o) {
                SetHint(Icons.Rounded.TouchApp, "Долгое нажатие на «,»", "Эмодзи")
                SetHint(Icons.Rounded.TouchApp, "Долгое нажатие на верхний ряд", "Цифры")
                SetHint(Icons.Rounded.TouchApp, "Долгое на «е», «ь», «г»", "«ё», «ъ», «ґ»")
                SetHint(Icons.Rounded.TouchApp, "Долгое на пробел или 🌐", "Язык / клавиатура")
                SetHint(Icons.Rounded.TouchApp, "Двойное нажатие ⇧", "Caps Lock")
                SetHint(Icons.Rounded.Swipe, "Свайп по пробелу", "Курсор")
                SetHint(Icons.Rounded.Swipe, "Свайп влево с ⌫", "Стирать словами")
                SetHint(Icons.Rounded.Apps, "Кнопка ▦", "Буфер, правка, перевод…")
            }
            OutlinedTextField(test, { test = it }, Modifier.fillMaxWidth(), label = { Text("Проверь здесь") },
                minLines = 2, shape = MaterialTheme.shapes.large)
            if (q.isBlank()) TextButton({
                s.kbHeight = 1f; s.kbTextSize = 21; s.kbBottom = 10; s.kbGap = 5; s.kbSide = 0; s.kbNumberRow = false; s.kbHaptic = true
                s.kbSound = false; s.kbPreview = true; s.kbGlide = true; s.kbSmartQuotes = true; s.kbOneHand = 0
                s.kbSuggest = true; s.kbAutocorrect = true; s.kbLearn = true; s.kbAutoCaps = true; s.kbDoubleSpace = true
                s.kbTheme = 0; s.kbBorders = false; s.kbSplit = 0; s.kbAiContinue = false; s.kbEmojiSuggest = true
            }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Сбросить настройки") }
            Text("В полях паролей и в режиме инкогнито клавиатура ничего не запоминает. Словари: FrequencyWords (CC BY-SA 4.0), эмодзи: Unicode CLDR.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
}

@Composable
private fun SnippetsEditor(onBack: () -> Unit) {
    val s = LocalContext.current.app.settings
    var add by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Заготовки", style = MaterialTheme.typography.titleLarge)
        }
        Text("Частые фразы: адрес, номер карты лояльности, «Буду через 10 минут». На клавиатуре — ▦ → Заготовки.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(add, { add = it }, Modifier.fillMaxWidth(), label = { Text("Новая заготовка") }, shape = MaterialTheme.shapes.large,
            trailingIcon = { IconButton({ if (add.isNotBlank()) { s.setSnippets((s.snippetList + add.trim()).distinct()); add = "" } }) { Icon(Icons.Rounded.Add, "Добавить") } })
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(s.snippetList) { i: Int, t: String ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    if (edit == i) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        OutlinedTextField(editText, { editText = it }, Modifier.weight(1f))
                        IconButton({ s.setSnippets(s.snippetList.toMutableList().also { l -> l[i] = editText.trim() }.filter { it.isNotBlank() }); edit = null }) {
                            Icon(Icons.Rounded.Check, "Сохранить")
                        }
                    } else ListItem(headlineContent = { Text(t, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row {
                                IconButton({ edit = i; editText = t }) { Icon(Icons.Rounded.Edit, "Изменить") }
                                IconButton({
                                    val old = s.snippetList; s.setSnippets(old - t)
                                    SnackHost.undo("Заготовка удалена") { s.setSnippets(old) }
                                }) { Icon(Icons.Rounded.Delete, "Удалить") }
                            }
                        })
                }
            }
        }
    }
}

/** Неинтерактивная копия клавиатуры, которая сразу показывает изменения настроек. */
@Composable
private fun KeyboardPreview(s: Settings) {
    val h = (50f * s.kbHeight).dp * 0.72f
    val gap = s.kbGap.dp * 0.72f
    val ts = (s.kbTextSize * 0.72f).sp
    val rows = buildList {
        if (s.kbNumberRow) add("1234567890")
        addAll(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"))
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 6.dp + s.kbSide.dp * 0.72f, end = 6.dp + s.kbSide.dp * 0.72f, top = 6.dp)) {
            // Строка подсказок
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp)) }
                listOf("привет" to false, "Привет" to true, "приветик" to false).forEach { (w, b) ->
                    Text(w, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                        fontWeight = if (b) FontWeight.Bold else FontWeight.Normal,
                        color = if (b) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(Modifier.padding(vertical = gap / 2), verticalArrangement = Arrangement.spacedBy(gap)) {
                rows.forEachIndexed { i, r ->
                    val rel = i - (if (s.kbNumberRow) 1 else 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap * 0.8f)) {
                        if (rel == 2) PreviewKey(Modifier.weight(1.5f), h, special = true) { Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(14.dp)) }
                        r.forEach { c -> PreviewKey(Modifier.weight(1f), h) { Text(c.toString(), fontSize = ts) } }
                        if (rel == 2) PreviewKey(Modifier.weight(1.5f), h, special = true) { Icon(Icons.AutoMirrored.Rounded.Backspace, null, Modifier.size(14.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap * 0.8f)) {
                    PreviewKey(Modifier.weight(1.5f), h, special = true) { Text("?123", fontSize = 9.sp) }
                    PreviewKey(Modifier.weight(1f), h) {
                        Text(",", fontSize = ts)
                        Icon(Icons.Rounded.EmojiEmotions, null, Modifier.size(9.dp).align(Alignment.TopEnd).padding(top = 1.dp, end = 1.dp))
                    }
                    PreviewKey(Modifier.weight(1f), h, special = true) { Icon(Icons.Rounded.Language, null, Modifier.size(14.dp)) }
                    PreviewKey(Modifier.weight(4.5f), h) { Text("Русский", fontSize = 9.sp) }
                    PreviewKey(Modifier.weight(1f), h) { Text(".", fontSize = ts) }
                    PreviewKey(Modifier.weight(1.5f), h, accent = true) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            // Отступ снизу — заштрихованная зона
            Box(Modifier.fillMaxWidth().height(s.kbBottom.dp * 0.72f + 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 60.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

@Composable
private fun PreviewKey(modifier: Modifier, h: Dp, special: Boolean = false, accent: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.height(h).clip(RoundedCornerShape((LocalContext.current.app.settings.kbRadius * 0.72f).dp)).background(
            when {
                accent -> MaterialTheme.colorScheme.primaryContainer
                special -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ), contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun IconSlider(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, v: Float,
                       range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(value, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Slider(v, onChange, valueRange = range, modifier = Modifier.padding(start = 32.dp))
    }
}

@Composable
private fun IconSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(value, onChange) },
        modifier = Modifier.clickable { onChange(!value) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, gesture: String, result: String) {
    ListItem(
        leadingContent = { Icon(icon, null, Modifier.size(20.dp)) },
        headlineContent = { Text(gesture, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = { Text(result, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
private fun MyDictionary(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ud = ctx.app.userDict
    var tick by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    val list = remember(tick, q) {
        ud.words.entries.filter { q.isBlank() || it.key.substringAfter('|').contains(q, true) }
            .sortedByDescending { it.value }.take(300)
    }
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Мой словарь", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton({ ud.clear(); tick++ }) { Text("Очистить") }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Найти слово") })
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.key }) { e ->
                ListItem(
                    headlineContent = { Text(e.key.substringAfter('|')) },
                    supportingContent = { Text("${e.key.substringBefore('|').uppercase()} · ${e.value} раз") },
                    trailingContent = {
                        IconButton({ ud.forget(e.key.substringBefore('|'), e.key.substringAfter('|')); tick++ }) {
                            Icon(Icons.Rounded.DeleteOutline, "Забыть")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun StepCard(n: Int, title: String, sub: String, done: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        ListItem(
            leadingContent = {
                Surface(shape = MaterialTheme.shapes.extraLarge,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                        else Text("$n", fontWeight = FontWeight.Bold)
                    }
                }
            },
            headlineContent = { Text(title) },
            supportingContent = { Text(sub) },
            trailingContent = { Icon(Icons.Rounded.OpenInNew, null) }
        )
    }
}
