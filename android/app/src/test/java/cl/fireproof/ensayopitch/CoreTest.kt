package cl.fireproof.ensayopitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    private fun deck(): Deck {
        val f = listOf("../../ensayo/assets/default.pptx", "../ensayo/assets/default.pptx", "../../../ensayo/assets/default.pptx")
            .map { File(it) }.first { it.exists() }
        return DeckParser.load(f.readBytes(), "test", true, { null }, Curated.KEYWORDS)
    }

    @Test fun parseNotes() {
        val p = DeckParser.parseNotes("DIEGO BUSTOS (15 s). 'Hola a todos.' (pausa) 'Sigo.' Todos: 'Gracias.'")
        assertNotNull(p)
        assertEquals("Diego Bustos", p!!.speaker)
        assertEquals(15, p.secs)
        assertTrue(p.pause)
        assertFalse(p.text.contains("Gracias"))
        assertNull(DeckParser.parseNotes("(No se presenta)"))
    }

    @Test fun deckDefault() {
        val d = deck()
        assertEquals(13, d.sections.size)
        assertEquals(300, d.totalTarget)
        assertEquals("Diego Bustos", d.speakers[0])
        assertEquals(listOf("Diego Bustos", "César", "Bastián", "Gaspar", "Daniel", "Diego Gutiérrez"), d.speakers)
        assertTrue(d.sections.all { it.keywords.isNotEmpty() && it.tail.isNotEmpty() })
        assertTrue(d.sections[0].hasPause)
    }

    @Test fun numbers() {
        assertEquals("mil setecientos", TextMatch.numberToWords(1700))
        assertEquals("veintiun mil", TextMatch.numberToWords(21000))
        assertEquals("cinco millones", TextMatch.numberToWords(5_000_000))
        assertEquals("doscientos cuarenta mil", TextMatch.numberToWords(240000))
        assertEquals("sesenta y dos", TextMatch.numberToWords(62))
        assertTrue("veintiun mil" in TextMatch.numberForms("21", "mil"))
        assertTrue("mil setecientos" in TextMatch.numberForms("1.700"))
    }

    @Test fun tailAndKeywords() {
        val tail = TextMatch.tailWords("Son dos gastos separados, y nadie los une.")
        assertTrue(TextMatch.tailHit(tail, TextMatch.words("bueno y son dos gastos separados y nadie los une")))
        assertFalse(TextMatch.tailHit(tail, TextMatch.words("hoy hacen cuatro cosas")))
        assertTrue(TextMatch.keywordHit(listOf("hervimos"), "despues la hervimos para"))
        assertEquals("canon", TextMatch.normalize("Cañón!"))
    }

    private fun run(r: Rehearsal, t0: Double, secs: Double, text: String? = null, voiced: Boolean = true): Double {
        var t = t0
        val steps = (secs * 10).toInt()
        for (k in 0 until steps) {
            t += 0.1
            r.onAudio(t, 0.1, voiced)
            if (text != null && k == steps - 1) r.onText(text, true)
            r.poll(t)
        }
        return t
    }

    @Test fun teamAutoAdvance() {
        val d = deck()
        val r = Rehearsal(d, TEAM, "Gaspar")
        var t = 0.0
        r.start(t)
        for (s in d.sections) {
            val i = r.idx
            t = run(r, t, s.targetS * 0.9, s.text)
            t = run(r, t, 1.0, voiced = false)
            assertTrue("lámina ${s.slideNo}", r.finished || r.idx == i + 1)
        }
        assertTrue(r.finished)
        val tot = r.totalElapsed()
        assertTrue("$tot", tot > 260 && tot < 290)
        assertTrue(r.runs.all { it.hits.isNotEmpty() })
    }

    @Test fun noEarlyAdvance() {
        val d = deck()
        val r = Rehearsal(d, TEAM)
        r.start(0.0)
        run(r, 0.0, 2.0, d.sections[0].text)
        assertEquals(0, r.idx)
    }

    @Test fun soloSilenceAndKeys() {
        val d = deck()
        val r = Rehearsal(d, SOLO, "Diego Bustos")
        r.start(0.0)
        assertFalse(r.current!!.byMachine)
        var t = run(r, 0.0, 10.0, "levanten la mano")
        t = run(r, t, 8.0, voiced = false)
        assertEquals(1, r.idx)
        assertTrue(r.current!!.byMachine)
        assertTrue(kotlin.math.abs(r.runs[0].elapsed - 10) < 0.6)
        r.advance(t + 30, "voz-pc")
        assertEquals(2, r.idx)
        r.back(t + 31)
        assertEquals(1, r.idx)
        r.finish(t + 40)
        assertTrue(r.runs[0].hitLabels().isNotEmpty())
    }
}

class GuionTest {
    private fun asset(name: String): File = listOf("../../ensayo/assets/$name", "../ensayo/assets/$name").map { File(it) }.first { it.exists() }

    @Test fun ejemploDocx() {
        val e = GuionParser.parse(asset("plantillas/Guion_ejemplo_FireProof.docx").readBytes())
        assertEquals(13, e.size)
        assertEquals("César", e[1].speaker)
        assertEquals(30, e[1].secs)
        assertTrue(e[0].keywords.isNotEmpty())
        val d = DeckParser.loadWithGuion(e, "ejemplo", asset("default.pptx").readBytes())
        assertEquals(300, d.totalTarget)
        assertEquals(270.0, d.minTotal, 0.01)
        assertEquals(305.0, d.maxTotal, 0.01)
        assertTrue(d.sections[0].title.isNotEmpty())
        assertTrue(d.sections[2].keywords.any { "mil setecientos" in it.alts })
    }

    @Test fun plantillas() {
        assertEquals(1, GuionParser.parse(asset("plantillas/Plantilla_Guion.docx").readBytes()).size)
        assertEquals(2, GuionParser.parse(asset("plantillas/Plantilla_Guion.txt").readBytes()).size)
    }

    @Test fun texto() {
        val txt = "# hola\nQuién habla: Ana\nSegundos: 0:25\nLo que dice:\nPrimera línea.\nSegunda línea.\n---\n" +
            "Lámina: 4\nOrador: Bruno\nTiempo: 1 min\nPuntos clave: 30 %; cliente\nTexto: Hola a todos.\n"
        val e = GuionParser.parse(txt.toByteArray())
        assertEquals(listOf(1, 4), e.map { it.slide })
        assertEquals(25, e[0].secs)
        assertEquals("Primera línea. Segunda línea.", e[0].text)
        assertEquals(60, e[1].secs)
        assertEquals(listOf("30 %", "cliente"), e[1].keywords)
    }
}

class TurnosTest {
    @Test fun turnos() {
        val f = listOf("../../ensayo/assets/default.pptx", "../ensayo/assets/default.pptx").map { File(it) }.first { it.exists() }
        val d = DeckParser.load(f.readBytes(), "t", true, { null }, Curated.KEYWORDS)
        assertEquals(listOf(listOf(3, 4)), d.turnsOf("Bastián").map { t -> t.map { it.slideNo } })
        assertEquals("1 turno · 2 láminas", d.turnLabel("Bastián"))
        assertEquals("2 turnos", d.turnLabel("Daniel"))
        assertEquals("4 turnos", d.turnLabel("Diego Bustos"))
    }
}
