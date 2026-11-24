package com.nanopero.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GboardTest {
    @Test fun importAndSuggest() {
        val ud = UserDict(File.createTempFile("udict", ".json").also { it.delete() })
        val txt = "# Gboard Dictionary version:1\nспс\tспасибо\tru-RU\n\tНаноПеро\tru-RU\n\tзакодил\t\nbrb\tbe right back\ten-US\n\tпривітулі\tuk\n"
        val r = ud.importGboard(txt)
        println(r)
        assertEquals(4, r.words)  // спасибо, нанопero, закодил, привітулі
        assertEquals(2, r.shortcuts)
        assertTrue(ud.known("ru", "наноперо"))
        assertTrue(ud.known("ru", "закодил"))
        assertTrue(ud.known("uk", "привітулі"))
        val s = Suggest.compute("ru", null, ud, "спс", null, true)
        println(s)
        assertEquals("спасибо", s.items[1])
        assertEquals(null, s.autocorrect)
        val en = Suggest.compute("en", null, ud, "brb", null, true)
        assertEquals("be right back", en.items[1])
        println(ud.exportGboard())
    }
}
