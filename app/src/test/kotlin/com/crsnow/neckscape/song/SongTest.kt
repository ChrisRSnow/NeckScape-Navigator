package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.ChordSymbol
import com.crsnow.neckscape.theory.Tunings
import com.crsnow.neckscape.theory.describeVoicing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongTest {
    private val std = Tunings.all.first()

    /** schema/fixtures, from the module or the project root. */
    private val fixtures = listOf(File("../schema/fixtures"), File("schema/fixtures")).first { it.isDirectory }

    @Test fun `every shared fixture gives the expected result`() {
        val payloads = fixtures.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".expect.json") }!!.sortedBy { it.name }
        assertTrue("found ${payloads.size} fixtures", payloads.size >= 20)
        for (file in payloads) {
            val expect = Json.parse(File(file.path.removeSuffix(".json") + ".expect.json").readText()) as Map<*, *>
            val reader = SchemaReader(std, 22)
            try {
                val checked = reader.read(Json.parse(file.readText()))
                assertEquals("${file.name} should be rejected", expect["ok"], true)
                val got = checked.warnings.map { "${it.code}@${it.path}" }.sorted()
                assertEquals(file.name, (expect["warnings"] as List<*>).map { it.toString() }.sorted(), got)
            } catch (e: BridgeError) {
                assertEquals("${file.name} should be accepted, got ${e.code} at ${e.path}: ${e.detail}", false, expect["ok"])
                assertEquals(file.name, expect["error"], e.code)
                assertEquals(file.name, expect["path"], e.path)
            }
        }
    }

    @Test fun `chord symbols parse in their common spellings`() {
        fun name(s: String) = ChordSymbol.parse(s)?.name
        assertEquals("Am", name("Am"))
        assertEquals("F♯m7♭5", name("F#m7b5"))
        assertEquals("B♭maj7", name("Bbmaj7"))
        assertEquals("B♭maj7", name("BbM7"))
        assertEquals("Cm7", name("Cmin7"))
        assertEquals("C/G", name("C/G"))
        assertEquals("E7♯9", name("E7#9"))
        assertEquals("Cdim7", name("C°7"))
        assertEquals("Cm7♭5", name("Cø7"))
        assertEquals("Gsus4", name("Gsus"))
        assertEquals("Cadd9", name("C(add9)"))
        assertNull(ChordSymbol.parse("H7"))
        assertNull(ChordSymbol.parse("Cfoo"))
    }

    @Test fun `voicing mismatch names what the frets play`() {
        assertEquals("F major seventh", describeVoicing(std, listOf(1, 3, 3, 2, 1, 0)))
        assertEquals("C major", describeVoicing(std, listOf(null, 3, 2, 0, 1, 0)))
        val warnings = SchemaReader(std, 22).checkVoicing(std, ChordSymbol.parse("F")!!, listOf(1, 3, 3, 2, 1, 0), "chords.F")
        assertEquals("Frets give F major seventh, not F", warnings.single().detail)
        // A C7 without its 5th is still a C7.
        assertTrue(SchemaReader(std, 22).checkVoicing(std, ChordSymbol.parse("C7")!!, listOf(null, 3, 2, 3, 1, 0), "x").isEmpty())
    }

    @Test fun `midi round trip keeps notes, tempo and markers`() {
        val notes = listOf(MidiNote(0.0, 1.0, 45), MidiNote(1.0, 1.0, 48), MidiNote(2.0, 0.5, 52), MidiNote(2.5, 0.5, 57), MidiNote(4.0, 2.0, 64))
        val bytes = Midi.write(notes, "Test", 120.0, TimeSignature(4, 4), listOf(4.0 to "Solo"))
        val tab = Midi.read(bytes, "test.mid", null, null, std).song as Tab
        assertEquals(120.0, tab.tempoBpm!!, 0.5)
        assertEquals(2, tab.measures.size)
        assertEquals("Solo", tab.measures[1].marker)
        val first = tab.measures[0].events
        assertEquals(listOf(0.0, 1.0, 2.0, 2.5), first.map { it.at })
        assertEquals(0.5, first[2].dur!!, 1e-9)
        // Pitches survive the trip through the fingering.
        val pitches = tab.measures.flatMap { m -> m.events.flatMap { e -> e.notes.map { n -> std.midi[std.strings - n.string] + n.fret } } }
        assertEquals(notes.map { it.midi }, pitches)
    }

    @Test fun `chordpro keeps chords and sections, drops lyrics`() {
        val text = """
            {title: Test Song}
            {artist: Someone}
            {key: G}
            {define: G base-fret 1 frets 3 2 0 0 0 3}
            {start_of_verse: Verse 1}
            [G]Some words [C]here and [D]there
            {end_of_verse}
            {soc}
            [Em]More [C]words
            {eoc}
        """.trimIndent()
        val p = ChordProImporter.read(text, "test.cho", std).song as Progression
        assertEquals("Test Song", p.title)
        assertEquals(listOf("Verse 1", "Chorus"), p.sections.map { it.name })
        assertEquals(listOf("G", "C", "D"), p.sections[0].bars.flatten())
        assertEquals(listOf(3, 2, 0, 0, 0, 3), p.chords["G"])
        assertTrue(p.toString().contains("words").not())
    }

    @Test fun `text tab reads frets, bar lines, techniques and labels`() {
        val text = """
            [Solo]
            e|-----------------|-----5-8b10-----|
            B|-----------5h8---|-8--------------|
            G|-------5/7-------|----------------|
            D|---5-7-----------|----------------|
            A|-----------------|----------------|
            E|-----------------|----------------|
        """.trimIndent()
        val tab = AsciiTabImporter.read(text, "solo.txt", std).song as Tab
        assertEquals(2, tab.measures.size)
        assertEquals("Solo", tab.measures[0].marker)
        val m1 = tab.measures[0].events.map { e -> e.notes.single().let { "${it.string}:${it.fret}" + (it.technique?.mark ?: "") } }
        assertEquals(listOf("4:5", "4:7", "3:5/7", "2:5", "2:8h"), m1)
        val bend = tab.measures[1].events.flatMap { it.notes }.first { it.fret == 8 && it.string == 1 }
        assertEquals("bend", bend.technique?.type)
        assertEquals(2.0, bend.technique?.semitones!!, 1e-9)
        assertNull(tab.measures[0].events[0].dur)
    }

    @Test fun `musicxml with tab staff reads strings, frets, rhythm and rehearsal marks`() {
        val xml = """<?xml version="1.0"?>
            <score-partwise version="4.0">
              <work><work-title>Exercise</work-title></work>
              <part-list><score-part id="P1"><part-name>Guitar</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>2</divisions><time><beats>3</beats><beat-type>4</beat-type></time>
                    <staff-details><staff-lines>6</staff-lines>
                      <staff-tuning line="1"><tuning-step>E</tuning-step><tuning-octave>2</tuning-octave></staff-tuning>
                      <staff-tuning line="2"><tuning-step>A</tuning-step><tuning-octave>2</tuning-octave></staff-tuning>
                      <staff-tuning line="3"><tuning-step>D</tuning-step><tuning-octave>3</tuning-octave></staff-tuning>
                      <staff-tuning line="4"><tuning-step>G</tuning-step><tuning-octave>3</tuning-octave></staff-tuning>
                      <staff-tuning line="5"><tuning-step>B</tuning-step><tuning-octave>3</tuning-octave></staff-tuning>
                      <staff-tuning line="6"><tuning-step>E</tuning-step><tuning-octave>4</tuning-octave></staff-tuning>
                    </staff-details></attributes>
                  <direction><direction-type><rehearsal>Intro</rehearsal></direction-type></direction>
                  <note><pitch><step>A</step><octave>3</octave></pitch><duration>2</duration><notations><technical><string>3</string><fret>2</fret></technical></notations></note>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations><technical><string>2</string><fret>1</fret></technical></notations></note>
                  <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><notations><technical><string>1</string><fret>0</fret></technical></notations></note>
                  <note><rest/><duration>3</duration></note>
                </measure>
              </part>
            </score-partwise>""".trimIndent()
        val tab = MusicXmlImporter.read(xml.toByteArray(), "ex.musicxml", null, null).song as Tab
        assertEquals("Exercise", tab.title)
        assertEquals(TimeSignature(3, 4), tab.timeSignature)
        assertEquals(std.midi, tab.tuning!!.midi)
        assertEquals("Intro", tab.measures[0].marker)
        val events = tab.measures[0].events
        assertEquals(listOf(0.0, 1.0, 1.5), events.map { it.at })
        assertEquals(2, events[1].notes.size)
        assertTrue(events[2].notes.isEmpty())
        assertNotNull(Importers.formatFor("x.mxl"))
    }
}
