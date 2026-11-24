package com.nanopero.app

import org.junit.Test
import java.io.File

class SuggestTest {
    private fun dict(lang: String): LangDict {
        val p = ArrayList<Pair<String, Int>>()
        File("src/main/assets/dict/$lang.txt").forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) p += l.substring(0, t) to l.substring(t + 1).toInt() }
        p.sortBy { it.first }
        val d = LangDict(Array(p.size) { p[it].first }, ByteArray(p.size) { p[it].second.toByte() })
        val o = ArrayList<Pair<String, Int>>()
        File("src/main/assets/dict/${lang}_obscene.txt").forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) o += l.substring(0, t) to l.substring(t + 1).toInt() }
        o.sortBy { it.first }
        d.obscene = LangDict(Array(o.size) { o[it].first }, ByteArray(o.size) { o[it].second.toByte() })
        File("src/main/assets/dict/${lang}_apos.txt").takeIf { it.exists() }?.let { f ->
            d.apos = f.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap() }
        return d
    }

    @Test fun report() {
        val t0 = System.nanoTime()
        val ru = dict("ru"); val uk = dict("uk"); val en = dict("en")
        println("load ms: " + (System.nanoTime() - t0) / 1e6)
        val user = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
        // опечатки — должны исправиться
        val typos = listOf("ru" to "прибет", "ru" to "превет", "ru" to "спасибл", "ru" to "сегодгя", "ru" to "првиет",
            "ru" to "хорошл", "ru" to "делаеш", "ru" to "Првиет", "ru" to "кк", "ru" to "сейчач", "ru" to "работаю", "ru" to "пожвлуйста",
            "ru" to "завтоа", "ru" to "встретимсч", "ru" to "нормальео", "ru" to "сообщениие", "ru" to "ехать", "ru" to "чтото",
            "uk" to "дякуб", "uk" to "привіи", "uk" to "будласка", "en" to "teh", "en" to "becuase", "en" to "tomorow",
            "ru" to "пржалуйтса", "ru" to "здравствуцте", "ru" to "насторение", "ru" to "интепесно", "ru" to "сеготня", "ru" to "приложегте",
            "ru" to "обязпиельно", "ru" to "кррче", "ru" to "посмортю", "uk" to "будь-ласкк", "uk" to "зпрвзу")
        // правильные (в т.ч. редкие формы) — не трогать
        val good = listOf("ru" to "сделаешь", "ru" to "переписываемся", "ru" to "подумаешь", "ru" to "завтракаю", "ru" to "щас",
            "ru" to "ок", "ru" to "норм", "ru" to "приложеньке", "ru" to "скинешь", "ru" to "законтрибьютил", "ru" to "гугл",
            "uk" to "дякую", "uk" to "зробиш", "uk" to "перепишемо", "en" to "thanks", "en" to "gonna")
        repeat(3) { for ((l, w) in typos + good) Suggest.compute(l, when (l) { "ru" -> ru; "uk" -> uk; else -> en }, user, w, null, true) }
        val t1 = System.nanoTime()
        for ((l, w) in typos + good) {
            val d = when (l) { "ru" -> ru; "uk" -> uk; else -> en }
            val s = Suggest.compute(l, d, user, w, null, true)
            val tag = if ((l to w) in typos) "TYPO" else "GOOD"
            println("$tag $l '$w' (f=${d.freqOf(w)}) -> auto=${s.autocorrect}  ${s.items}")
            if (false) listOf("привет", "придет").forEach { c -> println("   $c f=${d.freqOf(c)} d=${Suggest.distance(w, c, LongArray(64), 3f)}") }
        }
        for (w in listOf("блять", "пиздец", "блчть", "пизднц", "сцка")) {
            val off = Suggest.compute("ru", ru, user, w, null, true, allowObscene = false)
            val on = Suggest.compute("ru", ru, user, w, null, true, allowObscene = true)
            println("OBSC '$w' off: auto=${off.autocorrect} ${off.items} | on: auto=${on.autocorrect} ${on.items}")
        }
        for ((l, w) in listOf("en" to "dont", "en" to "im", "en" to "its", "en" to "i", "uk" to "мясо", "uk" to "пять", "uk" to "памяті",
                "ru" to "приветкак", "ru" to "явсе", "ru" to "незнаю", "ru" to "довстречи", "ru" to "всегохорошего", "ru" to "переписываемся", "en" to "seeyou")) {
            val d = when (l) { "ru" -> ru; "uk" -> uk; else -> en }
            val sg = Suggest.compute(l, d, user, w, null, true)
            println("GBRD $l '$w' -> auto=${sg.autocorrect} ${sg.items}")
        }
        println("avg ms: " + (System.nanoTime() - t1) / 1e6 / (typos.size + good.size))
    }
}
