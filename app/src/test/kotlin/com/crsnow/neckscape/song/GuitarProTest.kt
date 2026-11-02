package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files written by PyGuitarPro (tools/make_gp_fixtures.py), read by our own reader. */
class GuitarProTest {
    private fun bytes(name: String) = javaClass.getResource("/gp/$name")!!.readBytes()

    private fun describe(e: TabEvent) = e.notes.joinToString(",") { "${it.string}:${it.fret}" + (it.technique?.mark ?: "") }

    @Test fun `reads every version the same way`() {
        for (file in listOf("fixture.gp3", "fixture.gp4", "fixture.500.gp5", "fixture.gp5")) {
            val result = Importers.import(bytes(file), "guitar_pro", file, "solo", null, Tunings.all.first())
            val tab = result.song as Tab
            assertEquals(file, "GP Fixture", tab.title)
            assertEquals(file, "NeckScape tests", tab.attribution)
            assertEquals(file, 96.0, tab.tempoBpm!!, 1e-9)
            assertEquals(file, Tunings.all.first().midi, tab.tuning!!.midi)
            // part "solo" picks the lead track and lands on the Solo marker.
            assertTrue(file, result.note!!.contains("Lead Guitar"))
            assertEquals(file, 1, result.startBar)
            assertEquals(file, listOf("Intro", "Solo", null), tab.measures.map { it.marker })

            val m1 = tab.measures[0].events
            assertEquals(file, listOf("3:5", "3:7h", "1:5/7", "1:7"), m1.map(::describe))
            assertEquals(file, listOf(0.0, 1.0, 2.0, 3.0), m1.map { it.at })

            val m2 = tab.measures[1].events
            assertEquals(file, listOf("2:8b2", "", "3:7", "3:5"), m2.map(::describe))
            assertEquals(file, listOf(2.0, 1.0, 0.5, 0.5), m2.map { it.dur })

            val m3 = tab.measures[2].events
            assertEquals(file, "1:5,2:5", describe(m3[0]))
            assertEquals(file, 3.0, m3[0].dur!!, 1e-9)
            assertEquals(file, "", describe(m3[1])) // dead note left out
        }
    }

    @Test fun `picks the rhythm track by default and a named track on request`() {
        val default = Importers.import(bytes("fixture.gp5"), "guitar_pro", "f.gp5", null, null, Tunings.all.first())
        assertTrue(default.note!!.contains("Rhythm Guitar"))
        val c = (default.song as Tab).measures[0].events[0]
        assertEquals(5, c.notes.size)
        val named = Importers.import(bytes("fixture.gp5"), "guitar_pro", "f.gp5", null, "lead", Tunings.all.first())
        assertTrue(named.note!!.contains("Lead Guitar"))
    }

    /**
     * Guitar Pro 7/8 (.gp) files can't be generated without Guitar Pro, so this
     * reads any real ones in $GP7_DIR (e.g. alphaTab's MPL-licensed test-data)
     * when set, and is skipped otherwise.
     */
    @Test fun `reads guitar pro 7 files when samples are available`() {
        val dir = System.getenv("GP7_DIR")?.let { java.io.File(it) }?.takeIf { it.isDirectory } ?: return
        for (f in dir.listFiles { x -> x.name.endsWith(".gp") }!!.sortedBy { it.name }) {
            val tab = Importers.import(f.readBytes(), "guitar_pro", f.name, null, null, Tunings.all.first()).song as Tab
            val notes = tab.measures.sumOf { m -> m.events.sumOf { it.notes.size } }
            println("${f.name}: ${tab.measures.size} measures, $notes notes, ${tab.timeSignature}, first: " +
                tab.measures.first().events.take(4).joinToString(" ") { e -> e.notes.joinToString(",") { "${it.string}:${it.fret}${it.technique?.mark ?: ""}" } + "/" + e.dur })
            assertTrue(f.name, tab.measures.isNotEmpty())
        }
    }
}
