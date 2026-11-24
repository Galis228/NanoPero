package com.nanopero.app

import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Замер качества автоисправления на тысячах синтетических опечаток (модель «толстого пальца»). */
class AutocorrectBench {
    fun dictPublic(lang: String) = dict(lang)
    private fun dict(lang: String): LangDict {
        val p = ArrayList<Pair<String, Int>>()
        File("src/main/assets/dict/$lang.txt").forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) p += l.substring(0, t) to l.substring(t + 1).toInt() }
        p.sortBy { it.first }
        val d = LangDict(Array(p.size) { p[it].first }, ByteArray(p.size) { p[it].second.toByte() })
        File("src/main/assets/dict/${lang}_apos.txt").takeIf { it.exists() }?.let { f ->
            d.apos = f.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap() }
        val m = HashMap<String, ArrayList<Pair<String, Int>>>()
        File("src/main/assets/dict/${lang}_bi.txt").takeIf { it.exists() }?.forEachLine { l ->
            val sp = l.indexOf(' '); val t = l.indexOf('\t')
            if (sp > 0 && t > sp) { val list = m.getOrPut(l.substring(0, sp)) { ArrayList(4) }; if (list.size < 12) list += l.substring(sp + 1, t) to l.substring(t + 1).toInt() }
        }
        d.next = m
        return d
    }

    private val rows = mapOf("ru" to listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"),
        "uk" to listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"), "en" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))

    private fun neigh(lang: String, c: Char): List<Char> {
        val r = rows[lang]!!
        for ((y, row) in r.withIndex()) { val x = row.indexOf(c); if (x >= 0) {
            val out = ArrayList<Char>()
            for (dy in -1..1) { val rr = r.getOrNull(y + dy) ?: continue; for (dx in -1..1) rr.getOrNull(x + dx)?.let { if (it != c) out += it } }
            // Ближайшие по горизонтали — вдвое чаще
            row.getOrNull(x - 1)?.let { out += it }; row.getOrNull(x + 1)?.let { out += it }
            return out
        } }
        return emptyList()
    }

    private fun typo(lang: String, w: String, rnd: Random): String {
        val i = rnd.nextInt(w.length)
        return when (rnd.nextInt(100)) {
            in 0..54 -> neigh(lang, w[i]).takeIf { it.isNotEmpty() }?.let { w.substring(0, i) + it[rnd.nextInt(it.size)] + w.substring(i + 1) } ?: w
            in 55..69 -> if (w.length > 3) w.removeRange(i, i + 1) else w
            in 70..82 -> if (i < w.length - 1) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else w
            in 83..92 -> neigh(lang, w[i]).takeIf { it.isNotEmpty() }?.let { w.substring(0, i + 1) + it[rnd.nextInt(it.size)] + w.substring(i + 1) } ?: w
            else -> w.substring(0, i + 1) + w[i] + w.substring(i + 1)
        }
    }

    @Test fun bench() {
        val langs = (System.getenv("BENCH_LANGS") ?: "ru,uk,en").split(',')
        System.getenv("DW")?.let { Suggest.distW = it.toFloat() }
        System.getenv("INS")?.let { Suggest.insCost = it.toFloat() }
        System.getenv("DEL")?.let { Suggest.delCost = it.toFloat() }
        System.getenv("MG")?.let { Suggest.margin = it.toFloat() }
        val user = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
        for (lang in langs) {
            val d = dict(lang)
            val rnd = Random(42)
            val common = d.words.indices.filter { d.f(it) >= 125 && d.words[it].length >= 4 && d.words[it].all { c -> c.isLetter() } }.shuffled(rnd).take(1200)
            var ok = 0; var wrong = 0; var none = 0; var n = 0
            val wrongEx = ArrayList<String>(); val noneEx = ArrayList<String>()
            val t0 = System.nanoTime()
            for (i in common) {
                val w = d.words[i]
                var t = typo(lang, w, rnd)
                if (w.length >= 8 && rnd.nextInt(3) == 0) t = typo(lang, t, rnd)
                if (t == w || d.find(t).let { it >= 0 && d.f(it) >= 25 }) continue  // опечатка дала реальное слово — не считаем
                n++
                val s = Suggest.compute(lang, d, user, t, null, true)
                when (s.autocorrect) {
                    w -> ok++
                    null -> { none++; if (noneEx.size < 12) noneEx += "$t→($w)" }
                    else -> { wrong++; if (wrongEx.size < 12) wrongEx += "$t→${s.autocorrect}($w)" }
                }
            }
            val ms = (System.nanoTime() - t0) / 1e6 / n
            // Правильные редкие слова/формы — не трогать
            val valid = d.words.indices.filter { d.f(it) in 25..110 && d.words[it].length >= 4 && d.words[it].all { c -> c.isLetter() } }.shuffled(rnd).take(1200)
            var fp = 0; val fpEx = ArrayList<String>()
            for (i in valid) {
                val w = d.words[i]
                val s = Suggest.compute(lang, d, user, w, null, true)
                if (s.autocorrect != null && s.autocorrect != w) { fp++; if (fpEx.size < 15) fpEx += "$w(${d.f(i)})→${s.autocorrect}" }
            }
            println("BENCH $lang: typos=$n fixed=${"%.1f".format(ok * 100.0 / n)}% wrongFix=${"%.1f".format(wrong * 100.0 / n)}% " +
                    "noFix=${"%.1f".format(none * 100.0 / n)}% | falsePos=${"%.1f".format(fp * 100.0 / valid.size)}% | ${"%.1f".format(ms)} ms/word")
            println("  wrong: $wrongEx"); println("  none: $noneEx"); println("  fp: $fpEx")
        }
    }
}
