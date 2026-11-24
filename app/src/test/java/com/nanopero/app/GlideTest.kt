package com.nanopero.app

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class GlideTest {
    private fun dict(lang: String): LangDict {
        val p = ArrayList<Pair<String, Int>>()
        File("src/main/assets/dict/$lang.txt").forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) p += l.substring(0, t) to l.substring(t + 1).toInt() }
        p.sortBy { it.first }
        return LangDict(Array(p.size) { p[it].first }, ByteArray(p.size) { p[it].second.toByte() })
    }

    @Test fun glide() {
        val rows = kbLayouts[0].rows.map { r -> r.map { KeyDef(KType.CHAR, it.toString(), letter = true) } }
        val keys = computeLayout(rows, 1080f, 140f, 12f, 0f)
        val centers = keys.associate { it.def.label[0] to it.rect.center }
        val d = dict("ru"); val user = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
        for (w in listOf("привет", "спасибо", "сегодня", "хорошо", "как", "дела", "завтра", "давай", "люблю", "работа")) {
            // Имитируем движение пальца: точки через каждые 15 px между центрами букв (с небольшим дрожанием)
            val pts = ArrayList<Offset>()
            for (i in 0 until w.length - 1) {
                val a = centers[w[i]]!!; val b = centers[w[i + 1]]!!
                val n = (hypot(b.x - a.x, b.y - a.y) / 15f).toInt().coerceAtLeast(1)
                for (k in 0..n) pts += Offset(a.x + (b.x - a.x) * k / n + (k % 3 - 1) * 6f, a.y + (b.y - a.y) * k / n)
            }
            val seq = StringBuilder(); var prev: Char? = null; var len = 0f; var lp: Offset? = null
            for (p in pts) {
                lp?.let { len += hypot(p.x - it.x, p.y - it.y) }; lp = p
                val ch = hitTest(keys, p)!!.def.label[0]; if (ch != prev) { seq.append(ch); prev = ch }
            }
            val res = Glide.decode(d, user, "ru", seq.toString(), centers, len, keys[0].rect.width)
            println("GLIDE $w  seq=$seq -> $res")
        }
    }
}
