package com.nanopero.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Реалистичный замер автоисправления: отложенные предложения «печатаются» виртуальным пальцем.
 * Касание = центр клавиши + гауссов шум (как у людей при быстром наборе), плюс редкие пропуски,
 * лишние нажатия и перестановки. Предыдущее слово передаётся как контекст.
 */
class SentenceBench {
    private val rows = mapOf(
        "ru" to listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"),
        "uk" to listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"),
        "en" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
    private val offsets = mapOf("ru" to listOf(0f, 0f, 1f), "uk" to listOf(0f, 0.5f, 1.5f), "en" to listOf(0f, 0.5f, 1.5f))
    private val keyW = 100f; private val keyH = 140f

    private fun centers(lang: String): Map<Char, Offset> = buildMap {
        rows[lang]!!.forEachIndexed { y, row -> row.forEachIndexed { x, c -> put(c, Offset((x + 0.5f + offsets[lang]!![y]) * keyW, (y + 0.5f) * keyH)) } }
        if (lang == "ru") { get('е')?.let { put('ё', it) }; get('ь')?.let { put('ъ', it) } }
        if (lang == "uk") get('г')?.let { put('ґ', it) }
    }

    private fun gauss(r: Random): Float { // Бокс — Мюллер
        val u1 = r.nextDouble().coerceAtLeast(1e-9); val u2 = r.nextDouble()
        return (Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)).toFloat()
    }

    /** Имитация набора слова: возвращает набранное и точки касаний. */
    private fun typeWord(w: String, cs: Map<Char, Offset>, r: Random, sx: Float, sy: Float): Pair<String, List<Offset?>> {
        val out = StringBuilder(); val pts = ArrayList<Offset?>()
        val keys = cs.entries.filter { it.key != 'ё' && it.key != 'ъ' && it.key != 'ґ' }
        fun tap(c: Char) {
            val ctr = cs[c] ?: run { out.append(c); pts += null; return }
            val p = Offset(ctr.x + gauss(r) * sx * keyW, ctr.y + gauss(r) * sy * keyH)
            val hit = keys.minBy { (it.value - p).getDistanceSquared() }.key
            // ё/ъ/ґ набираются долгим нажатием — считаем, что человек их ставит редко: печатает е/ь/г
            out.append(hit); pts += p
        }
        var i = 0
        while (i < w.length) {
            val roll = r.nextFloat()
            when {
                roll < 0.012f && w.length > 3 -> { i++; continue }                         // пропуск буквы
                roll < 0.020f && i < w.length - 1 -> { tap(w[i + 1]); tap(w[i]); i += 2; continue } // перестановка
                roll < 0.028f -> { tap(w[i]); tap(w[i]) }                                    // двойное нажатие
                else -> tap(w[i])
            }
            i++
        }
        return out.toString() to pts
    }

    @Test fun bench() {
        val dir = File("/home/claude/acbench")
        Assume.assumeTrue(dir.isDirectory)
        System.getenv("DW")?.let { Suggest.distW = it.toFloat() }
        System.getenv("MG")?.let { Suggest.margin = it.toFloat() }
        System.getenv("LMW")?.let { Suggest.lmW = it.toFloat() }
        System.getenv("RW")?.let { Suggest.realWord = it.toFloat() }
        System.getenv("MS")?.let { Suggest.minShort = it.toInt() }
        System.getenv("FP")?.let { Suggest.firstPen = it.toFloat() }
        System.getenv("MSC")?.let { Suggest.minScale = it.toFloat() }
        val sx = System.getenv("SX")?.toFloat() ?: 0.30f
        val sy = System.getenv("SY")?.toFloat() ?: 0.24f
        val langs = (System.getenv("BENCH_LANGS") ?: "ru,uk,en").split(',')
        for (lang in langs) {
            val d = AutocorrectBench().dictPublic(lang)
            System.getenv("LM_DIR")?.let { LangModel.loadFile(File(it, "${lang}.lm"), d) }
            val user = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
            val cs = centers(lang)
            val r = Random(11)
            val sents = File(dir, "${lang}_test.txt").readLines().filter { it.isNotBlank() }.map { it.split(' ') }
            // Набираем всё один раз, затем прогоняем варианты параметров
            data class Item(val w: String, val typed: String, val pts: List<androidx.compose.ui.geometry.Offset?>, val prev: String?)
            val items = ArrayList<Item>()
            for (s0 in sents) { var pv: String? = null
                for (w in s0) { if (w.length < 2 || w.any { it !in cs && it != '\'' }) { pv = w; continue }
                    val (t, p) = typeWord(w, cs, r, sx, sy); items += Item(w, t, p, pv); pv = w } }
            val grid = System.getenv("GRID")
            if (grid != null) {
                val lim = System.getenv("GRID_N")?.toInt() ?: 4000
                val sub = items.take(lim)
                for (cfg in grid.split(';')) {
                    val v = cfg.split(',').map { it.toFloat() }
                    Suggest.temp = v[0]; Suggest.theta = v[1]; Suggest.oovScore = v[2]; Suggest.plausBonus = v[3]; Suggest.typedBonus = v[4]
                    if (v.size > 5) Suggest.lmW = v[5]
                    Suggest.probMode = !(v.size > 6 && v[6] == 0f)
                    if (v.size > 7) { Spatial.gauss = v[7] > 0f; if (v[7] > 0f) { Spatial.sx = v[7]; Spatial.sy = v[7] * 0.9f } }
                    if (v.size > 8) Spatial.natsPerUnit = v[8]
                    var tt = 0; var ty = 0; var fx = 0; var wr = 0; var br = 0; var ok2 = 0
                    for (it in sub) {
                        val sg = Suggest.compute(lang, d, user, it.typed, it.prev, true, spatial = Spatial(it.pts, cs, keyW, keyH))
                        val res = (sg.autocorrect ?: it.typed).replace('ё', 'е'); val w = it.w.replace('ё', 'е'); val tp = it.typed.replace('ё', 'е')
                        tt++; if (res == w) ok2++
                        if (tp != w) { ty++; if (res == w) fx++ else if (sg.autocorrect != null) wr++ } else if (res != w) br++
                    }
                    fun pc(a: Int, b: Int) = "%.1f".format(a * 100.0 / maxOf(1, b))
                    println("GRID $lang $cfg | fixed=${pc(fx, ty)} wrong=${pc(wr, ty)} broken=${pc(br, tt - ty)} ACC=${pc(ok2, tt)}")
                }
                continue
            }
            var total = 0; var typos = 0; var fixed = 0; var wrong = 0; var broken = 0; var finalOk = 0
            var ms = 0L; var oov = 0; var oovTypos = 0
            val wrongEx = ArrayList<String>(); val brokenEx = ArrayList<String>(); val missEx = ArrayList<String>()
            for (s in sents) {
                var prev: String? = null
                for (w in s) {
                    if (w.length < 2 || w.any { it !in cs && it != '\'' }) { prev = w; continue }
                    val (typed, pts) = typeWord(w, cs, r, sx, sy)
                    val sp = Spatial(pts, cs, keyW, keyH)
                    val t0 = System.nanoTime()
                    val sg = Suggest.compute(lang, d, user, typed, prev, true, spatial = if (pts.size == typed.length) sp else null)
                    ms += System.nanoTime() - t0
                    val res = sg.autocorrect ?: typed
                    total++
                    val norm = { x: String -> x.replace('ё', 'е') }
                    val ok = norm(res) == norm(w)
                    if (ok) finalOk++
                    val inDict = d.find(w) >= 0 || d.find(w.replace('ё', 'е')) >= 0
                    if (!inDict) oov++
                    if (norm(typed) != norm(w)) {
                        typos++
                        if (!inDict) oovTypos++
                        if (ok) fixed++ else if (sg.autocorrect != null) { wrong++; if (wrongEx.size < 40) wrongEx += "${prev ?: ""} [$typed→${sg.autocorrect}/$w]" }
                        else if (missEx.size < 60) missEx += "${prev ?: ""} [$typed/$w]"
                    } else if (!ok) { broken++; if (brokenEx.size < 15) brokenEx += "${prev ?: ""} [$w→${sg.autocorrect}]" }
                    prev = w
                }
            }
            fun pct(a: Int, b: Int) = "%.1f".format(a * 100.0 / maxOf(1, b))
            println("SBENCH $lang words=$total typos=$typos(${pct(typos, total)}%) | fixed=${pct(fixed, typos)}% wrong=${pct(wrong, typos)}% " +
                    "missed=${pct(typos - fixed - wrong, typos)}% | broken=${pct(broken, total - typos)}% | WORD ACC=${pct(finalOk, total)}% " +
                    "| ${"%.1f".format(ms / 1e6 / total)} ms | OOV=${pct(oov, total)}% (опечаток в словах вне словаря: ${pct(oovTypos, typos)}%)")
            println("  wrong: $wrongEx"); println("  missed: $missEx"); println("  broken: $brokenEx")
        }
    }
}
