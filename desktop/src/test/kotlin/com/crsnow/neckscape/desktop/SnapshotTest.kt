package com.crsnow.neckscape.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crsnow.neckscape.ui.NeckScapeScreen
import com.crsnow.neckscape.ui.NeckScapeTheme
import com.crsnow.neckscape.ui.NeckScapeViewModel
import com.crsnow.neckscape.theory.Level
import com.crsnow.neckscape.theory.Chords
import com.crsnow.neckscape.theory.PluckSynth
import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Tunings
import com.crsnow.neckscape.theory.VoicingFinder
import com.crsnow.neckscape.theory.playingOrder
import com.crsnow.neckscape.ui.Display
import com.crsnow.neckscape.ui.LabelMode
import com.crsnow.neckscape.ui.AudioOutput
import com.crsnow.neckscape.ui.LocalNotePlayer
import com.crsnow.neckscape.ui.LocalScrollbars
import com.crsnow.neckscape.ui.NotePlayer
import com.crsnow.neckscape.ui.NoteMark
import com.crsnow.neckscape.ui.UiState
import com.crsnow.neckscape.ui.ViewMode
import com.crsnow.neckscape.ui.buildDots
import com.crsnow.neckscape.ui.degreeColor
import com.crsnow.neckscape.ui.musicFor
import com.crsnow.neckscape.ui.pdfFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnapshotTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private fun snapshot(name: String, width: Int, height: Int, density: Float, setup: (NeckScapeViewModel) -> Unit = {}) {
        val vm = NeckScapeViewModel()
        val scene = ImageComposeScene(width, height, Density(density)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalScrollbars provides DesktopScrollbars) {
                NeckScapeTheme(dark = true, fontFamily = InterFamily, logo = LogoPainter) { NeckScapeScreen(vm) }
            }
        }
        scene.render()
        setup(vm)
        var t = 0L
        repeat(10) { scene.render(t); t += 100_000_000; Thread.sleep(50) }
        val png = scene.render(t).use { it.encodeToData()!!.bytes }
        File("build/snapshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(png)
        scene.close()
    }

    private fun showShape(vm: NeckScapeViewModel) {
        vm.setViewMode(ViewMode.Chord)
        runBlocking { withTimeout(10_000) { vm.voicings.first { it.isNotEmpty() } } }
        vm.stepVoicing(1)
    }

    @Test fun desktopScale() = snapshot("desktop-scale", 1280, 1100, 1f) { it.setFretRange(5, 8) }

    @Test fun desktopChord() = snapshot("desktop-chord", 1280, 1100, 1f, ::showShape)

    @Test fun phoneScale() = snapshot("phone-scale", 1080, 2400, 2.625f)

    @Test fun desktopScore() = snapshot("desktop-score", 1280, 1400, 1f) {
        it.setFretRange(0, 12)
        it.setDisplay(Display.Score)
    }

    @Test fun desktopTabChord() = snapshot("desktop-tab-chord", 1280, 1100, 1f) {
        it.selectChord(Chords.all.indexOfFirst { c -> c.symbol == "maj7" })
        showShape(it)
        it.setDisplay(Display.Tab)
    }

    @Test fun phoneArpeggioScore() = snapshot("phone-arpeggio-score", 1080, 2400, 2.625f) {
        it.setLevel(Level.Beginner)
        it.selectRoot(9)
        it.setViewMode(ViewMode.Arpeggio)
        it.setFretRange(5, 9)
        it.setDisplay(Display.Score)
    }

    /** PDFs of a few typical sheets, for checking with a PDF viewer / pdftoppm. */
    @Test fun pdfs() {
        fun pdf(name: String, state: UiState, display: Display, everything: Boolean) {
            val notes = state.intervals.map { state.root + it }
            val marks = state.intervals.zip(notes).associate { (iv, n) ->
                n.pitchClass to NoteMark(if (state.labelMode == LabelMode.Notes) n.name else iv.label, degreeColor(iv))
            }
            val dots = buildDots(state.tuning, marks, state.fretRange, state.stringRange, null, state.showOrder)
            val sequence = playingOrder(state.tuning, dots.map { it.position }).keys.toList()
            val shapes = if (state.viewMode == ViewMode.Chord) VoicingFinder.find(state.tuning, state.root, state.chord, state.fretCount)
                .filter { v -> v.frets.all { f -> f == null || f in state.fretRange } } else emptyList()
            val bytes = pdfFor(musicFor(state, dots, sequence, shapes), display, everything)
            File("build/snapshots/$name.pdf").apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        val chord = Chords.all.indexOfFirst { it.symbol == "maj7" }
        pdf("pdf-c-major-everything", UiState(fretTo = 12), Display.Score, everything = true)
        pdf("pdf-cmaj7-everything", UiState(viewMode = ViewMode.Chord, chordIndex = chord, fretTo = 12), Display.Fretboard, everything = true)
        pdf("pdf-a-minor-pent-tab", UiState(root = Note.parse("A"), familyIndex = 5, scaleIndex = 4, fretFrom = 5, fretTo = 8), Display.Tab, everything = false)
        val dadgad = Tunings.all.indexOfFirst { it.name.contains("DADGAD") }
        pdf("pdf-fsharp-lydian-dadgad-score", UiState(root = Note.parse("F#"), scaleIndex = 3, tuningIndex = dadgad, fretTo = 7), Display.Score, everything = false)
        val bass = Tunings.all.indexOfFirst { it.name.contains("Bass") }
        pdf("pdf-bass-eb-major", UiState(root = Note.parse("Eb"), tuningIndex = bass, fretTo = 12), Display.Score, everything = true)
    }

    @Test fun desktopCagedAll() = snapshot("desktop-caged-all", 1280, 1000, 1f) { it.setLevel(Level.Intermediate) }

    @Test fun desktopCagedAShape() = snapshot("desktop-caged-a-shape", 1280, 1000, 1f) {
        it.setLevel(Level.Intermediate)
        it.setCaged(1)
    }

    @Test fun desktopCagedChord() = snapshot("desktop-caged-chord", 1280, 1100, 1f) {
        it.setLevel(Level.Intermediate)
        it.setViewMode(ViewMode.Chord)
        it.selectRoot(7)
        it.setCaged(3)
    }

    /** A small window: the fretboard and the page both need scroll bars. */
    @Test fun desktopNote() = snapshot("desktop-note", 1280, 1000, 1f) { it.setViewMode(ViewMode.Note) }
    @Test fun phoneNote() = snapshot("phone-note", 1080, 2376, 2.75f) { it.setViewMode(ViewMode.Note); it.selectRoot(1) }
    @Test fun phoneNoteTab() = snapshot("phone-note-tab", 1080, 2376, 2.75f) { it.setViewMode(ViewMode.Note); it.setDisplay(Display.Tab) }

    @Test fun phoneCaged() = snapshot("phone-caged", 1080, 2376, 2.75f) { it.setLevel(Level.Intermediate); it.setViewMode(ViewMode.Chord) }

    @Test fun keyChordShapes() = snapshot("key-chord-shapes", 1280, 1100, 1f) { it.setLevel(Level.Advanced); it.pickKeyChord(1) }
    @Test fun keyChordDiminishedShape() = snapshot("key-chord-dim", 1280, 1100, 1f) {
        it.setLevel(Level.Advanced); it.pickKeyChord(6); it.setCaged(1)
    }

    @Test fun keyChordTones() = snapshot("key-chord-tones", 1280, 1100, 1f) { it.setLevel(Level.Intermediate); it.pickKeyChord(1) }

    @Test fun tappingACagedBandStrumsItsShape() {
        val vm = NeckScapeViewModel()
        val played = ArrayList<Int>()
        val player = NotePlayer(object : AudioOutput {
            override fun play(pcm: ShortArray, sampleRate: Int) { synchronized(played) { played += pcm.size } }
            override fun release() {}
        })
        val scene = ImageComposeScene(1280, 1000, Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalScrollbars provides DesktopScrollbars, LocalNotePlayer provides player) {
                NeckScapeTheme(dark = true, fontFamily = InterFamily, logo = LogoPainter) { NeckScapeScreen(vm) }
            }
        }
        scene.render()
        vm.setLevel(Level.Intermediate)
        vm.setViewMode(ViewMode.Chord)
        var t = 0L
        repeat(5) { scene.render(t); t += 100_000_000; Thread.sleep(50) }
        fun tap(x: Float, y: Float): Set<String> {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            repeat(5) { scene.render(t); t += 100_000_000; Thread.sleep(50) }
            return vm.sounding.value.map { "${it.string}:${it.fret}" }.toSet()
        }
        // The C band (upper lane, from the nut) and the A band below it, over frets 2–5.
        assertEquals(setOf("1:3", "2:2", "3:0", "4:1", "5:0"), tap(450f, 395f))
        assertEquals(setOf("1:3", "2:5", "3:5", "4:5", "5:3"), tap(700f, 408f))
        Thread.sleep(300)
        assertTrue("the strum reached the speaker", synchronized(played) { played.any { it > 0 } })
        scene.close()
    }

    @Test fun desktopNarrow() = snapshot("desktop-narrow", 820, 1000, 1f)

    /** A short phrase in every sound, as WAVs in build/sounds, for listening (the video helpers write the WAV). */
    @Test fun soundSamples() {
        val dir = File("build/sounds").apply { mkdirs() }
        val scale = listOf(45, 47, 48, 50, 52, 53, 55, 57).mapIndexed { i, m -> com.crsnow.neckscape.theory.NoteEvent(m, i * 0.28) }
        val chord = PluckSynth.strum(listOf(45, 52, 57, 60, 64)).map { it.copy(at = it.at + 2.6) }
        for (instrument in com.crsnow.neckscape.theory.Instrument.entries) {
            val pcm = PluckSynth.render(scale + chord, ring = 1.8, instrument = instrument)
            writeWav(File(dir, "sound-${instrument.name.lowercase()}.wav"), pcm, PluckSynth.SAMPLE_RATE)
        }
        // Beginner's C major run from the low E string, one note every 0.4 s: the piano's low notes must be heard.
        val run = listOf(40, 41, 43, 45, 47, 48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65, 67).mapIndexed { i, m -> com.crsnow.neckscape.theory.NoteEvent(m, i * 0.4) }
        for (instrument in com.crsnow.neckscape.theory.Instrument.entries) {
            writeWav(File(dir, "run-${instrument.name.lowercase()}.wav"), PluckSynth.render(run, ring = 1.6, instrument = instrument), PluckSynth.SAMPLE_RATE)
        }
    }

    /** OnePlus 8 Pro on its side (905 × 411 dp): two panes, slim bars, the neck in view. */
    @Test fun phoneLandscape() = snapshot("phone-landscape", 3168, 1440, 3.5f)

    /** A smaller phone on its side (≈ 700 × 390 dp): still two panes, narrower sidebar. */
    @Test fun smallPhoneLandscape() = snapshot("phone-landscape-small", 1920, 1080, 2.75f)

    @Test fun chordScoreBeginner() = snapshot("chord-score-beginner", 1280, 1000, 1f) {
        it.setViewMode(ViewMode.Chord)
        it.setDisplay(Display.Score)
    }

    @Test fun chordTabCaged() = snapshot("chord-tab-caged", 1280, 1000, 1f) {
        it.setLevel(Level.Intermediate)
        it.setViewMode(ViewMode.Chord)
        it.setCaged(2)
        it.setDisplay(Display.Tab)
    }

    @Test fun chordScoreSeventh() = snapshot("chord-score-seventh", 1280, 1000, 1f) {
        it.setLevel(Level.Intermediate)
        it.setViewMode(ViewMode.Chord)
        it.selectChord(Chords.all.indexOfFirst { c -> c.symbol == "maj7" })
        it.setDisplay(Display.Score)
    }

    private fun loadRisingSun(vm: NeckScapeViewModel) {
        val payload = com.crsnow.neckscape.song.Json.parse(File("../schema/fixtures/good-house-of-the-rising-sun.json").readText())
        com.crsnow.neckscape.ui.AiTools(vm, "test").call("load_progression", mapOf("progression" to payload))
    }
    @Test fun songScore() = snapshot("song-score", 1280, 1000, 1f) { loadRisingSun(it); it.setDisplay(Display.Score) }
    @Test fun songTab() = snapshot("song-tab", 1280, 1000, 1f) { loadRisingSun(it); it.setDisplay(Display.Tab) }
    @Test fun chordScorePhone() = snapshot("chord-score-phone", 1080, 2376, 2.75f) { it.setViewMode(ViewMode.Chord); it.setDisplay(Display.Score) }
    @Test fun chordScoreBeginnerD() = snapshot("chord-score-beginner-d", 1280, 1000, 1f) { it.setViewMode(ViewMode.Chord); it.selectRoot(2); it.setDisplay(Display.Score) }
    @Test fun chordTabNearest() = snapshot("chord-tab-nearest", 1280, 1000, 1f) {
        it.setLevel(Level.Intermediate); it.setViewMode(ViewMode.Chord); it.setFretRange(0, 4)
        it.selectRoot(3); it.selectChord(Chords.all.indexOfFirst { c -> c.symbol == "m9" }); it.setDisplay(Display.Tab)
    }
}
