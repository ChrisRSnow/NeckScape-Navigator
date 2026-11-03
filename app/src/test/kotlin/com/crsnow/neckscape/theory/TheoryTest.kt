package com.crsnow.neckscape.theory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheoryTest {
    private val c = Note.parse("C")

    private fun scale(family: String, mode: String) =
        Scales.families.first { it.name == family }.scales.first { it.name == mode }

    private fun chord(symbol: String) = Chords.all.first { it.symbol == symbol }

    private fun names(notes: List<Note>) = notes.joinToString(" ") { it.name }

    @Test fun `intervals spell on the right letter`() {
        assertEquals("E♭", (c + Interval.parse("b3")).name)
        assertEquals("F", (Note.parse("Db") + Interval.parse("3")).name)
        assertEquals("B♭♭", (c + Interval.parse("bb7")).name)
        assertEquals("F♯", (c + Interval.parse("#11")).name)
    }

    @Test fun `major and modes`() {
        assertEquals("F♯ G♯ A♯ B C♯ D♯ E♯", names(scale("Major (diatonic)", "Ionian (Major)").notes(Note.parse("F#"))))
        assertEquals("C D E♭ F G A B♭", names(scale("Major (diatonic)", "Dorian").notes(c)))
        assertEquals("R ♭2 ♭3 ♭4 ♭5 ♭6 ♭7", scale("Melodic Minor", "Altered (Super Locrian)").intervals.joinToString(" ") { it.label })
    }

    @Test fun `every curated seven-note scale uses each letter once`() {
        // tonal spells a few on purpose with a repeated letter (augmented heptatonic: 5 and ♯5).
        Scales.families.dropLast(1).flatMap { it.scales }.filter { it.isHeptatonic }.forEach { s ->
            assertEquals(s.name, 7, s.notes(c).map { it.letter }.toSet().size)
        }
    }

    @Test fun `non-heptatonic spellings follow convention`() {
        assertEquals("C E♭ F G♭ G B♭", names(scale("Blues", "Minor Blues").notes(c)))
        assertEquals("C D E F♯ G♯ B♭", names(scale("Symmetric", "Whole Tone").notes(c)))
        assertEquals("A C D E G", names(scale("Pentatonic", "Minor Pentatonic").notes(Note.parse("A"))))
    }

    @Test fun `root spelling picks the key with fewer accidentals`() {
        val major = scale("Major (diatonic)", "Ionian (Major)").intervals
        val minor = scale("Major (diatonic)", "Aeolian (Natural Minor)").intervals
        assertEquals("D♭", Note.bestSpelling(1, major).name)
        assertEquals("C♯", Note.bestSpelling(1, minor).name)
        assertEquals("G♯", Note.bestSpelling(8, minor).name)
        assertEquals("F♯", Note.bestSpelling(6, major).name)
    }

    @Test fun `chord spelling and identification`() {
        assertEquals("C E G B", names(chord("maj7").notes(c)))
        assertEquals("C E♭ G♭ B♭♭", names(chord("dim7").notes(c)))
        assertEquals("m7♭5", Chords.identify(setOf(0, 3, 6, 10))?.symbol)
        val symbols = Chords.all.map { it.symbol }
        assertEquals("chord symbols must be unique", symbols.size, symbols.toSet().size)
    }

    @Test fun `tonal dictionary fills the gaps without duplicates`() {
        assertTrue(Chords.all.size > 100)
        assertEquals("C E G B♭ D♯ F♯", names(chord("7♯9♯11").notes(c)))
        assertEquals(Chords.all.size, Chords.all.map { it.pitchSet }.toSet().size)
        val more = Scales.families.last()
        assertTrue(more.scales.any { it.name == "Flamenco" })
        assertTrue(more.scales.none { it.name == "Dorian" })
    }

    @Test fun `diatonic chords of C major`() {
        val ionian = scale("Major (diatonic)", "Ionian (Major)")
        val triads = diatonicChords(ionian, c, sevenths = false)
        assertEquals("C Dm Em F G Am Bdim", triads.joinToString(" ") { it.type!!.displayName(it.root) })
        assertEquals("I ii iii IV V vi vii", triads.joinToString(" ") { it.numeral })
        val sevenths = diatonicChords(ionian, c, sevenths = true)
        assertEquals("Cmaj7 Dm7 Em7 Fmaj7 G7 Am7 Bm7♭5", sevenths.joinToString(" ") { it.type!!.displayName(it.root) })
    }

    @Test fun `diatonic numerals carry accidentals`() {
        val aeolian = scale("Major (diatonic)", "Aeolian (Natural Minor)")
        assertEquals("i ii ♭III iv v ♭VI ♭VII", diatonicChords(aeolian, c, false).joinToString(" ") { it.numeral })
    }

    private fun scalePositions(tuning: Tuning, s: Scale, root: Note, frets: IntRange): List<Position> {
        val pcs = s.notes(root).map { it.pitchClass }.toSet()
        return (0 until tuning.strings).flatMap { str -> frets.map { Position(str, it) } }
            .filter { tuning.pitchClassAt(it.string, it.fret) in pcs }
    }

    @Test fun `playing order walks C major up from the nut`() {
        val std = Tunings.all.first()
        val order = playingOrder(std, scalePositions(std, scale("Major (diatonic)", "Ionian (Major)"), c, 0..3))
        val expected = listOf(0 to 0, 0 to 1, 0 to 3, 1 to 0, 1 to 2, 1 to 3, 2 to 0, 2 to 2, 2 to 3,
            3 to 0, 3 to 2, 4 to 0, 4 to 1, 4 to 3, 5 to 0, 5 to 1, 5 to 3)
        assertEquals(expected.mapIndexed { i, (s, f) -> Position(s, f) to i + 1 }.toMap(), order)
    }

    @Test fun `playing order gives the minor pentatonic box`() {
        val std = Tunings.all.first()
        val box = playingOrder(std, scalePositions(std, scale("Pentatonic", "Minor Pentatonic"), Note.parse("A"), 5..8))
        assertEquals("5 8 5 7 5 7 5 7 5 8 5 8", box.keys.joinToString(" ") { "${it.fret}" })
        assertEquals((0..5).flatMap { listOf(it, it) }, box.keys.map { it.string })
    }

    @Test fun `playing order skips duplicate pitches across the whole neck`() {
        val std = Tunings.all.first()
        val positions = scalePositions(std, scale("Major (diatonic)", "Ionian (Major)"), c, 0..22)
        val order = playingOrder(std, positions)
        val pitches = order.keys.map { std.midi[it.string] + it.fret }
        assertEquals(pitches.sorted(), pitches)
        assertEquals(pitches.size, pitches.toSet().size)
        assertEquals(positions.map { std.midi[it.string] + it.fret }.toSet().size, order.size)
    }

    @Test fun `synth renders a strum at the right length and level`() {
        val pcm = PluckSynth.render(PluckSynth.strum(listOf(40, 45, 50)), ring = 1.0)
        assertEquals(((2 * 0.035 + 1.0) * PluckSynth.SAMPLE_RATE).toInt(), pcm.size)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak in 25_000..Short.MAX_VALUE.toInt())
        assertTrue("ends near silent (no click)", kotlin.math.abs(pcm.last().toInt()) < 50)
        val run = PluckSynth.upAndDown(listOf(1, 2, 3), step = 0.5)
        assertEquals(listOf(1, 2, 3, 2, 1), run.map { it.midi })
        assertEquals(2.0, run.last().at, 1e-9) // 120 BPM: a beat every half second
    }

    @Test fun `scale to chord picks the home chord`() {
        assertEquals("", tonicChord(scale("Major (diatonic)", "Ionian (Major)"), false)?.symbol)
        assertEquals("m", tonicChord(scale("Major (diatonic)", "Dorian"), false)?.symbol)
        assertEquals("dim", tonicChord(scale("Major (diatonic)", "Locrian"), false)?.symbol)
        assertEquals("m7♭5", tonicChord(scale("Major (diatonic)", "Locrian"), true)?.symbol)
        assertEquals("m", tonicChord(scale("Pentatonic", "Minor Pentatonic"), false)?.symbol)
        assertEquals("aug", tonicChord(scale("Symmetric", "Whole Tone"), false)?.symbol)
    }

    @Test fun `chord to scale picks the everyday scale`() {
        fun pick(symbol: String, family: Int = 0) =
            scaleFor(chord(symbol), family)?.let { (f, s) -> Scales.families[f].scales[s].name }
        assertEquals("Ionian (Major)", pick(""))
        assertEquals("Aeolian (Natural Minor)", pick("m7"))
        assertEquals("Mixolydian", pick("7"))
        assertEquals("Lydian", pick("maj7♯11"))
        assertEquals("Locrian", pick("m7♭5"))
        assertEquals("Altered (Super Locrian)", pick("7alt"))
        // Prefers the family you were in: minor chord from the pentatonics stays pentatonic.
        val pent = Scales.families.indexOfFirst { it.name == "Pentatonic" }
        assertEquals("Minor Pentatonic", pick("m", pent))
    }

    @Test fun `retuning one string`() {
        val dropD = Tunings.all.first().retune(0, -2)
        assertEquals(listOf(38, 45, 50, 55, 59, 64), dropD.midi)
        assertEquals("D", dropD.stringNames[0])
        assertEquals("D2", Tuning.midiName(38))
    }

    @Test fun `tuning parses to midi`() {
        val std = Tunings.all.first()
        assertEquals(listOf(40, 45, 50, 55, 59, 64), std.midi)
        assertEquals(listOf("E", "A", "D", "G", "B", "E"), std.stringNames)
    }

    @Test fun `voicing finder finds the open chords`() {
        val std = Tunings.all.first()
        fun tabs(root: String, symbol: String) =
            VoicingFinder.find(std, Note.parse(root), chord(symbol), 22).map { it.tab }

        assertTrue("x32010" in tabs("C", ""))
        assertTrue("022100" in tabs("E", ""))
        assertTrue("x02210" in tabs("A", "m"))
        assertTrue("x32000" in tabs("C", "maj7"))
        assertTrue("320003" in tabs("G", "") || "320033" in tabs("G", ""))
        assertTrue("x7678x" in tabs("E", "7♯9")) // the Hendrix chord
    }

    @Test fun `every beginner chord has a shape in the first four frets`() {
        // Open D (xx0232) used to lose out at its position to x54232, which then fell outside frets 0–4.
        val std = Tunings.all.first()
        for (type in Chords.all.filter { Level.Beginner.allows(it) }) for (pc in 0 until 12) {
            val root = Note.spellings(pc).first()
            val fitting = VoicingFinder.bestPerPosition(
                VoicingFinder.findAll(std, root, type, 22).filter { v -> v.frets.all { it == null || it in 0..4 } },
            )
            assertTrue("${root.name}${type.symbol}", fitting.isNotEmpty())
        }
        assertTrue("xx0232" in VoicingFinder.findAll(std, Note.parse("D"), chord(""), 22).map { it.tab })
    }

    @Test fun `every chord has shapes in standard tuning`() {
        val std = Tunings.all.first()
        Chords.all.forEach { type ->
            val shapes = VoicingFinder.find(std, c, type, 22)
            assertNotNull(type.symbol, shapes.firstOrNull())
        }
    }

    @Test fun `levels offer progressively more`() {
        val beginner = Level.Beginner.chordIndices().map { Chords.all[it].symbol }
        assertEquals(listOf("", "m"), beginner)
        val beginnerScales = Level.Beginner.scaleIndices().map { (f, s) -> Scales.families[f].scales[s].name }
        assertEquals(setOf("Ionian (Major)", "Aeolian (Natural Minor)", "Major Pentatonic", "Minor Pentatonic"), beginnerScales.toSet())

        assertTrue(Level.Intermediate.allows(chord("maj7")))
        assertTrue(Level.Intermediate.allows(chord("13")))
        assertTrue(!Level.Intermediate.allows(chord("7alt")))
        assertTrue(Level.Intermediate.allowsScale(0, 1)) // Dorian
        assertTrue(!Level.Intermediate.allowsScale(1, 6)) // Altered
        assertEquals(Chords.all.size, Level.Advanced.chordIndices().size)
    }

    @Test fun `levels swap in the nearest thing they offer`() {
        assertEquals("m", Level.Beginner.nearestChord(chord("m7")).symbol)
        assertEquals("", Level.Beginner.nearestChord(chord("7")).symbol)
        assertEquals("m", Level.Beginner.nearestChord(chord("dim")).symbol)
        // Dorian → natural minor (closest of the beginner scales).
        val (f, s) = Level.Beginner.nearestScale(0, 1)
        assertEquals("Aeolian (Natural Minor)", Scales.families[f].scales[s].name)
        val minorBlues = Scales.families.indexOfFirst { it.name == "Blues" }
        val (f2, s2) = Level.Beginner.nearestScale(minorBlues, 0)
        assertEquals("Minor Pentatonic", Scales.families[f2].scales[s2].name)
    }

    @Test fun `scale for a chord respects the level`() {
        val (f, s) = scaleFor(chord("m"), preferFamily = 1, allowed = Level.Beginner::allowsScale)!!
        assertEquals("Aeolian (Natural Minor)", Scales.families[f].scales[s].name)
    }

    @Test fun `CAGED positions tile the neck in order`() {
        fun row(root: String, minor: Boolean) = cagedPositions(Note.parse(root).pitchClass, minor, 22)
            .joinToString(" ") { "${it.shape}${it.offset}" }
        assertEquals("C0 A3 G5 E8 D10 C12 A15 G17 E20", row("C", false))
        assertEquals("G0 E3 D5 C7 A10 G12 E15 D17 C19", row("G", false))
        // The moved shapes are the real chords: C as an A shape is x35553, as an E shape 8 10 10 9 8 8.
        val c = cagedPositions(0, false, 22)
        assertEquals("x35553", c.first { it.shape == CagedShape.A }.voicing.tab)
        assertEquals("8 10 10 9 8 8", c.first { it.shape == CagedShape.E }.voicing.tab)
        assertEquals("x02210", cagedPositions(9, true, 22).first { it.shape == CagedShape.A }.voicing.tab)
        assertEquals(2..5, c.first { it.shape == CagedShape.A }.frets)
        assertTrue(Tunings.all.first().isStandardShape())
        assertTrue(!Tunings.all.first { it.name == "DADGAD" }.isStandardShape())
    }

    @Test fun `every sound renders a clean, audible clip at the right pitch`() {
        val a = listOf(NoteEvent(57, 0.0)) // A3, 220 Hz
        for (instrument in Instrument.entries) {
            val pcm = PluckSynth.render(a, ring = 1.0, instrument = instrument)
            assertTrue(instrument.name, pcm.size >= PluckSynth.SAMPLE_RATE)
            val rms = kotlin.math.sqrt(pcm.take(PluckSynth.SAMPLE_RATE / 2).sumOf { it.toDouble() * it } / (PluckSynth.SAMPLE_RATE / 2))
            assertTrue("${instrument.name} is too quiet: $rms", rms > 1_000)
            // Pitch from autocorrelation over 0.1–0.3 s: the best lag should be one period of 220 Hz.
            val x = pcm.copyOfRange(4_410, 13_230).map { it.toDouble() }
            val best = (120..260).maxBy { lag -> (0 until x.size - lag).sumOf { x[it] * x[it + lag] } }
            assertEquals("${instrument.name} pitch", 44_100.0 / 220, best.toDouble(), 2.5)
        }
        // Guitar notes ease in rather than starting on a full-scale click.
        for (instrument in listOf(Instrument.Acoustic, Instrument.Classical, Instrument.Electric)) {
            val pcm = PluckSynth.render(a, 1.0, instrument)
            val first = pcm.take(10).maxOf { kotlin.math.abs(it.toInt()) }
            val body = pcm.take(4_410).maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("${instrument.name} attack", first < body / 4)
        }
        // Classical chords are finger-picked: thumb on the bass, fingers together just after.
        val chord = PluckSynth.strum(listOf(45, 52, 57, 60))
        val classical = PluckSynth.render(chord, 1.0, Instrument.Classical)
        assertTrue(classical.size > PluckSynth.SAMPLE_RATE)
    }
}
