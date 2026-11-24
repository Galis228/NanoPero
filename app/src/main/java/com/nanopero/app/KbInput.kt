package com.nanopero.app

import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

// ---------- модель раскладки ----------

enum class KType { CHAR, SHIFT, BACK, SYM, SYM2, LANG, SPACE, ENTER, COMMA, PERIOD, ABC }

data class KeyDef(val type: KType, val label: String = "", val weight: Float = 1f, val alts: List<String> = emptyList(), val letter: Boolean = false)

class KeyRect(val def: KeyDef, val rect: Rect)

data class KbLayout(val name: String, val rows: List<String>, val title: String = name)

val kbLayouts = listOf(
    KbLayout("RU", listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), "Русский"),
    KbLayout("UA", listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"), "Українська"),
    KbLayout("EN", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), "English"),
)
private val symbolPages = listOf(
    listOf("1234567890", "@#₴$%&-+()", "*\"':;!?/"),
    listOf("~`|•√π÷×¶∆", "€£¥₽^°={}", "\\©®™✓[]<>"),
)
private val digitsRow = "1234567890"
private val letterAlts = mapOf("е" to listOf("ё"), "ь" to listOf("ъ"), "г" to listOf("ґ"), "ъ" to listOf("ь"))
/** Апостроф в украинской раскладке (м'ясо, п'ять) — долгим нажатием на «є» и «ж», как в Gboard. */
private val ukAlts = mapOf("є" to listOf("'"), "ж" to listOf("'"), "і" to listOf("ї", "'"), "ь" to listOf("'"))
private val enAlts = mapOf("e" to listOf("é", "è", "ê", "ë"), "a" to listOf("á", "à", "â", "ä"), "o" to listOf("ó", "ò", "ô", "ö"),
    "u" to listOf("ú", "ù", "û", "ü"), "i" to listOf("í", "ì", "î", "ï"), "n" to listOf("ñ"), "c" to listOf("ç"), "s" to listOf("ß"))
private val symbolAlts = mapOf(
    "-" to listOf("—", "–", "_"), "1" to listOf("¹", "½"), "2" to listOf("²"), "0" to listOf("°"),
    "?" to listOf("¿"), "!" to listOf("¡"), "\"" to listOf("«", "»", "„", "“"), "'" to listOf("’"),
    "$" to listOf("€", "₴", "₽", "£"), "%" to listOf("‰"), "(" to listOf("[", "{", "<"), ")" to listOf("]", "}", ">")
)
private val periodAlts = listOf("…", "!", "?", ",", ";", ":", "-", "\"", "'")

/** Строки клавиш для текущего состояния клавиатуры. */
fun buildRows(st: KbState, s: Settings): List<List<KeyDef>> {
    if (st.numpad) return listOf(
        listOf(KeyDef(KType.CHAR, "1"), KeyDef(KType.CHAR, "2"), KeyDef(KType.CHAR, "3"), KeyDef(KType.CHAR, "+", alts = listOf("-", "(", ")", "*", "#"))),
        listOf(KeyDef(KType.CHAR, "4"), KeyDef(KType.CHAR, "5"), KeyDef(KType.CHAR, "6"), KeyDef(KType.SPACE, "␣")),
        listOf(KeyDef(KType.CHAR, "7"), KeyDef(KType.CHAR, "8"), KeyDef(KType.CHAR, "9"), KeyDef(KType.BACK)),
        listOf(KeyDef(KType.ABC, "ABC"), KeyDef(KType.CHAR, "0"), KeyDef(KType.CHAR, ".", alts = listOf(",", "-", "/", ":")), KeyDef(KType.ENTER)),
    )
    val rows = ArrayList<List<KeyDef>>()
    val lang = st.lang
    if (st.symbols) {
        val pg = symbolPages[st.symPage]
        pg.forEachIndexed { i, r ->
            val keys = r.map { KeyDef(KType.CHAR, it.toString(), alts = symbolAlts[it.toString()] ?: emptyList()) }
            rows += if (i == 2) listOf(KeyDef(KType.SYM2, if (st.symPage == 0) "=\\<" else "?123", 1.4f)) + keys + KeyDef(KType.BACK, weight = 1.4f) else keys
        }
    } else {
        if (s.kbNumberRow) rows += digitsRow.map { KeyDef(KType.CHAR, it.toString()) }
        kbLayouts[lang].rows.forEachIndexed { i, r ->
            val keys = r.mapIndexed { j, c ->
                val ch = c.toString()
                val alts = ArrayList<String>()
                when (lang) {
                    1 -> (ukAlts[ch] ?: letterAlts[ch]?.takeIf { ch == "г" })?.let { alts += it }
                    2 -> enAlts[ch]?.let { alts += it }
                    else -> letterAlts[ch]?.takeIf { ch != "г" }?.let { alts += it }
                }
                if (i == 0 && !s.kbNumberRow && j < 10) alts.add(0, digitsRow[j].toString())
                KeyDef(KType.CHAR, ch, alts = alts, letter = true)
            }
            rows += if (i == 2) listOf(KeyDef(KType.SHIFT, weight = 1.4f)) + keys + KeyDef(KType.BACK, weight = 1.4f) else keys
        }
    }
    // Нижний ряд как в Gboard: ?123 · ☺, · 🌐 · пробел · . · Enter
    rows += listOf(
        KeyDef(KType.SYM, if (st.symbols) (if (lang == 2) "ABC" else "АБВ") else "?123", 1.5f),
        KeyDef(KType.COMMA, ",", 1f),
        KeyDef(KType.LANG, weight = 1f),
        KeyDef(KType.SPACE, kbLayouts[lang].title, 4.5f),
        KeyDef(KType.PERIOD, ".", 1f, alts = periodAlts),
        KeyDef(KType.ENTER, weight = 1.5f),
    )
    return rows
}

/**
 * Раскладка в пикселях, как в Gboard:
 *  • буквенные ряды — одинаковая ширина клавиш (по самому длинному ряду), короткие ряды центрируются;
 *  • ряд с Shift/⌫ — буквы той же ширины, Shift и ⌫ делят остаток ширины;
 *  • ряд цифр, нижний ряд, символы и цифровая клавиатура — растягиваются на всю ширину.
 * Щелей «мимо клавиш» нет — касание уходит к ближайшей.
 */
fun computeLayout(rows: List<List<KeyDef>>, width: Float, keyH: Float, gap: Float, side: Float, split: Float = 0f): List<KeyRect> {
    if (split > 0f) {
        // Разделённая клавиатура: считаем без промежутка, потом раздвигаем половины (нижний ряд не делим)
        val base = computeLayout(rows, width - split, keyH, gap, side)
        val mid = (width - split) / 2f
        val lastRowTop = base.maxOf { it.rect.top }
        return base.map { k ->
            if (k.rect.top >= lastRowTop) {
                // Нижний ряд: пробел растягиваем на промежуток
                when {
                    k.def.type == KType.SPACE -> KeyRect(k.def, Rect(k.rect.left, k.rect.top, k.rect.right + split, k.rect.bottom))
                    k.rect.center.x > mid -> KeyRect(k.def, k.rect.translate(split, 0f))
                    else -> k
                }
            } else if (k.rect.center.x > mid) KeyRect(k.def, k.rect.translate(split, 0f)) else k
        }
    }
    val out = ArrayList<KeyRect>()
    val avail = width - 2 * side
    val letterRows = rows.filter { r -> r.any { it.letter } }
    val maxLetters = letterRows.maxOfOrNull { r -> r.count { it.letter } } ?: 0
    val unit = if (maxLetters > 0) (avail - gap * (maxLetters - 1)) / maxLetters else 0f
    rows.forEachIndexed { ri, r ->
        val y = ri * (keyH + gap) + gap / 2
        val letters = r.count { it.letter }
        val specials = r.filter { !it.letter }
        if (letters > 0 && specials.all { it.type == KType.SHIFT || it.type == KType.BACK }) {
            val lettersW = letters * unit + gap * (letters - 1)
            if (specials.isEmpty()) {
                var x = side + (avail - lettersW) / 2f
                r.forEach { k -> out += KeyRect(k, Rect(x, y, x + unit, y + keyH)); x += unit + gap }
            } else {
                val specW = (avail - lettersW - gap * specials.size) / specials.size
                var x = side
                r.forEach { k ->
                    val w = if (k.letter) unit else specW
                    out += KeyRect(k, Rect(x, y, x + w, y + keyH)); x += w + gap
                }
            }
        } else {
            val total = r.sumOf { it.weight.toDouble() }.toFloat()
            val u = (avail - gap * (r.size - 1)) / total
            var x = side
            r.forEach { k ->
                val w = k.weight * u
                out += KeyRect(k, Rect(x, y, x + w, y + keyH)); x += w + gap
            }
        }
    }
    return out
}

fun hitTest(keys: List<KeyRect>, p: Offset): KeyRect? {
    keys.firstOrNull { it.rect.contains(p) }?.let { return it }
    return keys.minByOrNull { k ->
        val dx = maxOf(k.rect.left - p.x, 0f, p.x - k.rect.right)
        val dy = maxOf(k.rect.top - p.y, 0f, p.y - k.rect.bottom)
        dx * dx + dy * dy
    }
}

// ---------- набор свайпом ----------

object Glide {
    /** Кандидаты по последовательности клавиш под пальцем. */
    fun decode(dict0: LangDict?, user: UserDict, lang: String, seq: String, centers: Map<Char, Offset>, pathLen: Float, keyW: Float,
               allowObscene: Boolean = false, prev: String? = null): List<String> {
        if (dict0 == null || seq.length < 2) return emptyList()
        val first = seq.first(); val last = seq.last()
        val scored = ArrayList<Pair<String, Float>>()
        for (dict in listOfNotNull(dict0, if (allowObscene) dict0.obscene else null)) for (i in dict.prefixRange(first.toString())) {
            val w = dict.words[i]
            if (w.length < 2 || w.last() != last) continue
            if (!subseq(w, seq)) continue
            val ideal = idealLen(w, centers)
            val pen = abs(ideal - pathLen) / keyW * 14f
            val ctx = if (prev != null) 14f * kotlin.math.ln(1f + dict0.pair(prev.lowercase(), w)) else 0f
            scored += w to (dict.f(i) - pen + ctx + (if (user.known(lang, w)) 30f else 0f))
        }
        user.words.keys.asSequence().filter { it.startsWith("$lang|$first") && it.endsWith(last) && (user.words[it] ?: 0) >= 3 }
            .map { it.substringAfter('|') }.filter { subseq(it, seq) && scored.none { s -> s.first == it } }
            .forEach { scored += it to 120f }
        return scored.sortedByDescending { it.second }.take(4).map { it.first }
    }

    /** Буквы слова (без повторов подряд) — подпоследовательность пройденных клавиш. */
    private fun subseq(w: String, seq: String): Boolean {
        var j = 0
        var prev = ' '
        for (c in w) {
            if (c == prev) continue
            prev = c
            val cc = if (c == 'ё') 'е' else if (c == 'ъ') 'ь' else c
            while (j < seq.length && seq[j] != cc) j++
            if (j == seq.length) return false
            j++
        }
        return true
    }

    private fun idealLen(w: String, centers: Map<Char, Offset>): Float {
        var len = 0f
        var p: Offset? = null
        for (c in w) {
            val o = centers[if (c == 'ё') 'е' else if (c == 'ъ') 'ь' else c] ?: continue
            p?.let { len += hypot(o.x - it.x, o.y - it.y) }
            p = o
        }
        return len
    }
}

// ---------- отрисовка и касания ----------

private enum class TMode { NORMAL, ALT, SPACE_SWIPE, BACK_SWIPE, GLIDE }

private class Touch(val id: PointerId, val key: KeyRect, val start: Offset, val time: Long) {
    var mode = TMode.NORMAL
    var job: Job? = null
    var consumed = false
    var acc = 0f
    var last = start
    var altIndex = 0
    var held = false  // Shift удерживается как модификатор
}

/**
 * Клавиатура одним Canvas: одна точка обработки касаний для всех пальцев, нет «мёртвых» щелей,
 * всплывающая буква над пальцем, свайпы по пробелу/⌫, набор свайпом, меню долгого нажатия.
 */
@Composable
fun KeyCanvas(ime: NanoIme, modifier: Modifier = Modifier) {
    val st = ime.state
    val s = ime.s
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val tm = rememberTextMeasurer(64)
    val rows = buildRows(st, s)
    val keyH = with(density) { (50f * s.kbHeight).dp.toPx() }
    val gap = with(density) { s.kbGap.dp.toPx() }
    val side = with(density) { s.kbSide.dp.toPx() }
    val totalH = rows.size * (keyH + gap)
    val cs = MaterialTheme.colorScheme
    val textSize = s.kbTextSize.sp

    val pIcons = mapOf(
        "shift" to rememberVectorPainter(ShiftIcons.outline),
        "shiftOn" to rememberVectorPainter(ShiftIcons.filled),
        "caps" to rememberVectorPainter(Icons.Rounded.KeyboardCapslock),
        "back" to rememberVectorPainter(Icons.AutoMirrored.Rounded.Backspace),
        "lang" to rememberVectorPainter(Icons.Rounded.Language),
        "enter" to rememberVectorPainter(Icons.AutoMirrored.Rounded.KeyboardReturn),
        "search" to rememberVectorPainter(Icons.Rounded.Search),
        "send" to rememberVectorPainter(Icons.AutoMirrored.Rounded.Send),
        "go" to rememberVectorPainter(Icons.AutoMirrored.Rounded.ArrowForward),
        "done" to rememberVectorPainter(Icons.Rounded.Check),
        "emoji" to rememberVectorPainter(Icons.Rounded.EmojiEmotions),
    )

    // Состояние касаний
    val pressed = remember { mutableStateMapOf<PointerId, KeyRect>() }
    val pressAt = remember { HashMap<KeyRect, Long>() }
    val fades = remember { mutableStateMapOf<KeyRect, Long>() }   // отпущенные клавиши плавно «гаснут»
    var popup by remember { mutableStateOf<Pair<KeyRect, Int>?>(null) }
    var popupAt by remember { mutableLongStateOf(0L) }
    val trail = remember { mutableStateListOf<Offset>() }
    val trailT = remember { ArrayList<Long>() }
    var frame by remember { mutableLongStateOf(0L) }
    var animUntil by remember { mutableLongStateOf(0L) }
    // Цикл кадров живёт, только пока есть что анимировать (не читаем frame в композиции — без лишних перекомпоновок)
    LaunchedEffect(trail.isNotEmpty(), fades.isNotEmpty(), animUntil) {
        if (trail.isEmpty() && fades.isEmpty() && System.currentTimeMillis() > animUntil) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameMillis { frame = System.currentTimeMillis() }
            val now = System.currentTimeMillis()
            fades.entries.removeAll { now - it.value > FADE_MS }
            if (trail.isEmpty() && fades.isEmpty() && now > animUntil) break
        }
    }
    val touches = remember { HashMap<PointerId, Touch>() }
    var lastShiftUp by remember { mutableLongStateOf(0L) }

    BoxWithConstraints(modifier.fillMaxWidth().height(with(density) { totalH.toDp() })) {
        val width = with(density) { maxWidth.toPx() }
        val splitPx = when (s.kbSplit) {
            1 -> width * 0.16f
            0 -> if (with(density) { width.toDp() } > 600.dp) width * 0.16f else 0f
            else -> 0f
        }
        val keys = remember(rows, width, keyH, gap, side, splitPx) { computeLayout(rows, width, keyH, gap, side, splitPx) }
        val centers = remember(keys) {
            keys.filter { it.def.letter }.associate { it.def.label[0] to it.rect.center }
        }
        val keyW = keys.firstOrNull { it.def.letter }?.rect?.width ?: 60f
        val keyHpx = keys.firstOrNull()?.rect?.height ?: 100f
        SideEffect { if (centers.isNotEmpty()) ime.geo = Triple(centers, keyW, keyHpx) }
        val slop = with(density) { 10.dp.toPx() }

        fun feedback(long: Boolean = false) {
            if (s.kbHaptic) view.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
        }

        fun action(k: KeyDef, at: Offset? = null) {
            when (k.type) {
                KType.CHAR -> ime.type(k.label, if (k.letter) at else null)
                KType.COMMA -> ime.type(",")
                KType.PERIOD -> ime.type(".")
                KType.SPACE -> ime.type(" ")
                KType.ENTER -> if (st.emojiSearch) { st.emojiSearch = false; st.emojiQuery = "" } else ime.enter()
                KType.SHIFT -> {
                    val now = System.currentTimeMillis()
                    st.shift = if (now - lastShiftUp < 320) Shift.LOCK else when (st.shift) {
                        Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.OFF; Shift.LOCK -> Shift.OFF
                    }
                    lastShiftUp = now
                }
                KType.SYM -> { st.symbols = !st.symbols; st.symPage = 0 }
                KType.SYM2 -> st.symPage = 1 - st.symPage
                KType.LANG -> ime.switchLang()
                KType.ABC -> { st.numpad = false; st.symbols = false }
                KType.BACK -> {}
            }
        }

        fun release(k: KeyRect) { fades[k] = System.currentTimeMillis() }

        /** Левая граница меню долгого нажатия (то же, что при отрисовке). */
        fun popupLeft(k: KeyRect, w: Float): Float {
            val cell = keyW * 1.1f
            return k.rect.left.coerceAtMost(w - cell * k.def.alts.size - 4 * density.density).coerceAtLeast(4 * density.density)
        }

        val input = Modifier.pointerInput(keys) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    for (c in ev.changes) {
                        val pos = c.position
                        when {
                            c.pressed && !c.previousPressed -> {
                                val k = ime.personalHit(keys, pos, hitTest(keys, pos) ?: continue)
                                // Быстрый набор двумя пальцами («перекат»): предыдущая клавиша срабатывает, как только
                                // опускается следующий палец — так буквы не меняются местами и не теряются (как в Gboard).
                                for (o in touches.values) if (o.mode == TMode.NORMAL && !o.consumed) when (o.key.def.type) {
                                    KType.CHAR, KType.COMMA, KType.PERIOD, KType.SPACE -> {
                                        o.job?.cancel(); o.consumed = true
                                        action(o.key.def, o.start)
                                        if (o.key.def.letter) ime.learnTap(o.key.def.label[0], o.start, o.key.rect)
                                        pressed.remove(o.id); release(o.key)
                                    }
                                    KType.SHIFT -> if (k.def.letter) {
                                        // Shift зажат как модификатор: пока держишь — заглавные
                                        o.consumed = true; o.held = true
                                        if (st.shift != Shift.LOCK) st.shift = Shift.LOCK
                                    }
                                    else -> {}
                                }
                                val t = Touch(c.id, k, pos, c.uptimeMillis)
                                touches[c.id] = t
                                pressed[c.id] = k
                                pressAt[k] = System.currentTimeMillis(); fades.remove(k)
                                animUntil = System.currentTimeMillis() + 120
                                feedback(); ime.click(k.def.type)
                                when (k.def.type) {
                                    KType.BACK -> {
                                        ime.backspace()
                                        t.job = scope.launch {
                                            delay(420); var d = 75L
                                            while (true) { ime.backspace(); delay(d); d = (d - 5).coerceAtLeast(28) }
                                        }
                                    }
                                    else -> {
                                        val hasAlt = k.def.alts.isNotEmpty() || k.def.type == KType.COMMA ||
                                                k.def.type == KType.LANG || k.def.type == KType.SPACE
                                        if (hasAlt) t.job = scope.launch {
                                            delay(330)
                                            if (t.mode != TMode.NORMAL || t.consumed) return@launch
                                            feedback(long = true); t.consumed = true
                                            when (k.def.type) {
                                                KType.COMMA -> { if (!st.emojiSearch) st.emoji = true }
                                                KType.LANG -> ime.switchToNextIme()
                                                KType.SPACE -> ime.switchLang()
                                                else -> {
                                                    t.mode = TMode.ALT
                                                    // Сразу выбран символ над пальцем
                                                    t.altIndex = ((t.last.x - popupLeft(k, size.width.toFloat())) / (keyW * 1.1f)).toInt()
                                                        .coerceIn(0, k.def.alts.lastIndex)
                                                    popup = k to t.altIndex; popupAt = System.currentTimeMillis()
                                                    animUntil = popupAt + 160
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            c.pressed && c.previousPressed -> {
                                val t = touches[c.id] ?: continue
                                val dx = pos.x - t.start.x; val dy = pos.y - t.start.y
                                val moved = hypot(dx, dy)
                                when (t.mode) {
                                    TMode.ALT -> {
                                        val n = t.key.def.alts.size
                                        val idx = ((pos.x - popupLeft(t.key, size.width.toFloat())) / (keyW * 1.1f)).toInt().coerceIn(0, n - 1)
                                        if (idx != t.altIndex) { t.altIndex = idx; popup = t.key to idx; feedback() }
                                    }
                                    TMode.SPACE_SWIPE -> {
                                        val step = with(density) { 14.dp.toPx() }
                                        t.acc += pos.x - t.last.x
                                        while (abs(t.acc) >= step) { ime.moveCursor(if (t.acc > 0) 1 else -1); t.acc -= if (t.acc > 0) step else -step; feedback() }
                                    }
                                    TMode.BACK_SWIPE -> {
                                        val step = with(density) { 36.dp.toPx() }
                                        t.acc += pos.x - t.last.x
                                        while (t.acc <= -step) { ime.deleteWordBefore(); t.acc += step; feedback() }
                                    }
                                    TMode.GLIDE -> {
                                        // Не копим лишние точки: достаточно шага ~1/6 клавиши
                                        if (hypot(pos.x - trail.last().x, pos.y - trail.last().y) > keyW / 6f) {
                                            trail += pos; trailT += System.currentTimeMillis()
                                        }
                                    }
                                    TMode.NORMAL -> if (moved > slop && !t.consumed) {
                                        when {
                                            t.key.def.type == KType.SPACE && abs(dx) > abs(dy) -> { t.job?.cancel(); t.mode = TMode.SPACE_SWIPE; t.acc = dx }
                                            t.key.def.type == KType.BACK && dx < 0 && abs(dx) > abs(dy) -> { t.job?.cancel(); t.mode = TMode.BACK_SWIPE; t.acc = dx }
                                            // Свайп-набор только при уверенном движении — случайный «смаз» при тапе остаётся тапом
                                            t.key.def.letter && s.kbGlide && touches.size == 1 && !st.symbols && moved > keyW * 0.85f -> {
                                                t.job?.cancel(); t.mode = TMode.GLIDE; trail.clear(); trailT.clear()
                                                val now = System.currentTimeMillis(); trail += t.start; trail += pos; trailT += now; trailT += now
                                                pressed.remove(c.id); release(t.key)
                                            }
                                        }
                                    }
                                }
                                t.last = pos
                            }
                            !c.pressed && c.previousPressed -> {
                                val t = touches.remove(c.id) ?: continue
                                t.job?.cancel()
                                pressed.remove(c.id)?.let { release(it) }
                                when (t.mode) {
                                    TMode.NORMAL -> when {
                                        t.held -> { st.shift = Shift.OFF; lastShiftUp = 0; ime.updateShift() }
                                        !t.consumed && t.key.def.type != KType.BACK -> {
                                            action(t.key.def, t.start)
                                            if (t.key.def.letter) ime.learnTap(t.key.def.label[0], t.start, t.key.rect)
                                        }
                                    }
                                    TMode.ALT -> { t.key.def.alts.getOrNull(t.altIndex)?.let { ime.type(it) }; popup = null }
                                    TMode.GLIDE -> {
                                        val seq = StringBuilder()
                                        var prev: Char? = null
                                        var len = 0f
                                        var lp: Offset? = null
                                        for (p in trail) {
                                            lp?.let { len += hypot(p.x - it.x, p.y - it.y) }; lp = p
                                            val k = hitTest(keys, p) ?: continue
                                            if (!k.def.letter) continue
                                            val ch = k.def.label[0]
                                            if (ch != prev) { seq.append(ch); prev = ch }
                                        }
                                        // След не обрываем — он догорает сам
                                        val end = System.currentTimeMillis() + 450
                                        trail.clear(); trailT.clear(); animUntil = end
                                        ime.commitGlide(seq.toString(), centers, len, keyW)
                                    }
                                    else -> {}
                                }
                            }
                        }
                        c.consume()
                    }
                }
            }
        }

        val shiftOn = st.shift != Shift.OFF
        val enterIcon = when ((ime.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "go"
            EditorInfo.IME_ACTION_DONE -> "done"
            else -> "enter"
        }.let { if (st.emojiSearch) "search" else it }
        val radius = with(density) { s.kbRadius.dp.toPx() }
        val keyColor = cs.surfaceContainerHighest
        val specialColor = cs.surfaceContainerHigh
        val downColor = cs.surfaceVariant
        val iconPx = with(density) { 22.dp.toPx() }
        val hintStyle = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)
        val keyStyle = TextStyle(fontSize = textSize, color = cs.onSurface)
        val smallStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.Medium)
        val spaceStyle = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant)
        val transparentKeys = s.kbTheme == 4
        val borders = s.kbBorders

        // Слой 1: клавиши. Перерисовывается только при нажатиях/смене регистра, не на каждый кадр свайпа.
        Canvas(Modifier.matchParentSize().then(input)) {
            val pressedKeys = pressed.values.toSet()
            val now = if (fades.isNotEmpty()) { frame; System.currentTimeMillis() } else 0L
            for (k in keys) {
                val d = k.def
                val down = k in pressedKeys
                val base = when {
                    d.type == KType.ENTER -> cs.primaryContainer
                    d.type == KType.SHIFT && st.shift == Shift.LOCK -> cs.primaryContainer
                    d.type == KType.CHAR || d.type == KType.SPACE || d.type == KType.COMMA || d.type == KType.PERIOD -> keyColor
                    else -> specialColor
                }
                val fadeK = fades[k]?.let { 1f - ((now - it).toFloat() / FADE_MS).coerceIn(0f, 1f) } ?: 0f
                var bg = if (down) downColor else if (fadeK > 0f) lerp(base, downColor, fadeK) else base
                if (transparentKeys && !down && d.type != KType.ENTER) bg = bg.copy(alpha = 0.72f)
                drawRoundRect(bg, k.rect.topLeft, k.rect.size, CornerRadius(radius))
                if (borders) drawRoundRect(cs.outlineVariant, k.rect.topLeft, k.rect.size, CornerRadius(radius), style = Stroke(1.dp.toPx()))
                when (d.type) {
                    KType.CHAR, KType.COMMA, KType.PERIOD -> {
                        val label = if (d.letter && shiftOn) d.label.uppercase() else d.label
                        centerText(tm.measure(label, keyStyle), k.rect)
                        if (d.type == KType.COMMA) icon(pIcons["emoji"]!!, Rect(k.rect.right - 16.dp.toPx(), k.rect.top + 3.dp.toPx(),
                            k.rect.right - 4.dp.toPx(), k.rect.top + 15.dp.toPx()), cs.onSurfaceVariant)
                        else d.alts.firstOrNull()?.let { h ->
                            val r = tm.measure(h, hintStyle)
                            drawText(r, topLeft = Offset(k.rect.right - r.size.width - 5.dp.toPx(), k.rect.top + 2.dp.toPx()))
                        }
                    }
                    KType.SPACE -> centerText(tm.measure(d.label, spaceStyle), k.rect)
                    KType.SHIFT -> icon(pIcons[if (st.shift == Shift.LOCK) "caps" else if (shiftOn) "shiftOn" else "shift"]!!, k.rect.centerSquare(iconPx),
                        if (st.shift == Shift.LOCK) cs.onPrimaryContainer else cs.onSurface)
                    KType.BACK -> icon(pIcons["back"]!!, k.rect.centerSquare(iconPx), cs.onSurface)
                    KType.LANG -> icon(pIcons["lang"]!!, k.rect.centerSquare(iconPx), cs.onSurface)
                    KType.ENTER -> icon(pIcons[enterIcon]!!, k.rect.centerSquare(iconPx), cs.onPrimaryContainer)
                    else -> centerText(tm.measure(d.label, smallStyle), k.rect)
                }
            }
        }

        // Слой 2: след свайпа, увеличенная буква и меню долгого нажатия — анимируются по кадрам.
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_VARIABLE") val tick = frame
            val now = System.currentTimeMillis()
            if (trail.size > 1) {
                val life = 450f
                for (i in 1 until trail.size) {
                    val age = (now - (trailT.getOrNull(i) ?: now)).toFloat()
                    if (age > life) continue
                    val k = 1f - age / life
                    drawLine(cs.primary.copy(alpha = 0.7f * k), trail[i - 1], trail[i], strokeWidth = (2f + 5f * k) * density.density,
                        cap = StrokeCap.Round)
                }
            }
            if (s.kbPreview && popup == null) for (k in pressed.values) {
                if (k.def.type != KType.CHAR && k.def.type != KType.COMMA && k.def.type != KType.PERIOD) continue
                // Пузырь «выпрыгивает»: быстрое увеличение с лёгким перелётом
                val p = easeOutBack(((now - (pressAt[k] ?: 0L)) / 90f).coerceIn(0f, 1f))
                val w = k.rect.width * 1.25f * (0.7f + 0.3f * p); val h = k.rect.height * 1.15f * (0.7f + 0.3f * p)
                val r = Rect(Offset(k.rect.center.x - w / 2, k.rect.top - h - 4.dp.toPx() * p), Size(w, h))
                drawRoundRect(cs.primaryContainer.copy(alpha = p.coerceIn(0f, 1f)), r.topLeft, r.size, CornerRadius(radius * 1.2f))
                val label = if (k.def.letter && shiftOn) k.def.label.uppercase() else k.def.label
                centerText(tm.measure(label, keyStyle.copy(fontSize = textSize * 1.35f, color = cs.onPrimaryContainer)), r)
            }
            popup?.let { (k, sel) ->
                val alts = k.def.alts
                val cell = keyW * 1.1f
                val p = easeOutBack(((now - popupAt) / 140f).coerceIn(0f, 1f))
                val h = k.rect.height * 1.1f
                val left = popupLeft(k, size.width)
                val top = k.rect.top - h - 6.dp.toPx() + (1f - p) * 10.dp.toPx()
                val a = p.coerceIn(0f, 1f)
                drawRoundRect(cs.surfaceContainerHigh.copy(alpha = a), Offset(left - 4.dp.toPx(), top - 4.dp.toPx()),
                    Size(cell * alts.size + 8.dp.toPx(), h + 8.dp.toPx()), CornerRadius(radius * 1.3f))
                alts.forEachIndexed { i, alt ->
                    val r = Rect(Offset(left + i * cell, top), Size(cell, h))
                    if (i == sel) drawRoundRect(cs.primary.copy(alpha = a), r.topLeft, r.size, CornerRadius(radius))
                    centerText(tm.measure(if (k.def.letter && shiftOn) alt.uppercase() else alt,
                        keyStyle.copy(color = (if (i == sel) cs.onPrimary else cs.onSurface).copy(alpha = a))), r)
                }
            }
        }
    }
}

private const val FADE_MS = 160L

private fun easeOutBack(t: Float): Float {
    val c1 = 1.4f; val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

private fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

private fun Rect.centerSquare(px: Float) = Rect(Offset(center.x - px / 2, center.y - px / 2), Size(px, px))

private fun DrawScope.centerText(r: androidx.compose.ui.text.TextLayoutResult, box: Rect) {
    drawText(r, topLeft = Offset(box.center.x - r.size.width / 2f, box.center.y - r.size.height / 2f))
}

private fun DrawScope.icon(p: androidx.compose.ui.graphics.painter.Painter, box: Rect, tint: Color) {
    translate(box.left, box.top) { with(p) { draw(box.size, colorFilter = ColorFilter.tint(tint)) } }
}

/** Значок Shift как в Gboard: контурная стрелка — выкл., залитая — заглавная. */
object ShiftIcons {
    private fun build(fill: Boolean) = ImageVector.Builder("shift", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString("M12,3.5L20.5,12.5L15.5,12.5L15.5,20L8.5,20L8.5,12.5L3.5,12.5Z").toNodes(),
            fill = if (fill) androidx.compose.ui.graphics.SolidColor(Color.Black) else null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round
        )
    }.build()
    val outline by lazy { build(false) }
    val filled by lazy { build(true) }
}
