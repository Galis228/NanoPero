package com.nanopero.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Частотный словарь языка (ассет: 90–160 тыс. словоформ).
 * Слова хранятся в алфавитном порядке: поиск слова и префикса — двоичный, без HashMap (экономия памяти).
 */
class LangDict(val words: Array<String>, val freq: ByteArray) {
    /** Отдельный список ненормативной лексики: такие слова не «исправляются», а в подсказки попадают только по настройке. */
    var obscene: LangDict? = null
    /** Частые пары слов из открытых корпусов (Universal Dependencies): «как» → [«и», «бы», «раз»…]. */
    var next: Map<String, List<Pair<String, Int>>> = emptyMap()
    fun pair(a: String, b: String): Int = next[a]?.firstOrNull { it.first == b }?.second ?: 0
    /** Слова с апострофом по написанию без него: dont → don't, мясо → м'ясо. */
    var apos: Map<String, String> = emptyMap()

    /** Индексы по (первая буква, длина) — кандидаты для исправления опечаток. */
    private val byFirstLen: Map<Int, IntArray> by lazy {
        words.indices.groupBy { key(words[it][0], words[it].length) }.mapValues { it.value.toIntArray() }
    }

    private fun key(c: Char, len: Int) = c.code * 64 + min(len, 63)
    /** Построить индекс заранее (в фоне при загрузке), чтобы первое исправление не тормозило. */
    fun warm() { byFirstLen.size }
    fun candidates(c: Char, len: Int): IntArray? = byFirstLen[key(c, len)]

    fun f(i: Int) = freq[i].toInt() and 0xff

    fun find(w: String): Int {
        var lo = 0; var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = words[mid].compareTo(w)
            if (c == 0) return mid else if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    fun freqOf(w: String) = find(w).let { if (it < 0) 0 else f(it) }

    /** Первые индексы слов, начинающихся с p (в алфавитном порядке). */
    fun prefixRange(p: String): IntRange {
        var lo = 0; var hi = words.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (words[mid] < p) lo = mid + 1 else hi = mid }
        var end = lo
        while (end < words.size && words[end].startsWith(p)) end++
        return lo until end
    }
}

object Dicts {
    private val cache = ConcurrentHashMap<String, LangDict>()
    val langs = listOf("ru", "uk", "en")

    fun get(lang: String): LangDict? = cache[lang]
    fun partnerOf(lang: String) = when (lang) { "ru" -> "uk"; "uk" -> "ru"; else -> null }
    /** Словарь второго кириллического языка: чтобы украинское слово на русской раскладке (и наоборот) не «исправлялось». */
    fun partner(lang: String): LangDict? = partnerOf(lang)?.let { cache[it] }

    /** Файлы словарей уже отсортированы по алфавиту — читаем сразу в массивы, без промежуточных пар и сортировки. */
    private fun readDict(ctx: Context, path: String): LangDict {
        val words = ArrayList<String>(170_000)
        val freq = java.io.ByteArrayOutputStream(170_000)
        ctx.assets.open(path).reader(Charsets.UTF_8).buffered(1 shl 16).useLines { seq ->
            seq.forEach { l ->
                val t = l.indexOf('\t')
                if (t > 0) { words += l.substring(0, t); freq.write(parseSmall(l, t + 1)) }
            }
        }
        val arr = words.toTypedArray()
        var sorted = true
        for (i in 1 until arr.size) if (arr[i - 1] >= arr[i]) { sorted = false; break }
        if (sorted) return LangDict(arr, freq.toByteArray())
        // Запасной путь (старый формат)
        val f = freq.toByteArray()
        val order = arr.indices.sortedBy { arr[it] }
        return LangDict(Array(order.size) { arr[order[it]] }, ByteArray(order.size) { f[order[it]] })
    }

    private fun parseSmall(l: String, from: Int): Int {
        var v = 0
        for (i in from until l.length) { val c = l[i]; if (c in '0'..'9') v = v * 10 + (c - '0') else break }
        return v
    }

    @Synchronized
    fun load(ctx: Context, lang: String): LangDict = cache[lang] ?: run {
        val d = readDict(ctx, "dict/$lang.txt")
        d.warm()
        LangModel.load(ctx, lang, d.words.size)
        d.obscene = runCatching { readDict(ctx, "dict/${lang}_obscene.txt") }.getOrNull()
        d.next = runCatching {
            val m = HashMap<String, ArrayList<Pair<String, Int>>>()
            ctx.assets.open("dict/${lang}_bi.txt").bufferedReader().useLines { seq ->
                seq.forEach { l ->
                    val sp = l.indexOf(' '); val t = l.indexOf('\t')
                    if (sp > 0 && t > sp) {
                        val list = m.getOrPut(l.substring(0, sp)) { ArrayList(4) }
                        if (list.size < 12) list += l.substring(sp + 1, t) to l.substring(t + 1).toInt()
                    }
                }
            }
            m as Map<String, List<Pair<String, Int>>>
        }.getOrDefault(emptyMap())
        d.apos = runCatching {
            ctx.assets.open("dict/${lang}_apos.txt").bufferedReader().useLines { seq ->
                seq.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            }
        }.getOrDefault(emptyMap())
        // Держим в памяти только текущий язык и его «пару» (русский ↔ украинский)
        cache.keys.filter { it != lang && it != partnerOf(lang) }.forEach { cache.remove(it) }
        cache[lang] = d
        d
    }
}

/** Личный словарь: выученные слова и пары «слово → следующее». Только на телефоне. */
class UserDict(private val file: File) {
    constructor(ctx: Context) : this(File(ctx.filesDir, "kb_user.json"))

    val words = ConcurrentHashMap<String, Int>()     // "ru|слово" -> очки (3+ = слово знакомо)
    val bigrams = ConcurrentHashMap<String, Int>()   // "ru|прошлое|следующее" -> сколько раз
    val shortcuts = ConcurrentHashMap<String, String>() // "ru|спс" -> "спасибо"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.optJSONObject("w")?.let { w -> w.keys().forEach { k -> words[k] = w.getInt(k) } }
                o.optJSONObject("b")?.let { b -> b.keys().forEach { k -> bigrams[k] = b.getInt(k) } }
                o.optJSONObject("s")?.let { x -> x.keys().forEach { k -> shortcuts[k] = x.getString(k) } }
            }
        }
    }

    fun count(lang: String, w: String) = words["$lang|$w"] ?: 0
    fun known(lang: String, w: String) = count(lang, w) >= 3

    /**
     * Запомнить слово. Словарное или подтверждённое (выбрано в подсказках / отменено исправление) — сразу знакомо;
     * незнакомое набранное слово становится «своим» только после 3 раз — так опечатки не попадают в словарь.
     */
    fun learn(lang: String, word: String, prev: String?, inDict: Boolean, confirmed: Boolean) {
        val w = word.lowercase()
        if (w.length < 2 || w.any { it.isDigit() }) return
        words.merge("$lang|$w", if (confirmed) 3 else 1, Int::plus)
        if (!prev.isNullOrBlank() && (inDict || confirmed || known(lang, w)))
            bigrams.merge("$lang|${prev.lowercase()}|$w", 1, Int::plus)
        trim(); save()
    }

    fun forget(lang: String, w: String) {
        words.remove("$lang|$w")
        bigrams.keys.filter { it.startsWith("$lang|") && (it.endsWith("|$w") || it.startsWith("$lang|$w|")) }.forEach { bigrams.remove(it) }
        save()
    }

    fun clear() { words.clear(); bigrams.clear(); shortcuts.clear(); save() }

    /** Слово из импорта: сразу «своё». */
    fun confirm(lang: String, word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty() || w.contains(' ')) return
        words.merge("$lang|$w", 3) { a, b -> maxOf(a, b) }
    }

    fun shortcut(lang: String, typed: String): String? = shortcuts["$lang|${typed.lowercase()}"]

    /** Язык записи: из метки (ru-RU, uk, en_US…) или по буквам слова. */
    private fun langOf(tag: String, word: String): String? {
        val t = tag.lowercase()
        return when {
            t.startsWith("ru") -> "ru"
            t.startsWith("uk") || t.startsWith("ua") -> "uk"
            t.startsWith("en") -> "en"
            t.isNotBlank() -> null
            word.any { it in "іїєґІЇЄҐ" } -> "uk"
            word.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' } -> "ru"
            word.any { it in 'a'..'z' || it in 'A'..'Z' } -> "en"
            else -> null
        }
    }

    data class ImportResult(val words: Int, val shortcuts: Int, val skipped: Int)

    /**
     * Импорт личного словаря Gboard: TSV «сокращение \t слово/фраза \t язык»,
     * первая строка «# Gboard Dictionary version:1».
     */
    fun importGboard(text: String): ImportResult {
        var w = 0; var sc = 0; var skip = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val p = line.split('\t')
            val shortcut = p.getOrNull(0)?.trim().orEmpty()
            val expr = p.getOrNull(1)?.trim().orEmpty().ifEmpty { if (p.size == 1) shortcut else "" }
            val tag = p.getOrNull(2)?.trim().orEmpty()
            if (expr.isEmpty()) { skip++; return@forEach }
            val langs = langOf(tag, expr)?.let { listOf(it) } ?: if (tag.isBlank()) Dicts.langs else { skip++; return@forEach }
            langs.forEach { l ->
                if (!expr.contains(' ')) { confirm(l, expr) }
                if (shortcut.isNotEmpty() && shortcut != expr && p.size > 1) shortcuts["$l|${shortcut.lowercase()}"] = expr
            }
            if (!expr.contains(' ')) w++
            if (shortcut.isNotEmpty() && shortcut != expr && p.size > 1) sc++
        }
        save()
        return ImportResult(w, sc, skip)
    }

    /** Экспорт в формат Gboard (чтобы перенести слова обратно). */
    fun exportGboard(): String = buildString {
        append("# Gboard Dictionary version:1\n")
        val tag = mapOf("ru" to "ru-RU", "uk" to "uk-UA", "en" to "en-US")
        words.entries.filter { it.value >= 3 }.sortedBy { it.key }.forEach { e ->
            val l = e.key.substringBefore('|'); append('\t').append(e.key.substringAfter('|')).append('\t').append(tag[l]).append('\n')
        }
        shortcuts.entries.sortedBy { it.key }.forEach { e ->
            val l = e.key.substringBefore('|'); append(e.key.substringAfter('|')).append('\t').append(e.value).append('\t').append(tag[l]).append('\n')
        }
    }

    /** Системный пользовательский словарь Android — доступен, когда НаноПеро выбрана клавиатурой. */
    fun syncSystem(ctx: Context): Int = runCatching {
        var n = 0
        ctx.contentResolver.query(android.provider.UserDictionary.Words.CONTENT_URI,
            arrayOf(android.provider.UserDictionary.Words.WORD, android.provider.UserDictionary.Words.SHORTCUT,
                android.provider.UserDictionary.Words.LOCALE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0) ?: continue
                val sc = c.getString(1).orEmpty()
                val loc = c.getString(2).orEmpty()
                val l = langOf(loc, word)
                (l?.let { listOf(it) } ?: Dicts.langs).forEach { lg ->
                    if (!word.contains(' ')) confirm(lg, word)
                    if (sc.isNotBlank()) shortcuts["$lg|${sc.lowercase()}"] = word
                }
                n++
            }
        }
        if (n > 0) save()
        n
    }.getOrDefault(0)

    fun next(lang: String, prev: String): List<Pair<String, Int>> {
        val p = "$lang|${prev.lowercase()}|"
        return bigrams.entries.filter { it.key.startsWith(p) }.map { it.key.removePrefix(p) to it.value }.sortedByDescending { it.second }
    }

    private fun trim() {
        if (words.size > 20_000) words.entries.sortedBy { it.value }.take(2_000).forEach { words.remove(it.key) }
        if (bigrams.size > 30_000) bigrams.entries.sortedBy { it.value }.take(3_000).forEach { bigrams.remove(it.key) }
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            val o = JSONObject().put("w", JSONObject(words as Map<*, *>)).put("b", JSONObject(bigrams as Map<*, *>))
                .put("s", JSONObject(shortcuts as Map<*, *>))
            val tmp = File(file.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
        }
    }
}

/** Где именно был палец при каждой букве — для «пространственной» оценки опечаток (как в Gboard). */
class Spatial(val points: List<androidx.compose.ui.geometry.Offset?>, val centers: Map<Char, androidx.compose.ui.geometry.Offset>,
              val keyW: Float, val keyH: Float) {
    /** Цена замены буквы на позиции i на букву b: чем ближе касание к клавише b, тем дешевле. */
    fun cost(i: Int, b: Char): Float? {
        val p = points.getOrNull(i) ?: return null
        val c = centers[b] ?: centers[if (b == 'ё') 'е' else if (b == 'ъ') 'ь' else b] ?: return null
        val dx = (p.x - c.x) / keyW; val dy = (p.y - c.y) / keyH
        if (!gauss) {
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            return ((d - 0.35f) * 0.95f).coerceIn(0.12f, 1f)
        }
        // Гауссова модель касания: −log P(точка | клавиша b) относительно ближайшей клавиши (той, что «нажалась»).
        // Результат кэшируется: функция вызывается в самом горячем цикле поиска.
        val tab = memo[i] ?: FloatArray(128) { Float.NaN }.also { memo[i] = it }
        val k = b.code and 127
        val cached = tab[k]
        if (!cached.isNaN()) return cached
        val nh = nh0[i]
        val nb = (dx * dx) / (2 * sx * sx) + (dy * dy) / (2 * sy * sy)
        val r = (base + (nb - nh) / natsPerUnit).coerceIn(base, 1.3f)
        tab[k] = r
        return r
    }

    private val memo = arrayOfNulls<FloatArray>(points.size)
    /** −log-правдоподобие касания для «нажавшейся» клавиши — один раз на позицию. */
    private val nh0 = FloatArray(points.size) { i ->
        val p = points[i]
        val hit = if (p == null) null else nearest(p)
        if (p == null || hit == null) 0f else {
            val hx = (p.x - hit.x) / keyW; val hy = (p.y - hit.y) / keyH
            (hx * hx) / (2 * sx * sx) + (hy * hy) / (2 * sy * sy)
        }
    }

    private fun nearest(p: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset? {
        var best: androidx.compose.ui.geometry.Offset? = null; var bd = Float.MAX_VALUE
        for (c in centers.values) { val d = (c - p).getDistanceSquared(); if (d < bd) { bd = d; best = c } }
        return best
    }

    companion object {
        @JvmField var gauss = true
        @JvmField var sx = 0.30f          // разброс касаний по горизонтали (в ширинах клавиши)
        @JvmField var sy = 0.27f          // по вертикали (в высотах клавиши)
        @JvmField var natsPerUnit = 5f    // 1 «ошибка» в расстоянии ≈ 5 нат (согласовано с distW/temp)
        @JvmField var base = 0.06f        // минимальная цена замены
    }
}

data class Suggestions(val items: List<String>, val autocorrect: String?, val typedKnown: Boolean, val typed: String = "") {
    companion object { val EMPTY = Suggestions(emptyList(), null, true) }
}

/** Подсказки и автоисправление. */
object Suggest {
    // Параметры модели (подобраны замером на тысячах опечаток — см. AutocorrectBench)
    @JvmField var distW = 100f      // цена одной «ошибки» в единицах частоты
    @JvmField var insCost = 0.85f     // пропущенная буква
    @JvmField var delCost = 0.9f     // лишняя буква
    @JvmField var margin = 8f     // насколько лучший вариант должен опережать второй
    @JvmField var minShort = 140   // минимальная частота исправления для слов из 3 букв
    @JvmField var firstPen = 0.25f // штраф за ошибку в первой букве
    // Вероятностный режим (по умолчанию)
    @JvmField var probMode = true
    @JvmField var temp = 20f        // «температура»: единиц частоты на 1 нат
    @JvmField var theta = 0.55f      // минимальная уверенность для автоисправления
    @JvmField var oovScore = -40f    // априорная оценка «набрано неизвестное, но задуманное слово»
    @JvmField var plausBonus = 40f  // у «правдоподобной» словоформы (основа + окончание) шанс быть задуманной выше
    @JvmField var shortOov = 40f    // 2 буквы вне словаря — скорее аббревиатура/сленг
    @JvmField var typedBonus = 45f  // набранное настоящее слово — по умолчанию доверяем человеку
    @JvmField var minAbs = 30       // совсем редкие слова не подставляем
    @JvmField var minScale = 1f    // множитель порогов частоты для исправления
    @JvmField var lmW = 24f       // вес контекста: единиц частоты на 1 нат «подъёма» языковой модели
    @JvmField var realWord = 50f  // насколько исправление должно быть вероятнее набранного настоящего слова
    private val verbEnd = listOf("ю", "у", "ешь", "ишь", "ет", "ит", "ем", "им", "ете", "ите", "ют", "ут", "ат", "ят", "л", "ла", "ло", "ли", "ть", "сь", "ся")

    /** Настоящие английские слова, которые Gboard не превращает в формы с апострофом. */
    private val enNoApos = setOf("its", "well", "were", "shell", "hell", "ill", "id", "wed", "lets", "shed", "whose", "were")

    private val rows = mapOf(
        "ru" to listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"),
        "uk" to listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"),
        "en" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    )
    private val adj = ConcurrentHashMap<String, Map<Char, CharArray>>()
    private val adjMask = ConcurrentHashMap<String, LongArray>()

    /** Соседи клавиш как битовые маски по (код % 64) — быстрая проверка в горячем цикле. */
    private fun masks(lang: String): LongArray = adjMask.getOrPut(lang) {
        val m = LongArray(64)
        neighbors(lang).forEach { (c, arr) -> arr.forEach { n -> m[c.code and 63] = m[c.code and 63] or (1L shl (n.code and 63)) } }
        m
    }

    private fun letters(w: String): Long { var x = 0L; for (c in w) x = x or (1L shl (c.code and 63)); return x }

    /** Частые окончания: форма с ними и известной основой считается правильной, даже если её нет в словаре. */
    private val endings = (
        "а я о е и ы у ю ь й і ї є ом ем ам ям ах ях ой ей ою ею ую юю ая яя ое ее ые ие ый ий ій ого его ому ему ым им ых их іх " +
        "ет ит ут ют ат ят ешь ишь ете ите ла ло ли ть ти сь ся ться тся ешься ишься ются утся ется ится ов ев ами ями " +
        "ость ости ные ный ная ное ного ному ным ных ными ению ение ения ении ний ня нє ння ннях ові еві ах ої єш ємо имо емо ять ють ать уть іть ати ити"
    ).split(' ').toSet()

    private fun neighbors(lang: String): Map<Char, CharArray> = adj.getOrPut(lang) {
        val r = rows[lang] ?: return@getOrPut emptyMap()
        val m = HashMap<Char, MutableSet<Char>>()
        r.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val set = m.getOrPut(c) { HashSet() }
                for (dy in -1..1) {
                    val rr = r.getOrNull(y + dy) ?: continue
                    // Ряды сдвинуты: соседи по диагонали — x и x±1
                    for (dx in -1..1) rr.getOrNull(x + dx)?.let { if (it != c) set += it }
                }
            }
        }
        m.mapValues { it.value.toCharArray() }
    }


    private class Buf { var r0 = FloatArray(32); var r1 = FloatArray(32); var r2 = FloatArray(32) }
    private val buf = ThreadLocal.withInitial { Buf() }

    /**
     * Взвешенное расстояние Дамерау–Левенштейна:
     * соседняя клавиша 0.55, е/ё 0.1, перестановка 0.6, удвоенная буква 0.5. Выход, если превышен лимит.
     */
    fun distance(a: String, b: String, nb: LongArray, limit: Float, sp: Spatial? = null): Float {
        val n = a.length; val m = b.length
        if (abs(n - m) > limit + 0.5f) return 99f
        val bf = buf.get()!!
        if (bf.r0.size <= m + 1) { bf.r0 = FloatArray(m + 8); bf.r1 = FloatArray(m + 8); bf.r2 = FloatArray(m + 8) }
        var pp = bf.r0; var prev = bf.r1; var cur = bf.r2
        for (j in 0..m) prev[j] = j.toFloat()
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val sub = when {
                    ca == cb -> 0f
                    (ca == 'е' && cb == 'ё') || (ca == 'ё' && cb == 'е') -> 0.1f
                    else -> sp?.cost(i - 1, cb) ?: if ((nb[ca.code and 63] shr (cb.code and 63)) and 1L != 0L) 0.55f else 1f
                }
                // Лишняя буква-дубль в набранном («приивет») и пропущенный дубль («клас» → «класс») — дешевле
                // Лишняя буква рядом с соседней клавишей («толстый палец» задел две) — тоже дешевле
                val del = if (i > 1 && a[i - 2] == ca) 0.5f
                    else if ((i > 1 && (nb[a[i - 2].code and 63] shr (ca.code and 63)) and 1L != 0L) ||
                        (i < n && (nb[a[i].code and 63] shr (ca.code and 63)) and 1L != 0L)) delCost * 0.75f else delCost
                val ins = if ((j > 1 && b[j - 2] == cb) || cb == 'ь' || cb == 'ъ') 0.4f else insCost
                var v = min(min(prev[j] + del, cur[j - 1] + ins), prev[j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, pp[j - 2] + 0.6f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit + 1f) return 99f
            val t = pp; pp = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** Есть ли у слова известная основа с типичным окончанием (для славянских языков). */
    private fun plausibleForm(d: LangDict, w: String): Boolean {
        if (w.length < 5) return false
        for (k in 1..4) {
            if (w.length - k < 5) break
            val end = w.takeLast(k)
            if (end !in endings) continue
            val stem = w.dropLast(k)
            val r = d.prefixRange(stem)
            var checked = 0
            for (i in r) {
                if (checked++ > 120) break
                val rest = d.words[i].substring(stem.length)
                if ((rest.isEmpty() || rest in endings) && d.f(i) >= 40) return true
            }
        }
        return false
    }

    private fun matchCase(typed: String, w: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    /**
     * @param midSentenceCapital слово с большой буквы в середине предложения — вероятно имя, не исправляем.
     */
    fun compute(
        lang: String, dict: LangDict?, user: UserDict, typedRaw: String, prev: String?,
        autocorrect: Boolean, midSentenceCapital: Boolean = false, allowObscene: Boolean = false, spatial: Spatial? = null
    ): Suggestions {
        val typed = typedRaw.lowercase()
        if (typed.isEmpty()) {
            if (prev.isNullOrBlank()) return Suggestions.EMPTY
            // Предсказание: сначала твои частые продолжения, потом частые из корпуса
            val p = prev.lowercase()
            val mine = user.next(lang, p).map { it.first }
            val corpus = dict?.next?.get(p)?.map { it.first }?.filter { w -> w.length > 1 || w in setOf("и", "в", "с", "к", "а", "я", "і", "у", "a", "i") } ?: emptyList()
            return Suggestions((mine + corpus).distinct().take(3), null, true)
        }
        val nb = neighbors(lang)
        val nm = masks(lang)
        val prevL = prev?.lowercase()
        // Языковая модель: насколько слово ожидаемо после предыдущего (в единицах частоты словаря)
        val lm = LangModel.get(lang)
        val prevId = if (lm != null && prevL != null) dict?.find(prevL) ?: -1 else -1
        val lmConf = if (prevId >= 0) lm!!.confidence(prevId) else 0f
        fun bigram(w: String): Float {
            if (prevL == null) return 0f
            val userPart = 30f * ln(1f + (user.bigrams["$lang|$prevL|$w"] ?: 0))
            if (lm != null && prevId >= 0) {
                val id = dict?.find(w) ?: -1
                return userPart + (if (id >= 0) lmW * lmConf * lm.lift(prevId, id) else 0f)
            }
            return userPart + 14f * ln(1f + (dict?.pair(prevL, w) ?: 0))
        }
        fun userBoost(w: String) = user.count(lang, w).let { if (it >= 3) 40f + 15f * ln(it.toFloat()) else 0f }

        val idx = dict?.find(typed) ?: -1
        val typedFreq = if (idx >= 0) dict!!.f(idx) else 0
        val obsIdx = dict?.obscene?.find(typed) ?: -1
        val altIdx = Dicts.partner(lang)?.let { a -> a.find(typed).takeIf { it >= 0 && a.f(it) >= 45 } } ?: -1
        val userKnown = user.known(lang, typed) || obsIdx >= 0 || altIdx >= 0
        val known = userKnown || (idx >= 0 && typedFreq >= 25)
        val sources = listOfNotNull(dict, if (allowObscene) dict?.obscene else null)

        val score = HashMap<String, Float>(64)
        fun add(w: String, s: Float) { val o = score[w]; if (o == null || s > o) score[w] = s }

        // 1. Дополнение по префиксу (лучшие по частоте)
        for (src in sources) {
            val r = src.prefixRange(typed)
            var n = 0
            for (i in r) {
                if (n++ > 4000) break
                val w = src.words[i]
                if (w.length == typed.length) continue
                add(w, src.f(i) - 3f * (w.length - typed.length) + userBoost(w) + bigram(w) - 15f)
            }
        }
        user.words.entries.asSequence().filter { it.value >= 3 && it.key.startsWith("$lang|$typed") }.take(200).forEach { e ->
            val w = e.key.substringAfter('|'); if (w != typed) add(w, (dict?.freqOf(w) ?: 60) + userBoost(w) + bigram(w) - 15f)
        }

        // 2. Исправления опечаток
        data class Fix(val w: String, val d: Float, val f: Int, val s: Float)
        val fixes = ArrayList<Fix>()
        if (typed.length >= 2 && dict != null) {
            // Допуск растёт с длиной слова: в длинных словах можно исправить 3–4 ошибки
            val limit = when {
                typed.length <= 2 -> 0.65f; typed.length <= 3 -> 0.9f; typed.length <= 4 -> 1.1f; typed.length <= 6 -> 1.7f
                typed.length <= 8 -> 2.3f; typed.length <= 11 -> 3.0f; else -> 3.6f
            }
            // Ошибка в первой букве: соседние клавиши (с небольшим штрафом; модель касаний уточняет)
            val firsts = CharArray(1) { typed[0] } +
                    (nb[typed[0]] ?: CharArray(0)) +
                    // Перестановка первых двух букв («ан» → «на»)
                    (if (typed.length >= 2 && typed[1] != typed[0]) charArrayOf(typed[1]) else CharArray(0)) +
                    (if (typed[0] == 'е') charArrayOf('ё') else CharArray(0))
            val span = when { typed.length >= 10 -> 3; typed.length >= 6 -> 2; else -> 1 }
            val lens = (typed.length - span).coerceAtLeast(1)..(typed.length + span)
            val tm = letters(typed)
            val maxDiff = (limit * 2 + 2).toInt()
            for (src in sources) for (f0 in firsts) for (len in lens) {
                val ids = src.candidates(f0, len) ?: continue
                for (i in ids) {
                    val w = src.words[i]
                    // Быстрый отсев: слишком разный набор букв
                    if (java.lang.Long.bitCount(tm xor letters(w)) > maxDiff) continue
                    if (w == typed) continue
                    var dist = distance(typed, w, nm, limit, spatial)
                    if (dist > limit) continue
                    if (f0 != typed[0]) dist += firstPen   // ошибка в первой букве — реже
                    val fr = src.f(i)
                    val s = fr - distW * dist + userBoost(w) + bigram(w)
                    fixes += Fix(w, dist, fr, s)
                    add(w, s)
                }
            }
        }
        // Пропущенный дефис («чтото» → «что-то», «будьласка» → «будь-ласка»)
        if (typed.length >= 5 && dict != null && '-' !in typed) {
            for (k in 2..typed.length - 2) {
                val w = typed.substring(0, k) + "-" + typed.substring(k)
                val i = dict.find(w)
                if (i < 0 || dict.f(i) < typedFreq + 15) continue
                val fr = dict.f(i)
                val sc = fr - distW * 0.15f + userBoost(w) + bigram(w)
                fixes += Fix(w, 0.15f, fr, sc); add(w, sc)
            }
        }
        // Пропущена первая буква («счезли» → «исчезли»): ищем слова на одну букву длиннее, у которых дальше идёт набранное
        if (typed.length >= 3 && dict != null) {
            for (c in neighbors(lang).keys) {
                val ids = dict.candidates(c, typed.length + 1) ?: continue
                for (i in ids) {
                    val w = dict.words[i]
                    if (w[1] != typed[0] || (w.length > 2 && w[2] != typed[1])) continue
                    val dist = distance(typed, w, nm, 1.6f, spatial) + firstPen * 0.5f
                    if (dist > 1.6f) continue
                    if (fixes.any { it.w == w }) continue
                    val fr = dict.f(i)
                    val sc = fr - distW * dist + userBoost(w) + bigram(w)
                    fixes += Fix(w, dist, fr, sc); add(w, sc)
                }
            }
        }
        fixes.sortByDescending { it.s }

        // 3. Решение об автоисправлении
        // Набранное слово как кандидат: его частота + контекст (для «настоящих» слов, набранных по ошибке)
        val typedScore = if (idx >= 0) typedFreq + bigram(typed) + userBoost(typed) else -999f
        var auto: String? = null
        val top = fixes.firstOrNull()
        fun norm(x: String) = x.replace('ё', 'е')
        if (probMode) {
            // Вероятностное решение (шумный канал): каждый кандидат — log P(слово) + log P(касания|слово) + контекст,
            // само набранное — тоже кандидат (если в словаре — со своей частотой, иначе — «неизвестное слово»).
            // Исправляем, только если апостериорная вероятность лучшего кандидата выше порога.
            if (autocorrect && !userKnown && !midSentenceCapital && typed.length >= 2 && !typed.contains('\'') && top != null) {
                val plaus = idx < 0 && lang != "en" && dict != null && typed.length >= 5 && plausibleForm(dict, typed)
                val typedS = if (idx >= 0) typedScore + typedBonus else oovScore + (if (plaus) plausBonus else 0f) + (if (typed.length <= 2) shortOov else 0f)
                // Кандидаты без дублей е/ё
                val seen = HashSet<String>()
                val cands = fixes.filter { seen.add(norm(it.w)) && norm(it.w) != norm(typed) }.take(12)
                val mx = maxOf(typedS, cands.maxOf { it.s })
                var z = Math.exp(((typedS - mx) / temp).toDouble())
                for (c in cands) z += Math.exp(((c.s - mx) / temp).toDouble())
                val best = cands.maxBy { it.s }
                val p = Math.exp(((best.s - mx) / temp).toDouble()) / z
                if (p >= theta && best.f >= minAbs) {
                    auto = best.w
                    if ('ё' in typed && 'ё' !in best.w) fixes.firstOrNull { norm(it.w) == norm(best.w) && 'ё' in it.w }?.let { auto = it.w }
                }
            }
        } else {
        // Мелкая ошибка (дубль буквы, пропущенный ь/ъ, е/ё) у частого слова — исправляем без проверки окончаний
        val tinyFix = top != null && top.d <= 0.55f && top.f >= 60
        val shortOk = typed.length == 2 && lmConf > 0.3f && (idx < 0 || typedFreq < 110)
        if (autocorrect && !userKnown && !midSentenceCapital && (typed.length >= 3 || shortOk) && !typed.contains('\'') &&
            (tinyFix || !(idx < 0 && lang != "en" && dict != null && plausibleForm(dict, typed)))
        ) {
            val best = top
            val second = fixes.drop(1).firstOrNull { norm(it.w) != norm(best?.w ?: "") }
            if (best != null) {
                // Украинский словарь дополнен книжными словами с заниженной частотой — пороги ниже
                val minScale = if (lang == "uk") minScale * 0.75f else minScale
                val minFreq = when {
                    typed.length == 2 -> 200
                    typed.length <= 3 -> minShort
                    best.d <= 0.65f -> (45 * minScale).toInt()
                    best.d <= 1.1f -> (70 * minScale).toInt()
                    best.d <= 2.2f -> (100 * minScale).toInt()
                    else -> (120 * minScale).toInt()
                }
                val clearWinner = second == null || best.s - second.s >= margin || best.d + 0.3f <= second.d
                // Слово есть в словаре? Исправляем, только если это явно редкая опечатка частого слова
                // Набранное слово есть в словаре? Исправляем, только если это почти наверняка опечатка частого слова
                val rareTyped = (idx < 0 && typed.length >= 3) || (best.f - typedFreq >= 95 && best.d <= 0.65f && typedFreq < 90) ||
                        (best.d <= 0.45f && best.f - typedFreq >= 45) ||
                        // Контекст: набранное слово существует, но после предыдущего слова исправление намного вероятнее
                        (lmConf > 0.3f && best.d <= (if (typed.length == 2) 0.6f else 1.0f) && best.s - typedScore >= realWord) ||
                        (typed.length == 2 && idx < 0 && best.d <= 0.6f && best.s - best.f >= 0f)
                if (best.f >= minFreq && clearWinner && rareTyped) {
                    auto = best.w
                    // Набрал «ё» — берём вариант с «ё», если он есть
                    if ('ё' in typed && 'ё' !in best.w) fixes.firstOrNull { norm(it.w) == norm(best.w) && 'ё' in it.w }?.let { auto = it.w }
                }
            }
        }

        }

        // 4. Как в Gboard: пропущенный апостроф (dont → don't, мясо → м'ясо) и «i» → «I»
        val aposWord = if (!user.known(lang, typed) && !typed.contains('\'')) dict?.apos?.get(typed)
            ?.takeIf { lang != "en" || typed !in enNoApos }
            // «streets» — это множественное число, а не «street's»: притяжательные формы — только для несуществующих слов
            ?.takeIf { a -> !(lang == "en" && a.endsWith("'s") && typedFreq >= 25) && !(lang == "en" && a.endsWith("s'")) }?.let { if (lang == "en" && it.startsWith("i'")) "I" + it.substring(1) else it } else null
        if (aposWord != null) { add(aposWord, 9_000f); if (autocorrect && !midSentenceCapital) auto = aposWord }
        if (lang == "en" && typed == "i" && autocorrect) auto = "I"

        // 5. Пропущенный пробел: «приветкак» → «привет как» (только если обычного исправления нет)
        // Слитное написание, которое встречается в переписках («незнаю», «seeyou»), тоже разделяем, если пара слов намного частотнее
        if (autocorrect && aposWord == null && dict != null && !user.known(lang, typed) && typed.length >= 4 && !midSentenceCapital) {
            var bestS = 0f; var split: String? = null
            for (k in 1..typed.length - 2) {
                val a = typed.substring(0, k); val b = typed.substring(k)
                // «ссказал» — это лишняя буква, а не «с сказал»
                if (a.last() == b.first()) continue
                val ia = dict.find(a); if (ia < 0) continue
                val ib = dict.find(b); if (ib < 0) continue
                val fa = dict.f(ia); val fb = dict.f(ib)
                val pr = dict.pair(a, b) + (user.bigrams["$lang|$a|$b"] ?: 0)
                // «незнаю», «нехочу» — частое слитное написание «не» с глаголом (но «непросто», «нечто» — слитно правильно)
                val neg = a == "не" && verbEnd.any { b.endsWith(it) } && b.length >= 3
                if ((a.length == 1 && fa < 200) || (a.length == 2 && fa < 175) || (b.length <= 2 && fb < 175)) continue
                // Без подтверждения корпусом делим только «длинное + длинное» частые слова
                if (pr == 0 && !neg && ((a.length < 3 && fa < 200) || (b.length < 3 && fb < 200) || minOf(fa, fb) < 150)) continue
                if (typedFreq >= 30 && !(pr > 0 || neg) ) continue
                if (typedFreq >= 30 && minOf(fa, fb) - typedFreq < 90) continue
                val sc = minOf(fa, fb) + 12f * ln(1f + dict.pair(a, b)) + 6f * ln(1f + (user.bigrams["$lang|$a|$b"] ?: 0))
                if (sc > bestS) { bestS = sc; split = "$a $b" }
            }
            val fixS = fixes.firstOrNull { it.w == auto }?.let { it.f - distW * it.d } ?: if (auto == null) -999f else 999f
            val confirmed = split != null && (dict.pair(split.substringBefore(' '), split.substringAfter(' ')) > 0 || split.startsWith("не "))
            if (split != null && bestS >= 115 && bestS > fixS + (if (confirmed) 15 else 80)) { auto = split; add(split, 8_000f) }
        }

        if (known) add(typed, 10_000f)
        val expansion = user.shortcut(lang, typed)
        val ranked = score.entries.sortedByDescending { it.value }.map { it.key }
        if (expansion != null) auto = null  // сокращение не заменяем автоматически — только подсказкой
        // Мат: если набранное ближе к нецензурному слову, чем к обычному, — не «исправляем» его в приличное
        dict?.obscene?.let { ob ->
            if (typed.length >= 3 && auto != null) {
                var bw: String? = null; var bd = 99f
                val lim = (fixes.firstOrNull()?.d ?: 1f) + 0.01f
                for (w in ob.words) {
                    if (abs(w.length - typed.length) > 2) continue
                    val d = distance(typed, w, nm, lim)
                    if (d < bd) { bd = d; bw = w }
                }
                val bestD = fixes.firstOrNull()?.d ?: 99f
                if (bw != null && bd <= bestD) auto = if (allowObscene) bw else null
            }
        }
        val main = expansion ?: auto ?: ranked.firstOrNull { it != typed }
        val items = buildList {
            add(typedRaw)
            main?.let { add(if (it == expansion) it else matchCase(typedRaw, it)) }
            ranked.firstOrNull { it != typed && it != main }?.let { add(matchCase(typedRaw, it)) }
        }.distinct()
        // Без разрешения мат не предлагаем, даже если слово выучено
        val filtered = if (allowObscene) items else items.filterIndexed { i, w -> i == 0 || (dict?.obscene?.find(w.lowercase()) ?: -1) < 0 }
        return Suggestions(filtered, auto?.let { matchCase(typedRaw, it) }, known || idx >= 0, typedRaw)
    }
}
