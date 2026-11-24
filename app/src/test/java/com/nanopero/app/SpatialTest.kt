package com.nanopero.app

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import java.io.File

class SpatialTest {
    @Test fun spatialAndBigrams() {
        val p = ArrayList<Pair<String, Int>>()
        File("src/main/assets/dict/ru.txt").forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) p += l.substring(0, t) to l.substring(t + 1).toInt() }
        p.sortBy { it.first }
        val d = LangDict(Array(p.size) { p[it].first }, ByteArray(p.size) { p[it].second.toByte() })
        val m = HashMap<String, ArrayList<Pair<String, Int>>>()
        File("src/main/assets/dict/ru_bi.txt").forEachLine { l -> val sp = l.indexOf(' '); val t = l.indexOf('\t')
            if (sp > 0 && t > sp) { val li = m.getOrPut(l.substring(0, sp)) { ArrayList() }; if (li.size < 12) li += l.substring(sp + 1, t) to l.substring(t + 1).toInt() } }
        d.next = m
        val user = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
        val rows = kbLayouts[0].rows.map { r -> r.map { KeyDef(KType.CHAR, it.toString(), letter = true) } }
        val keys = computeLayout(rows, 1080f, 140f, 12f, 0f)
        val centers = keys.associate { it.def.label[0] to it.rect.center }
        val w = keys[0].rect.width; val h = keys[0].rect.height
        // «прибет»: «б» нажата у самой границы с «ю»… а «в» далеко → без координат неясно, с координатами
        fun pts(word: String, wrongIdx: Int, toward: Char): List<Offset?> = word.mapIndexed { i, c ->
            val cc = centers[c]!!
            if (i == wrongIdx) { val t = centers[toward]!!; Offset(cc.x + (t.x - cc.x) * 0.45f, cc.y + (t.y - cc.y) * 0.45f) } else cc
        }
        // Нажали «а» почти на границе с «в» вместо «в»
        val typed = "приает"
        val sp = Spatial(pts(typed, 3, 'в'), centers, w, h)
        val a = Suggest.compute("ru", d, user, typed, null, true)
        val b = Suggest.compute("ru", d, user, typed, null, true, spatial = sp)
        println("SPATIAL без координат: ${a.autocorrect} ${a.items} | с координатами: ${b.autocorrect} ${b.items}")
        println("PREDICT после 'как': " + Suggest.compute("ru", d, user, "", "как", true).items)
        println("PREDICT после 'может': " + Suggest.compute("ru", d, user, "", "может", true).items)
        println("CTX 'до з..': " + Suggest.compute("ru", d, user, "за", "до", true).items)
    }
}
