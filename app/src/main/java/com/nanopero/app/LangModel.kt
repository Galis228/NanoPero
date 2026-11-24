package com.nanopero.app

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * Биграммная языковая модель клавиатуры: насколько слово ожидаемо после предыдущего.
 * Возвращает «подъём» (lift) в натуральных логарифмах: ln P(w|prev) − ln P(w).
 *  > 0 — слово типично после prev («как дела»), < 0 — встречалось бы, но не встречается (статистически маловероятно).
 * Сглаживание: для невстреченных пар — откат к униграмме с поправкой по ожидаемому числу встреч (идея из Katz / stupid backoff).
 */
class LangModel(private val uni: IntArray, private val off: IntArray, private val nxt: IntArray, private val cnt: ByteArray, private val total: Long) {

    private fun pairCount(a: Int, b: Int): Int {
        var lo = off[a]; var hi = off[a + 1] - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val v = nxt[m]
            if (v < b) lo = m + 1 else if (v > b) hi = m - 1 else return Math.round(Math.exp((cnt[m].toInt() and 255) / 16.0)).toInt()
        }
        return 0
    }

    fun lift(a: Int, b: Int): Float {
        if (a < 0 || b < 0 || a >= uni.size || b >= uni.size) return 0f
        val ca = uni[a].toDouble(); val cb = uni[b].toDouble()
        if (ca < 1 || cb < 1) return 0f
        val cab = pairCount(a, b)
        val expected = ca * cb / total
        return if (cab > 0) {
            // Сглаживание к ожидаемому числу (не доверяем единичным совпадениям)
            ln((cab + 0.5) / (expected + 0.5)).toFloat().coerceIn(-1f, 7f)
        } else {
            // Пары нет в корпусе: чем чаще мы бы её ожидали увидеть, тем сильнее штраф
            (-ln(1.0 + expected) * 0.8).toFloat().coerceIn(-4f, 0f)
        }
    }

    /** Насколько надёжен контекст: у редкого предыдущего слова статистики мало. */
    fun confidence(a: Int): Float = if (a < 0 || a >= uni.size) 0f else (ln(1.0 + uni[a]) / ln(1.0 + 2000)).toFloat().coerceIn(0f, 1f)

    companion object {
        private val cache = ConcurrentHashMap<String, LangModel>()
        fun get(lang: String): LangModel? = cache[lang]

        fun load(ctx: Context, lang: String, dictSize: Int) {
            if (cache.containsKey(lang)) return
            runCatching { ctx.assets.open("dict/$lang.lm").use { parse(it, dictSize) } }.getOrNull()?.let { cache[lang] = it }
        }

        /** Для тестов. */
        fun loadFile(f: File, d: LangDict) {
            if (!f.exists()) return
            parse(f.inputStream(), d.words.size)?.let { cache[f.nameWithoutExtension] = it }
        }

        fun parse(ins: InputStream, dictSize: Int): LangModel? {
            val bytes = ins.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(5); bb.get(magic)
            if (String(magic) != "NPLM1") return null
            val n = bb.int; val total = bb.long
            if (n != dictSize) return null   // словарь и модель от разных сборок — не используем
            val uni = IntArray(n); bb.asIntBuffer().get(uni); bb.position(bb.position() + n * 4)
            val off = IntArray(n + 1); bb.asIntBuffer().get(off); bb.position(bb.position() + (n + 1) * 4)
            val pairs = off[n]
            val nxt = IntArray(pairs); bb.asIntBuffer().get(nxt); bb.position(bb.position() + pairs * 4)
            val cnt = ByteArray(pairs); bb.get(cnt)
            return LangModel(uni, off, nxt, cnt, total)
        }
    }
}
