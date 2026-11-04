package com.crsnow.neckscape.ui

import com.crsnow.neckscape.song.BridgeError
import com.crsnow.neckscape.song.Importers
import com.crsnow.neckscape.song.Node
import com.crsnow.neckscape.song.Progression
import com.crsnow.neckscape.song.SCHEMA_VERSION
import com.crsnow.neckscape.song.ScaleNames
import com.crsnow.neckscape.song.ScaleOverlay
import com.crsnow.neckscape.song.SchemaReader
import com.crsnow.neckscape.song.Song
import com.crsnow.neckscape.song.Source
import com.crsnow.neckscape.song.Tab
import com.crsnow.neckscape.song.TabNote
import com.crsnow.neckscape.song.Technique
import com.crsnow.neckscape.song.Warning
import com.crsnow.neckscape.song.position
import com.crsnow.neckscape.theory.Instrument
import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Tunings
import com.crsnow.neckscape.theory.VoicingFinder
import java.util.Base64

const val APP_NAME = "NeckScape-Navigator"
const val APP_VERSION = "1.3.0"

/**
 * The app side of every AI tool (docs/ai-integration-spec.md). The bridge,
 * the Android intent and tests all call [call]; every answer has the same
 * shape: ok, warnings, rendered_summary — or ok=false with an error.
 */
class AiTools(private val vm: NeckScapeViewModel, private val platform: String) {

    /** Tools the app itself answers; list_targets, find_tab and import_url live in the MCP server. */
    val tools = listOf(
        "get_app_info", "get_state", "set_tuning", "show_chord", "load_progression", "load_tab",
        "show_scale", "highlight_notes", "import_file", "playback", "clear", "undo",
    )

    fun call(tool: String, args: Map<String, Any?>): Map<String, Any?> = try {
        when (tool) {
            "get_app_info" -> ok(appInfo(), "$APP_NAME $APP_VERSION on $platform")
            "get_state" -> ok(state(), describe())
            "set_tuning" -> setTuning(args)
            "show_chord" -> showChord(args)
            "load_progression" -> load(args, "progression")
            "load_tab" -> load(args, "tab")
            "show_scale" -> showScale(args)
            "highlight_notes" -> highlight(args)
            "import_file" -> importFile(args)
            "playback" -> playback(args)
            "clear" -> { vm.clear(); ok(summary = "Cleared the neck") }
            "undo" -> {
                if (vm.state.value.undoDepth == 0) throw BridgeError("NOT_FOUND", "Nothing to undo")
                vm.undo()
                ok(summary = "Undone; now showing: ${describe()}")
            }
            else -> throw BridgeError("INVALID_SCHEMA", "Unknown tool '$tool'", "tool")
        }
    } catch (e: BridgeError) {
        mapOf("ok" to false, "error" to e.toJson(), "warnings" to emptyList<Any>())
    } catch (e: Exception) {
        mapOf("ok" to false, "error" to mapOf("code" to "INVALID_SCHEMA", "detail" to (e.message ?: e.toString())), "warnings" to emptyList<Any>())
    }

    private fun ok(extra: Map<String, Any?> = emptyMap(), summary: String, warnings: List<Warning> = emptyList()) =
        linkedMapOf<String, Any?>("ok" to true, "warnings" to warnings.map { it.toJson() }, "rendered_summary" to summary) + extra

    private val reader get() = vm.state.value.let { SchemaReader(it.baseTuning, it.fretCount) }

    private fun appInfo() = mapOf(
        "app" to APP_NAME,
        "version" to APP_VERSION,
        "platform" to platform,
        "schema_version" to SCHEMA_VERSION,
        "tools" to tools,
        "scales" to ScaleNames.all.keys.toList(),
        "voicing_styles" to listOf("open", "barre", "drop2", "position_N"),
        "import_formats" to Importers.FORMATS,
        "techniques" to Technique.TYPES.toList(),
        "sounds" to Instrument.entries.map { it.name.lowercase() },
        "tunings" to Tunings.all.map { mapOf("name" to it.name, "strings" to it.midi.map { m -> Tuning.midiName(m) }) },
        "max_frets" to vm.state.value.fretCount,
        "string_numbering" to "1 = first (highest) string, as in tab",
    )

    private fun state(): Map<String, Any?> {
        val s = vm.state.value
        val song = s.song
        val play = vm.playback.value
        return mapOf(
            "type" to when {
                s.viewMode == ViewMode.Song && song is Progression -> "progression"
                s.viewMode == ViewMode.Song && song is Tab -> "tab"
                s.viewMode == ViewMode.Song && s.overlay != null -> "scale"
                s.viewMode == ViewMode.Song -> "empty"
                else -> s.viewMode.name.lowercase()
            },
            "title" to s.title,
            "source" to song?.source?.kind,
            "key" to ((song as? Progression)?.key?.toString() ?: if (s.viewMode == ViewMode.Scale) s.root.name + " " + s.scale.name else null),
            "time_signature" to song?.timeSignature?.toString(),
            "tempo_bpm" to song?.tempoBpm,
            "tuning" to mapOf("name" to s.baseTuning.name, "strings" to s.baseTuning.midi.map { Tuning.midiName(it) }),
            "capo" to s.capo,
            "current_bar" to if (song != null) s.songBar + 1 else null,
            "bar_count" to when (song) {
                is Progression -> song.flatBars.size
                is Tab -> song.measures.size
                null -> null
            },
            "current_chord" to (s.songChord ?: if (s.viewMode == ViewMode.Chord) s.chord.displayName(s.root) else null),
            "sections" to when (song) {
                is Progression -> song.sections.map { it.name }
                is Tab -> song.measures.mapNotNull { it.marker }
                null -> emptyList()
            },
            "overlay" to s.overlay?.let { "${it.root.name} ${it.name}" },
            "highlights" to s.highlights?.let { mapOf("count" to it.positions.size, "label" to it.label) },
            "playback" to mapOf("state" to if (play.playing) "playing" else "stopped", "looping" to play.looping, "section" to play.section),
            "display" to s.display.name.lowercase(),
            "level" to s.level.name.lowercase(),
            "can_undo" to (s.undoDepth > 0),
        )
    }

    private fun describe(): String {
        val s = vm.state.value
        return when (val song = s.song) {
            is Progression -> progressionSummary(song)
            is Tab -> tabSummary(song)
            null -> s.title
        } + (s.overlay?.let { "; overlay ${it.root.name} ${it.name}" } ?: "")
    }

    private fun setTuning(args: Map<String, Any?>): Map<String, Any?> {
        val root = Node(args, "")
        val t = root.opt("tuning")?.let { n ->
            if (n.value is String) Tunings.all.firstOrNull { it.name.equals(n.str(), true) || it.name.startsWith(n.str(), true) }
                ?: throw n.bad("Unknown tuning preset '${n.str()}'; get_app_info lists them")
            else reader.tuning(n)
        }
        val capo = root.opt("capo")?.int(0, 12)
        if (t == null && capo == null) throw root.bad("Give a tuning, a capo, or both")
        vm.setTuning(t, capo)
        val s = vm.state.value
        return ok(summary = "${s.baseTuning.name} (${s.baseTuning.stringNames.joinToString(" ")})" + if (s.capo > 0) ", capo ${s.capo}" else ", no capo")
    }

    private fun showChord(args: Map<String, Any?>): Map<String, Any?> {
        val root = Node(args, "")
        val symbol = reader.chordSymbol(root.req("chord"))
        val style = root.opt("voicing_style")?.str() ?: "open"
        vm.showChord(symbol, style)
        val s = vm.state.value
        val shape = VoicingFinder.find(s.tuning, s.root, s.chord, s.fretCount).getOrNull(s.voicingIndex)
        return ok(summary = "${symbol.name} (${s.root.name} ${s.chord.name}), " + (shape?.let { "$style voicing ${it.tab}" } ?: "every chord tone (no playable shape found)"))
    }

    private fun load(args: Map<String, Any?>, type: String): Map<String, Any?> {
        val payload = args[type] ?: args
        val checked = reader.expect(payload, type)
        val song = checked.value as Song
        vm.loadSong(song)
        val summary = if (song is Progression) progressionSummary(song) else tabSummary(song as Tab)
        return ok(summary = summary, warnings = checked.warnings)
    }

    private fun progressionSummary(p: Progression): String {
        val parts = p.sections.map { sec ->
            val chords = sec.bars.flatten().distinct()
            "${sec.name}: ${sec.bars.size} bars" + (if (sec.repeat > 1) " ×${sec.repeat}" else "") +
                ", ${chords.size} chords (${chords.joinToString(" ")})"
        }
        return (parts + listOfNotNull(
            "${p.voicingStyle} voicings",
            p.key?.toString(),
            p.timeSignature.toString(),
            p.tempoBpm?.let { "${it.toInt()} bpm" },
            if (p.capo > 0) "capo ${p.capo}" else null,
        )).joinToString(", ")
    }

    private fun tabSummary(t: Tab): String {
        val notes = t.measures.sumOf { m -> m.events.sumOf { it.notes.size } }
        val markers = t.measures.mapNotNull { it.marker }
        return listOfNotNull(
            "${t.measures.size} measures",
            t.timeSignature.toString(),
            "$notes notes",
            t.tuning?.let { tt -> Tunings.all.firstOrNull { it.midi == tt.midi }?.name ?: tt.name },
            if (t.capo > 0) "capo ${t.capo}" else null,
            if (markers.isNotEmpty()) "sections: ${markers.joinToString(", ")}" else null,
            if (!t.playable) "no rhythm (display only)" else null,
        ).joinToString(", ")
    }

    private fun showScale(args: Map<String, Any?>): Map<String, Any?> {
        val over = (args["overlay"] as? Boolean) ?: false
        val raw = args["scale"]
        val payload: Any? = when (raw) {
            is Map<*, *> -> raw
            is String -> mapOf("schema_version" to SCHEMA_VERSION, "type" to "scale", "scale" to raw, "root" to (args["root"] ?: throw Node(null, "root").bad("Give the root, e.g. \"A\"")), "fret_range" to args["fret_range"])
                .filterValues { it != null }
            else -> throw Node(raw, "scale").bad("scale is a scale payload or a scale name")
        }
        val checked = reader.expect(payload, "scale")
        val scale = checked.value as ScaleOverlay
        vm.showScale(scale, over)
        return ok(
            summary = "${scale.root.name} ${scale.name}" + (scale.fretRange?.let { ", frets ${it.first}–${it.last}" } ?: "") +
                if (over) ", over ${vm.state.value.title}" else "",
            warnings = checked.warnings,
        )
    }

    private fun highlight(args: Map<String, Any?>): Map<String, Any?> {
        val root = Node(args, "")
        val s = vm.state.value
        val tuning = s.tuning
        val positions = LinkedHashSet<Position>()
        val warnings = ArrayList<Warning>()
        root.opt("positions")?.arr()?.forEach { n ->
            val o = n.obj()
            val string = o.req("string").int(1, 12)
            val fret = o.req("fret").int(0, 36)
            val p = TabNote(string, fret).position(tuning)
            if (p == null) warnings += Warning("STRING_COUNT", o.path + ".string", "String $string on a ${tuning.strings}-string tuning")
            else positions += p
        }
        root.opt("notes")?.arr()?.forEach { n ->
            val note = runCatching { Note.parse(n.str()) }.getOrNull() ?: throw n.bad("'${n.str()}' isn't a note name")
            for (str in 0 until tuning.strings) for (f in s.fretRange) if (tuning.pitchClassAt(str, f) == note.pitchClass) positions += Position(str, f)
        }
        if (root.opt("positions") == null && root.opt("notes") == null) throw root.bad("Give positions or notes")
        val label = root.opt("label")?.str()
        vm.highlight(Highlights(positions, label))
        return ok(summary = "Marked ${positions.size} positions" + (label?.let { ": $it" } ?: ""), warnings = warnings)
    }

    private fun importFile(args: Map<String, Any?>): Map<String, Any?> {
        val root = Node(args, "")
        val bytes = root.opt("content_base64")?.let { Base64.getDecoder().decode(it.str()) }
            ?: root.opt("content")?.str()?.toByteArray(Charsets.UTF_8)
            ?: throw root.bad("Give content (text) or content_base64 (binary)")
        if (bytes.size > MAX_IMPORT_BYTES) throw BridgeError("INVALID_SCHEMA", "File is over ${MAX_IMPORT_BYTES / 1024 / 1024} MB", "content")
        val name = root.opt("file_name")?.str() ?: "file"
        val format = root.opt("format")?.str() ?: Importers.formatFor(name)
            ?: throw BridgeError("UNSUPPORTED_FORMAT", "Can't tell the format of '$name'; give format (${Importers.FORMATS.joinToString()})", "format")
        val result = Importers.import(bytes, format, name, root.opt("part")?.str(), root.opt("track")?.str(), vm.state.value.baseTuning)
        val sourceKind = root.opt("source")?.str() ?: "user_file"
        val song = result.song.withSource(
            Source(
                kind = sourceKind, file = name,
                url = root.opt("source_url")?.str(), author = root.opt("author")?.str(), rating = root.opt("rating")?.num(),
            ),
        )
        vm.loadSong(song)
        val start = result.startBar
        if (start > 0) vm.selectBar(start)
        val summary = (if (song is Progression) progressionSummary(song) else tabSummary(song as Tab)) +
            (result.note?.let { "; $it" } ?: "")
        return ok(summary = summary, warnings = result.warnings, extra = mapOf("title" to song.title, "start_bar" to start + 1))
    }

    private fun playback(args: Map<String, Any?>): Map<String, Any?> {
        val root = Node(args, "")
        val action = root.opt("action")?.str() ?: "play"
        return when (action) {
            "stop" -> { vm.stopPlayback(); ok(summary = "Stopped") }
            "play", "loop" -> {
                root.opt("sound")?.let { n ->
                    val want = n.str().lowercase()
                    val pick = Instrument.entries.firstOrNull { it.name.lowercase() == want || it.label.lowercase().startsWith(want) }
                        ?: throw n.bad("Unknown sound '${n.str()}'; sounds: ${Instrument.entries.joinToString { it.name.lowercase() }}")
                    vm.setInstrument(pick)
                }
                val pct = root.opt("tempo_pct")?.num() ?: 100.0
                if (pct < 10 || pct > 300) throw root.req("tempo_pct").bad("tempo_pct must be 10 to 300")
                val summary = vm.playSong(loop = action == "loop", section = root.opt("section")?.str(), tempoPct = pct)
                ok(summary = summary + if (vm.player == null) " (no audio output on this device)" else "")
            }
            else -> throw root.req("action").bad("action is play, stop or loop")
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    }
}

/** The same song with a different source label. */
fun Song.withSource(source: Source) = when (this) {
    is Progression -> copy(source = source)
    is Tab -> copy(source = source)
}
