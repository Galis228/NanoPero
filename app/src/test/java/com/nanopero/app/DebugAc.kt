package com.nanopero.app

import org.junit.Assume
import org.junit.Test
import java.io.File

class DebugAc {
    @Test fun dbg() {
        val q = File("/tmp/dbg.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim() ?: ""
        Assume.assumeTrue(q.isNotBlank())
        val lang = System.getenv("DBG_LANG") ?: "uk"
        val d = AutocorrectBench().dictPublic(lang)
        LangModel.loadFile(File("src/main/assets/dict/$lang.lm"), d)
        val user = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
        for (pair in q.split(';')) {
            val (prev, w) = pair.split(' ').let { if (it.size == 2) it[0] to it[1] else null to it[0] }
            val sg = Suggest.compute(lang, d, user, w, prev, true)
            val i = d.find(w)
            println("DBG [$prev] $w (inDict=${i >= 0} f=${if (i >= 0) d.f(i) else -1}) -> auto=${sg.autocorrect} items=${sg.items}")
        }
    }
}
