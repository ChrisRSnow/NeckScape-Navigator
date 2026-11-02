package com.crsnow.neckscape.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crsnow.neckscape.bridge.BridgeServer
import com.crsnow.neckscape.bridge.SettingsStore
import com.crsnow.neckscape.song.Json
import com.crsnow.neckscape.song.schedule
import com.crsnow.neckscape.theory.Chords
import com.crsnow.neckscape.theory.Level
import com.crsnow.neckscape.theory.NoteEvent
import com.crsnow.neckscape.theory.PluckSynth
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Scales
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.VoicingFinder
import com.crsnow.neckscape.theory.diatonicChords
import com.crsnow.neckscape.theory.playingOrder
import com.crsnow.neckscape.theory.thereAndBack
import com.crsnow.neckscape.ui.AiTools
import com.crsnow.neckscape.ui.AudioOutput
import com.crsnow.neckscape.ui.Display
import com.crsnow.neckscape.ui.NeckScapeScreen
import com.crsnow.neckscape.ui.NeckScapeViewModel
import com.crsnow.neckscape.ui.LabelMode
import com.crsnow.neckscape.ui.LocalNotePlayer
import com.crsnow.neckscape.ui.NoteMark
import com.crsnow.neckscape.ui.NotePlayer
import com.crsnow.neckscape.ui.ViewMode
import com.crsnow.neckscape.ui.midiFor
import com.crsnow.neckscape.ui.buildDots
import com.crsnow.neckscape.ui.degreeColor
import com.crsnow.neckscape.ui.musicFor
import com.crsnow.neckscape.ui.pdfFor
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scripts the real screen through a feature tour and writes, to build/video:
 * one PNG per distinct frame, an ffconcat list with how long each is held, and
 * a WAV soundtrack from the app's own synth in time with it. tools/make_video.sh
 * runs this and muxes the result with ffmpeg. Skipped unless -Pneckscape.video=1.
 */
class FeatureVideo {
    private data class Caption(
        val title: String,
        val body: String = "",
        val step: Int = 0,
        val listening: Boolean = false,
        val fullScreen: Boolean = false,
    )

    private val width = 1920
    private val height = 1080
    private val appWidth = 1440
    private val density = 1.08f
    private val steps = 20

    @Test fun render() {
        assumeTrue(System.getProperty("neckscape.video") == "1")
        val out = File(System.getProperty("neckscape.videoDir") ?: "build/video").apply { deleteRecursively(); mkdirs() }

        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        // Enables the Play button; the soundtrack is rendered separately below.
        val silent = NotePlayer(object : AudioOutput {
            override fun play(pcm: ShortArray, sampleRate: Int) {}
            override fun release() {}
        })
        val vm = NeckScapeViewModel()
        val caption = mutableStateOf(Caption(""))
        val pdfPage = mutableStateOf<ImageBitmap?>(null)
        val terminal = mutableStateOf<List<String>>(emptyList())
        // The real bridge, as Claude Code reaches it through the MCP server.
        val bridge = BridgeServer(
            object : SettingsStore {
                val m = HashMap<String, String>()
                override fun get(key: String) = m[key]
                override fun put(key: String, value: String) { m[key] = value }
            },
            lanCapable = false, basePort = 47750, handler = AiTools(vm, "linux")::call,
        ).also { it.start() }

        val scene = ImageComposeScene(width, height, Density(density)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNotePlayer provides silent) {
                MaterialTheme(lightColorScheme()) {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width((appWidth / density).dp).fillMaxHeight()) {
                                NeckScapeScreen(vm)
                                pdfPage.value?.let { PdfPreview(it) }
                                if (terminal.value.isNotEmpty()) Terminal(terminal.value, Modifier.align(Alignment.BottomEnd))
                            }
                            CaptionPanel(caption.value, Modifier.fillMaxSize())
                        }
                        if (caption.value.fullScreen) TitleCard(caption.value)
                    }
                }
            }
        }

        Director(vm, scene, caption, pdfPage, terminal, bridge, out).tour()
        bridge.stop()
        scene.close()
    }

    private inner class Director(
        val vm: NeckScapeViewModel,
        val scene: ImageComposeScene,
        val caption: MutableState<Caption>,
        val pdfPage: MutableState<ImageBitmap?>,
        val terminal: MutableState<List<String>>,
        val bridge: BridgeServer,
        val out: File,
    ) {
        private var time = 0.0
        private var clock = 0L
        private var frame = 0
        private val audio = mutableListOf<NoteEvent>()
        private val voiceDir = File(System.getProperty("neckscape.narrationDir") ?: "build/narration")
        private val voices = mutableListOf<Pair<Double, ShortArray>>() // start time, 44.1 kHz PCM
        private var voiceEnds = 0.0
        private val concat = StringBuilder("ffconcat version 1.0\n")
        private val state get() = vm.state.value

        /** Lets state changes, animations and async work (voicings) land. */
        private fun settle() = repeat(8) {
            scene.render(clock)
            clock += 60_000_000
            Thread.sleep(15)
        }

        /** Shows the current screen for [seconds]. */
        fun hold(seconds: Double) {
            settle()
            val name = "f%04d.png".format(frame++)
            File(out, name).writeBytes(scene.render(clock).use { it.encodeToData()!!.bytes })
            concat.append("file $name\nduration ${"%.3f".format(seconds)}\n")
            time += seconds
        }

        /** Holds the screen until any narration still playing has finished. */
        fun untilQuiet(gap: Double = 0.35) {
            val left = voiceEnds + gap - time
            if (left > 0) hold(left)
        }

        /**
         * Waits for the previous line to finish, then starts narration [id]
         * (tools/narration.txt, voiced by make_video.sh). Silent if there's no voice-over.
         */
        fun narrate(id: String) {
            if (!voiceDir.isDirectory) return
            untilQuiet()
            val pcm = readWav(File(voiceDir, "$id.wav"))
            voices += time to pcm
            voiceEnds = time + pcm.size.toDouble() / PluckSynth.SAMPLE_RATE
        }

        fun say(c: Caption) {
            caption.value = c
        }

        /** Queues sound starting now. */
        private fun sound(events: List<NoteEvent>) {
            audio += events.map { it.copy(at = it.at + time) }
        }

        /** Waits for the background voicing search to finish for the current chord. */
        private fun awaitVoicings() {
            val s = state
            val expected = VoicingFinder.find(s.tuning, s.root, s.chord, s.fretCount)
            repeat(200) {
                if (vm.voicings.value == expected) return
                scene.render(clock)
                Thread.sleep(25)
            }
            error("voicings never arrived for ${s.title}")
        }

        private fun Voicing.positions() = frets.withIndex().mapNotNull { (s, f) -> f?.let { Position(s, it) } }

        /** Steps to the shape with [tab] (or just the next one) and strums it, ringed. */
        private fun strumShape(tab: String? = null, seconds: Double) {
            val shapes = vm.voicings.value
            val target = tab?.let { t -> shapes.indexOfFirst { it.tab == t }.also { check(it >= 0) { "no shape $t" } } }
                ?: (state.voicingIndex + 1)
            vm.stepVoicing(target - state.voicingIndex)
            settle() // the screen's own strum effect fires here; take over from it
            val shape = shapes[target]
            vm.setSounding(shape.positions().toSet())
            sound(PluckSynth.strum(shape.positions().map { state.tuning.midi[it.string] + it.fret }))
            hold(1.8)
            vm.setSounding(emptySet())
            hold(seconds - 1.8)
        }

        /**
         * Runs the visible scale in its numbered order at the current tempo, ringing
         * each note: up and back down, or just the first [limit] notes going up.
         */
        private fun playScale(limit: Int? = null) {
            val s = state
            val pcs = s.intervals.map { (s.root + it).pitchClass }.toSet()
            val visible = s.stringRange.flatMap { str -> s.fretRange.map { Position(str, it) } }
                .filter { s.tuning.pitchClassAt(it.string, it.fret) in pcs }
            val order = playingOrder(s.tuning, visible).keys.toList()
            val path = if (limit != null) order.take(limit) else order.thereAndBack()
            val step = 60.0 / s.tempo
            sound(path.mapIndexed { i, p -> NoteEvent(s.tuning.midi[p.string] + p.fret, i * step) })
            for (p in path) {
                vm.setSounding(setOf(p))
                hold(step)
            }
            vm.setSounding(emptySet())
        }

        /** A tool call over HTTP, exactly as the MCP server makes it. */
        private fun bridgeCall(tool: String, args: Map<String, Any?>): Map<*, *> {
            val st = bridge.status.value
            val c = java.net.URI("http://127.0.0.1:${st.port}/v1/tools/$tool").toURL().openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer ${st.token}")
            c.outputStream.use { it.write(Json.write(args).toByteArray()) }
            return Json.parse(c.inputStream.readBytes().decodeToString()) as Map<*, *>
        }

        /** Types a line into the terminal panel. */
        private fun type(line: String, seconds: Double = 0.6) {
            terminal.value = (terminal.value + line).takeLast(9)
            hold(seconds)
        }

        /** Shows a tool call and its answer in the terminal, as an AI session would see it. */
        private fun tool(shown: String, name: String, args: Map<String, Any?>): Map<*, *> {
            type("  ⏺ neckscape · $shown", 0.5)
            val r = bridgeCall(name, args)
            if (r["ok"] == true) {
                (r["warnings"] as List<*>).forEach { w -> (w as Map<*, *>).let { type("    ⚠ ${it["code"]} ${it["path"]}: ${it["detail"]}", 0.4) } }
                type("    ✓ ${r["rendered_summary"]}", 0.6)
            } else {
                (r["error"] as Map<*, *>).let { type("    ✗ ${it["code"]}: ${(it["detail"] as String).substringBefore(". ")}", 0.6) }
            }
            return r
        }

        /** Plays the loaded song's first [bars] bars with ringing, like the app's playback. */
        private fun playSong(bars: Int, section: String? = null) {
            val s = state
            val plan = schedule(s.song!!, s.tuning, s.fretCount, section, 100.0)
            val first = plan.steps.firstOrNull()?.bar ?: return
            val steps = plan.steps.filter { it.bar < first + bars }
            val end = plan.steps.firstOrNull { it.bar >= first + bars }?.at ?: plan.seconds
            val start = steps.first().at
            sound(plan.notes.filter { it.at >= start - 1e-6 && it.at < end - 1e-6 }.map { it.copy(at = it.at - start) })
            steps.forEachIndexed { i, step ->
                vm.selectBar(step.bar)
                vm.setSounding(step.positions)
                hold((steps.getOrNull(i + 1)?.at ?: end) - step.at)
            }
            vm.setSounding(emptySet())
        }

        /** Page 1 of the PDF the app would save for the current screen, rasterised by pdftoppm. */
        private fun pdfOfScreen(everything: Boolean): ImageBitmap {
            val s = state
            val notes = s.intervals.map { s.root + it }
            val marks = s.intervals.zip(notes).associate { (iv, n) -> n.pitchClass to NoteMark(n.name, degreeColor(iv)) }
            val dots = buildDots(s.tuning, marks, s.fretRange, s.stringRange, null, s.showOrder)
            val sequence = playingOrder(s.tuning, dots.map { it.position }).keys.toList()
            val pdf = File(out, "handout.pdf")
            pdf.writeBytes(pdfFor(musicFor(s, dots, sequence, vm.voicings.value), s.display, everything))
            val png = File(out, "handout")
            val proc = ProcessBuilder("pdftoppm", "-r", "125", "-png", "-singlefile", pdf.path, png.path).inheritIO().start()
            check(proc.waitFor() == 0) { "pdftoppm failed" }
            return org.jetbrains.skia.Image.makeFromEncoded(File(out, "handout.png").readBytes()).toComposeImageBitmap()
        }

        fun tour() {
            val family = { name: String -> Scales.families.indexOfFirst { it.name == name } }
            val chord = { symbol: String -> Chords.all.indexOfFirst { it.symbol == symbol } }

            // Each scene: narrate (waits for the previous line), change the screen, caption, hold.
            narrate("intro")
            say(Caption("NeckScape-Navigator", "Scales, modes and chords on the guitar fretboard\n\nA short tour for teachers", fullScreen = true))
            hold(2.5)

            narrate("level_beginner")
            vm.setLevel(Level.Beginner)
            say(Caption(
                "Pick a level",
                "Beginner: major and minor scales, chords and arpeggios — nothing else to get lost in.",
                step = 1,
            ))
            hold(2.0)
            narrate("level_more")
            vm.setLevel(Level.Intermediate)
            say(caption.value.copy(body = "Beginner: major and minor scales, chords and arpeggios.\n\nIntermediate adds extended chords and the modes …"))
            hold(2.2)
            vm.setLevel(Level.Advanced)
            say(caption.value.copy(body = "Beginner: major and minor scales, chords and arpeggios.\n\nIntermediate adds extended chords and the modes …\n\n… and Advanced opens up everything else."))
            hold(2.0)

            narrate("whole")
            say(Caption(
                "The whole scale, the whole neck",
                "C major (Ionian) on every string and fret.\n\nColours follow the scale degree — root red, 3rd orange, 5th blue — so a shape looks the same in every key.",
                step = 2,
            ))
            hold(2.0)

            narrate("intervals")
            vm.setLabelMode(LabelMode.Intervals)
            say(Caption(
                "Note names or intervals",
                "One tap swaps note names for intervals — R, 2, 3, 4 …\n\nStudents see what each note does in the scale, not just what it's called.",
                step = 3,
            ))
            hold(2.0)

            narrate("dorian")
            vm.selectScale(1)
            say(Caption(
                "Every mode, from any root",
                "C Dorian: same root, new colours — the ♭3 and ♭7 stand out straight away.\n\nModes of major, melodic minor, harmonic minor and more — over 100 scales in all.",
                step = 4,
            ))
            hold(2.0)
            narrate("lydian")
            vm.selectScale(3)
            say(caption.value.copy(body = "C Lydian: the ♯4 is the one note that changes.\n\nModes of major, melodic minor, harmonic minor and more — over 100 scales in all."))
            hold(2.0)

            narrate("position")
            vm.selectFamily(family("Pentatonic"))
            vm.selectScale(4)
            vm.selectRoot(9)
            vm.setLabelMode(LabelMode.Notes)
            vm.setFretRange(5, 8)
            say(Caption(
                "One position at a time",
                "A minor pentatonic, frets 5–8: the fret slider narrows the neck to a single box.\n\nThe small numbers give the order to play it in, string by string.",
                step = 5,
            ))
            hold(2.0)

            narrate("hear")
            vm.setTempo(150)
            say(Caption(
                "Hear it — and follow it",
                "Press Play: the scale runs up and back down, and the ring shows which note is sounding.\n\nGood for ear training and for checking fingering.",
                step = 6,
                listening = true,
            ))
            hold(1.8)
            playScale()
            hold(1.0)

            narrate("tempo_slow")
            vm.setTempo(70)
            say(Caption(
                "Set the tempo",
                "The Tempo slider, under the fretboard, sets the playback speed.\n\nSlow — ♩ = 70 — for working out the fingering …",
                step = 7,
                listening = true,
            ))
            hold(2.5)
            playScale(limit = 7)
            narrate("tempo_drag")
            say(caption.value.copy(
                body = "The Tempo slider, under the fretboard, sets the playback speed.\n\n… then drag it up as the student gets it under the fingers.",
                listening = false,
            ))
            for (bpm in 80..200 step 10) {
                vm.setTempo(bpm)
                hold(0.15)
            }
            narrate("tempo_fast")
            say(caption.value.copy(
                body = "The Tempo slider, under the fretboard, sets the playback speed.\n\nUp to speed — ♩ = 200. Anywhere from 40 to 240 BPM.",
                listening = true,
            ))
            hold(1.5)
            playScale()
            hold(1.0)

            narrate("arpeggio")
            vm.setViewMode(ViewMode.Arpeggio)
            vm.setTempo(120)
            say(Caption(
                "Arpeggios",
                "Am arpeggio in the same position: numbered, and played up and back down like the scales.",
                step = 8,
                listening = true,
            ))
            hold(1.5)
            untilQuiet(gap = -1.5)
            playScale()
            hold(0.8)

            narrate("score")
            vm.setViewMode(ViewMode.Scale)
            vm.setDisplay(Display.Score)
            vm.setTempo(140)
            say(Caption(
                "Score and tab",
                "The same notes in standard notation — the note that's sounding lights up as it plays.",
                step = 9,
                listening = true,
            ))
            hold(1.2)
            untilQuiet(gap = -1.0)
            playScale()
            hold(0.6)
            narrate("tab")
            vm.setDisplay(Display.Tab)
            say(caption.value.copy(body = "The same notes in standard notation — the note that's sounding lights up as it plays.\n\nOr as tab, with the fret for every note."))
            hold(1.0)
            playScale(limit = 10)
            hold(1.0)
            vm.setDisplay(Display.Fretboard)

            narrate("strings")
            vm.setFretRange(0, 12)
            vm.setStringRange(3, 5)
            say(Caption(
                "Focus on a few strings",
                "The string slider limits the view too — here the top three strings, frets 0–12.\n\nUseful for triad shapes and single-string work.",
                step = 10,
            ))
            hold(2.0)

            narrate("key")
            vm.setFretRange(0, state.fretCount)
            vm.setStringRange(0, state.tuning.strings - 1)
            vm.selectFamily(family("Major (diatonic)"))
            vm.selectRoot(0)
            say(Caption(
                "The chords of the key",
                "Every seven-note scale is harmonised for you, with Roman numerals:\nI  ii  iii  IV  V  vi  vii°",
                step = 11,
            ))
            hold(2.0)
            narrate("sevenths")
            vm.setDiatonicSevenths(true)
            say(caption.value.copy(body = "Every seven-note scale is harmonised for you, with Roman numerals — as triads or as sevenths:\nImaj7  ii7  iii7  IVmaj7  V7  vi7  viiø7"))
            hold(2.0)

            narrate("tapchord")
            val ii = diatonicChords(state.scale, state.root, sevenths = true)[1]
            vm.openChord(1, ii.root, ii.type!!)
            awaitVoicings()
            say(Caption(
                "Tap a chord to see it",
                "Tap ii and you get Dm7: every chord tone on the neck …",
                step = 12,
            ))
            hold(2.0)
            narrate("shapes")
            say(caption.value.copy(
                body = "Tap ii and you get Dm7: every chord tone on the neck …\n\n… then playable shapes, found for the current tuning and strummed as you step through them.",
                listening = true,
            ))
            hold(1.5)
            repeat(3) { strumShape(seconds = 2.4) }

            narrate("hendrix")
            vm.selectRoot(4)
            vm.selectChord(chord("7♯9"))
            awaitVoicings()
            say(Caption(
                "106 chord types",
                "From triads and sixths to 13ths and altered dominants.\n\nHere E7♯9 — the “Hendrix chord”.",
                step = 13,
                listening = true,
            ))
            hold(2.0)
            untilQuiet(gap = -0.8) // strum as she names it
            strumShape("x7678x", seconds = 3.5)

            narrate("linked_cm7")
            vm.selectScale(0) // C Ionian underneath, so the switch below has to find a new scale
            vm.selectRoot(0)
            vm.selectChord(chord("m7"))
            awaitVoicings()
            say(Caption(
                "Scales and chords stay linked",
                "Choose Cm7 …",
                step = 14,
            ))
            hold(1.0)
            untilQuiet(gap = 0.1)
            narrate("linked_scale")
            vm.setViewMode(ViewMode.Scale)
            say(caption.value.copy(body = "Choose Cm7, switch to Scale, and you get a scale that fits it: ${state.title}.\n\nC Dorian ⇄ Cm, C Ionian ⇄ C — the views stay related."))
            hold(2.0)

            narrate("dadgad")
            vm.selectRoot(2)
            vm.selectScale(0)
            vm.selectTuning(4)
            say(Caption(
                "Any tuning",
                "D major in DADGAD.\n\nPresets for drop D, open G, 7-string, bass and ukulele — or retune any string yourself. Notes, numbers and chord shapes all follow.",
                step = 15,
            ))
            hold(2.0)
            narrate("ukulele")
            vm.selectTuning(10)
            say(caption.value.copy(body = "… and on ukulele.\n\nPresets for drop D, open G, 7-string, bass and ukulele — or retune any string yourself. Notes, numbers and chord shapes all follow."))
            hold(2.0)

            narrate("pdf")
            vm.selectTuning(0)
            vm.selectRoot(7)
            vm.setFretRange(0, 12)
            vm.setDisplay(Display.Score)
            say(Caption(
                "Print a handout",
                "PDF saves the current view — fretboard, score or tab — or all three on one page, ready to print.",
                step = 16,
            ))
            hold(1.5)
            pdfPage.value = pdfOfScreen(everything = true)
            say(caption.value.copy(body = "PDF saves the current view — fretboard, score or tab — or all three on one page, ready to print.\n\nHere: G major, frets 0–12, with neck diagram, notation and tab."))
            hold(3.5)
            untilQuiet()
            pdfPage.value = null

            // ---- AI assistants, through the real bridge ----
            val rising = Json.parse(File("../schema/fixtures/good-house-of-the-rising-sun.json").readText()) as Map<*, *>
            val wrongF = Json.parse(Json.write(rising)) as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            (wrongF["chords"] as MutableMap<String, Any?>)["F"] = mapOf("frets" to listOf(1, 3, 3, 2, 1, 0))
            vm.setDisplay(Display.Fretboard)
            narrate("ai_intro")
            say(Caption(
                "Ask your AI",
                "Claude Code, Codex or any MCP client can drive the app on this computer — or on a phone over USB.\n\n“Show me the chords for House of the Rising Sun.”",
                step = 17,
            ))
            type("> Show me the chords for House of the Rising Sun in Neckscape", 1.2)
            tool("load_progression(House of the Rising Sun)", "load_progression", mapOf("progression" to wrongF))
            narrate("ai_check")
            say(caption.value.copy(body = "Every payload is checked. A wrong F shape comes back with what the frets really play, and the assistant fixes it.\n\nAI content is labelled “check by ear”."))
            hold(1.0)
            type("  The F was really an Fmaj7 — fixing the voicing.", 1.0)
            tool("load_progression(House of the Rising Sun, F fixed)", "load_progression", mapOf("progression" to rising))
            untilQuiet(gap = -1.0)
            say(caption.value.copy(listening = true))
            playSong(bars = 4)
            hold(0.5)

            narrate("ai_overlay")
            say(Caption("A scale over the chords", "“Put A minor pentatonic, position 5, over it.”\n\nThe scale sits behind the chord shapes, roots picked out.", step = 18))
            type("> Put A minor pentatonic, position 5, over it", 1.0)
            tool("show_scale(A minor_pentatonic, frets 5–8, overlay)", "show_scale", mapOf(
                "scale" to mapOf("schema_version" to "1.0", "type" to "scale", "root" to "A", "scale" to "minor_pentatonic",
                    "fret_range" to listOf(5, 8), "highlight" to mapOf("roots" to true, "chord_tones_of" to "Am")),
                "overlay" to true,
            ))
            hold(2.0)

            narrate("ai_tab")
            terminal.value = emptyList()
            say(Caption("Tabs from your library", "“Tab me the refrain of Greensleeves.”\n\nThe assistant finds it in your tab library and the app opens it at the right bar.", step = 19, listening = true))
            type("> Tab me the refrain of Greensleeves", 1.0)
            val green = File("../demo/library/Traditional - Greensleeves.musicxml").readText()
            type("  ⏺ neckscape · find_tab(song=\"Greensleeves\", part=\"refrain\")", 0.5)
            type("    library: Traditional - Greensleeves.musicxml", 0.4)
            val found = bridgeCall("import_file", mapOf("content" to green, "file_name" to "Traditional - Greensleeves.musicxml", "part" to "refrain"))
            type("    ✓ ${found["rendered_summary"]}", 0.6)
            vm.setDisplay(Display.Tab)
            untilQuiet(gap = -1.5)
            playSong(bars = 4, section = "refrain")
            narrate("ai_honest")
            say(caption.value.copy(
                body = "No guessing a recorded solo from memory: if a tab isn't in your library, it says so — and offers the key, scale shapes and original licks instead.",
                listening = false,
            ))
            type("> Tab me the solo of Free Bird", 1.0)
            type("  ⏺ neckscape · find_tab(song=\"Free Bird\", part=\"solo\")", 0.5)
            type("    ✗ NOT_FOUND: no tab for 'Free Bird' in your tab library", 0.8)
            type("  I don't have that tab. It's in G major — want the G major", 0.4)
            type("  pentatonic positions, or some original licks in that style?", 1.5)
            untilQuiet()
            terminal.value = emptyList()

            narrate("files")
            val midi = midiFor(state, emptyList(), emptyList())
            vm.importUserFile("Greensleeves.mid", midi)
            vm.setDisplay(Display.Score)
            say(Caption(
                "Your files, in and out",
                "Open Guitar Pro, MusicXML, MIDI, ChordPro and text tabs — notes are placed on the neck for you.\n\nSave any view as PDF or MIDI. Here: Greensleeves saved as MIDI and opened again.",
                step = 20,
            ))
            hold(3.0)
            untilQuiet()

            narrate("outro")
            say(Caption("NeckScape-Navigator", "Android phone and tablet  ·  Windows  ·  macOS  ·  Linux\n\nAnd your AI assistant", fullScreen = true))
            hold(2.5)
            untilQuiet(gap = 1.5)

            finish()
        }

        private fun finish() {
            // ffconcat applies a duration only to entries followed by another file.
            concat.append("file f%04d.png\n".format(frame - 1))
            File(out, "frames.ffconcat").writeText(concat.toString())

            val rate = PluckSynth.SAMPLE_RATE
            val music = PluckSynth.render(audio, ring = 2.5)
            val total = maxOf((time * rate).toInt(), music.size)
            // Duck the guitar under the voice, easing in and out (~60 ms) so it doesn't pump.
            val target = FloatArray(total) { 0.9f }
            for ((start, pcm) in voices) {
                val from = ((start - 0.1) * rate).toInt().coerceAtLeast(0)
                val to = minOf(total, (start * rate).toInt() + pcm.size + (0.25 * rate).toInt())
                for (i in from until to) target[i] = 0.35f
            }
            val voice = FloatArray(total)
            for ((start, pcm) in voices) {
                val at = (start * rate).toInt()
                for (i in pcm.indices) if (at + i < total) voice[at + i] += pcm[i] * 0.8f
            }
            var gain = 0.9f
            val mix = ShortArray(total) { i ->
                gain += (target[i] - gain) * 0.0004f
                val m = if (i < music.size) music[i] * gain else 0f
                (m + voice[i]).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
            }
            writeWav(File(out, "audio.wav"), mix, rate)
            println("NeckScape video: $frame frames, %.1f s".format(time))
        }
    }

    @Composable
    private fun CaptionPanel(c: Caption, modifier: Modifier) {
        Column(
            modifier
                .background(Color(0xFF1F1B2E))
                .padding(horizontal = 32.dp, vertical = 40.dp),
        ) {
            Text("NeckScape-Navigator  ·  feature tour", color = Color(0xFFB39DDB), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(36.dp))
            Text(c.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
            Spacer(Modifier.height(20.dp))
            Text(c.body, color = Color.White.copy(alpha = 0.87f), fontSize = 19.sp, lineHeight = 28.sp)
            if (c.listening) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "♪  listen",
                    color = Color(0xFF1F1B2E),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFFFFCC80), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (c.step > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 1..steps) {
                        val color = if (i <= c.step) Color(0xFFB39DDB) else Color.White.copy(alpha = 0.2f)
                        Box(Modifier.size(if (i == c.step) 12.dp else 8.dp).background(color, CircleShape))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${c.step} / $steps", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
        }
    }

    /** An AI terminal session, in the corner of the app. */
    @Composable
    private fun Terminal(lines: List<String>, modifier: Modifier) {
        Column(
            modifier
                .padding(20.dp)
                .width(720.dp)
                .background(Color(0xF2101418), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text("claude  ·  ~/music", color = Color(0xFF9E9E9E), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            for (line in lines) {
                val color = when {
                    line.startsWith(">") -> Color(0xFFFFFFFF)
                    "✓" in line -> Color(0xFF81C784)
                    "⚠" in line -> Color(0xFFFFB74D)
                    "✗" in line -> Color(0xFFE57373)
                    "⏺" in line -> Color(0xFF90CAF9)
                    else -> Color(0xFFE0E0E0)
                }
                Text(line, color = color, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    /** A saved PDF page over the app, like opening it in a viewer. */
    @Composable
    private fun PdfPreview(page: ImageBitmap) {
        Box(Modifier.fillMaxSize().background(Color(0xFF424242)), contentAlignment = Alignment.Center) {
            Image(page, contentDescription = null, modifier = Modifier.padding(vertical = 24.dp).fillMaxHeight())
        }
    }

    @Composable
    private fun TitleCard(c: Caption) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1F1B2E)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (color in listOf(0xFFE53935, 0xFFFB8C00, 0xFF1E88E5, 0xFF8E24AA)) {
                        Box(Modifier.size(22.dp).background(Color(color), CircleShape))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(c.title, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text(
                    c.body,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 24.sp,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 16-bit mono PCM samples from a WAV file (as written by ffmpeg -c:a pcm_s16le -ac 1). */
private fun readWav(file: File): ShortArray {
    val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    buf.position(12)
    while (buf.remaining() >= 8) {
        val id = ByteArray(4).also { buf.get(it) }.decodeToString()
        val size = buf.int
        if (id == "data") return ShortArray(minOf(size, buf.remaining()) / 2) { buf.short }
        buf.position(buf.position() + size + (size and 1))
    }
    error("no data chunk in $file")
}

private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
    val data = pcm.size * 2
    val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    buf.put("data".toByteArray()).putInt(data)
    pcm.forEach { buf.putShort(it) }
    file.writeBytes(buf.array())
}
