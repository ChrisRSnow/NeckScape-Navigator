package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Tunings

/** An imported file: the song, anything worth warning about, and where to land ([startBar]). */
data class ImportResult(val song: Song, val warnings: List<Warning> = emptyList(), val startBar: Int = 0, val note: String? = null)

/**
 * Reads user files into songs: ChordPro (chords kept, lyrics dropped), plain
 * text (ASCII) tab, MusicXML (.musicxml/.xml/.mxl), Guitar Pro (.gp3, .gp4,
 * .gp5, .gp) and MIDI (.mid). Everything here treats its input as untrusted.
 */
object Importers {
    val FORMATS = listOf("chordpro", "ascii_tab", "musicxml", "guitar_pro", "midi")

    fun formatFor(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "cho", "chopro", "chordpro", "crd", "pro" -> "chordpro"
        "txt", "tab", "text" -> "ascii_tab"
        "xml", "musicxml", "mxl" -> "musicxml"
        "gp3", "gp4", "gp5", "gp", "gpx" -> "guitar_pro"
        "mid", "midi", "kar" -> "midi"
        else -> null
    }

    /** Lands on [part] ("solo", "chorus") if the file marks it; picks [track] by name or number. */
    fun import(bytes: ByteArray, format: String, fileName: String, part: String?, track: String?, tuning: Tuning): ImportResult {
        val raw = try {
            when (format) {
                "chordpro" -> ChordProImporter.read(text(bytes), fileName, tuning)
                "ascii_tab" -> AsciiTabImporter.read(text(bytes), fileName, tuning)
                "musicxml" -> MusicXmlImporter.read(bytes, fileName, part, track)
                "guitar_pro" -> GuitarProImporter.read(bytes, fileName, part, track)
                "midi" -> Midi.read(bytes, fileName, part, track, tuningFor(6, tuning))
                else -> throw BridgeError("UNSUPPORTED_FORMAT", "Can't read '$format'; formats: ${FORMATS.joinToString()}", "format")
            }
        } catch (e: BridgeError) {
            throw e
        } catch (e: Exception) {
            throw BridgeError("PARSE_FAILED", "Couldn't read $fileName as $format: ${e.message ?: e.javaClass.simpleName}")
        }
        if (part.isNullOrBlank()) return raw
        val bars = raw.song.barsOf(part)
        return if (bars != null) raw.copy(startBar = bars.first, note = listOfNotNull(raw.note, "opened at '$part' (bar ${bars.first + 1})").joinToString("; "))
        else raw.copy(note = listOfNotNull(raw.note, "no '$part' marked in the file, so it opens at the start").joinToString("; "))
    }

    private fun text(bytes: ByteArray): String {
        val s = String(bytes, Charsets.UTF_8)
        // Latin-1 files decode to replacement characters as UTF-8.
        return if ('�' in s) String(bytes, Charsets.ISO_8859_1) else s
    }

    /** A preset with this many strings, for files that don't say how they're tuned. */
    fun tuningFor(strings: Int, current: Tuning): Tuning =
        if (current.strings == strings) current else Tunings.all.firstOrNull { it.strings == strings } ?: Tunings.all.first()

    /** Words that name a part of a song, for spotting section labels in text and files. */
    val SECTION_WORDS = Regex(
        """^\s*\[?\s*((intro|verse|pre-?chorus|chorus|bridge|solo|guitar solo|lead|outro|riff|interlude|break|coda|ending|instrumental|theme|hook|main riff|fill)(\s*\d+)?)\s*\]?\s*:?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun fileTitle(fileName: String) = fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()
}

/** ChordPro: directives and [Chord] markers; one bar per chord, lyrics dropped. */
object ChordProImporter {
    private val DIRECTIVE = Regex("""^\s*\{\s*([a-zA-Z_]+)\s*(?::\s*(.*?))?\s*}\s*$""")
    private val CHORD = Regex("""\[([^\]\s]+)]""")

    fun read(text: String, fileName: String, tuning: Tuning): ImportResult {
        var title: String? = null
        var artist: String? = null
        var key: Key? = null
        var time = TimeSignature(4, 4)
        var tempo: Double? = null
        var capo = 0
        val chords = LinkedHashMap<String, List<Int?>>()
        val sections = ArrayList<Section>()
        var name = "Song"
        var bars = ArrayList<List<String>>()
        val skipped = HashSet<String>()
        var inTab = false

        fun flush() {
            if (bars.isNotEmpty()) sections += Section(name, bars)
            bars = ArrayList()
        }

        for (line in text.lines()) {
            val d = DIRECTIVE.find(line)
            if (d != null) {
                val (dir, arg) = d.destructured
                when (dir.lowercase()) {
                    "title", "t" -> title = arg
                    "artist", "subtitle", "st", "composer" -> if (artist == null) artist = arg
                    "key" -> key = runCatching { com.crsnow.neckscape.theory.Note.parse(arg.trimEnd('m')) }.getOrNull()
                        ?.let { Key(it, if (arg.endsWith("m")) "minor" else "major") }
                    "time" -> Regex("""(\d+)/(\d+)""").find(arg)?.let { time = TimeSignature(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                    "tempo" -> tempo = arg.toDoubleOrNull()
                    "capo" -> capo = arg.toIntOrNull()?.coerceIn(0, 12) ?: 0
                    "define", "chord" -> define(arg)?.let { (sym, f) -> if (f.size == tuning.strings) chords[sym] = f }
                    "start_of_verse", "sov" -> { flush(); name = arg.ifBlank { "Verse" } }
                    "start_of_chorus", "soc" -> { flush(); name = arg.ifBlank { "Chorus" } }
                    "start_of_bridge", "sob" -> { flush(); name = arg.ifBlank { "Bridge" } }
                    "start_of_tab", "sot" -> inTab = true
                    "end_of_tab", "eot" -> inTab = false
                    "comment", "c", "comment_italic", "ci", "comment_box", "cb" ->
                        if (Importers.SECTION_WORDS.matches(arg)) { flush(); name = arg.trim('[', ']', ' ', ':') }
                }
                continue
            }
            if (inTab || line.trimStart().startsWith("#")) continue
            // A line that's just a part name ("Chorus", "[Verse 2]") starts a section, even in brackets.
            if (Importers.SECTION_WORDS.matches(line)) {
                flush()
                name = line.trim().trim('[', ']', ':').trim()
                continue
            }
            for (m in CHORD.findAll(line)) {
                val sym = m.groupValues[1]
                if (com.crsnow.neckscape.theory.ChordSymbol.parse(sym) != null) bars += listOf(sym) else skipped += sym
            }
        }
        flush()
        if (sections.isEmpty()) throw BridgeError("PARSE_FAILED", "No chords found in $fileName")
        val p = Progression(
            title = title ?: Importers.fileTitle(fileName),
            attribution = artist,
            source = Source("user_file", file = fileName),
            tuning = tuning, capo = capo, tempoBpm = tempo, timeSignature = time, key = key,
            voicingStyle = "open", chords = chords, sections = sections,
        )
        val note = if (skipped.isNotEmpty()) "skipped unrecognised chords ${skipped.joinToString(" ")}" else null
        return ImportResult(p, note = listOfNotNull(note, "lyrics dropped").joinToString("; "))
    }

    /** "{define: Am base-fret 1 frets x 0 2 2 1 0}" → Am to [null, 0, 2, 2, 1, 0]. */
    private fun define(arg: String): Pair<String, List<Int?>>? {
        val parts = arg.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val base = parts.indexOf("base-fret").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1)?.toIntOrNull() } ?: 1
        val i = parts.indexOf("frets").takeIf { it >= 0 } ?: return null
        val frets = parts.drop(i + 1).takeWhile { it != "fingers" && it != "keys" }.map { f ->
            when {
                f.equals("x", true) || f == "-1" -> null
                else -> f.toIntOrNull()?.let { if (it > 0) it + base - 1 else it } ?: return null
            }
        }
        return parts[0] to frets
    }
}

/**
 * Plain-text tab: blocks of 4–12 lines like "e|--0--3-|", bar lines between
 * measures, h/p/b/r/slides/~ marks, and labels like [Solo] above a block.
 * Text tab has no rhythm, so events get an order but no duration.
 */
object AsciiTabImporter {
    private val LINE = Regex("""^\s*([A-Ga-g][#b]?\d?)?\s*([|:]?)([-0-9|hpbr/\\~xX()<>.^*=s ]+)\s*$""")

    private fun isTabLine(l: String): Boolean {
        val m = LINE.find(l) ?: return false
        val body = m.groupValues[3]
        return body.count { it == '-' } >= 6 && body.count { it == '-' } * 2 >= body.trim().length
    }

    fun read(text: String, fileName: String, tuning: Tuning): ImportResult {
        val lines = text.lines().map { it.trimEnd() }
        val measures = ArrayList<Measure>()
        var stringCount = 0
        var marker: String? = null
        var i = 0
        while (i < lines.size) {
            if (!isTabLine(lines[i])) {
                Importers.SECTION_WORDS.find(lines[i])?.let { marker = it.groupValues[1].trim() }
                i++
                continue
            }
            var j = i
            while (j < lines.size && isTabLine(lines[j])) j++
            val block = lines.subList(i, j)
            if (block.size in 4..12) {
                stringCount = maxOf(stringCount, block.size)
                val parsed = block(block)
                parsed.forEachIndexed { k, m -> measures += if (k == 0 && marker != null) m.copy(marker = marker) else m }
                marker = null
            }
            i = j
        }
        if (measures.isEmpty()) throw BridgeError("PARSE_FAILED", "No tab staves (lines like e|--0--3--|) found in $fileName")
        val t = Tab(
            title = Importers.fileTitle(fileName), attribution = null, source = Source("user_file", file = fileName),
            tuning = Importers.tuningFor(stringCount, tuning), capo = 0, tempoBpm = null, timeSignature = TimeSignature(4, 4),
            measures = measures,
        )
        return ImportResult(t, note = "plain-text tab has no rhythm, so it shows in order but doesn't play")
    }

    /** One block of lines (first line = highest string) → measures split at bar lines. */
    private fun block(block: List<String>): List<Measure> {
        // Strip labels so every line starts at its first '|' (or first tab character).
        val bodies = block.map { l ->
            val m = LINE.find(l)!!
            m.groupValues[2] + m.groupValues[3]
        }
        val width = bodies.maxOf { it.length }
        val rows = bodies.map { it.padEnd(width, '-') }
        val measures = ArrayList<Measure>()
        var events = ArrayList<TabEvent>()
        // Pending hammer/pull marks carry to the next note on that string.
        val pending = HashMap<Int, String>()
        // Columns already read on each string (a bend or slide target belongs to the note before it).
        val readTo = IntArray(rows.size)
        for (c in 0 until width) {
            if (rows.all { it[c] == '|' || it[c] == ':' }) {
                if (events.isNotEmpty()) measures += Measure(events)
                events = ArrayList()
                continue
            }
            val notes = ArrayList<TabNote>()
            rows.forEachIndexed { r, row ->
                if (c < readTo[r] || !row[c].isDigit()) return@forEachIndexed
                var end = c
                while (end < width && row[end].isDigit() && end - c < 2) end++
                readTo[r] = end
                val fret = row.substring(c, end).toInt()
                var technique: Technique? = when (pending.remove(r)) {
                    "h" -> Technique("hammer_on")
                    "p" -> Technique("pull_off")
                    else -> null
                }
                when (row.getOrNull(end)) {
                    'b' -> {
                        val target = Regex("""^\d{1,2}""").find(row.substring(end + 1))?.value
                        technique = Technique("bend", semitones = (((target?.toInt()) ?: (fret + 2)) - fret).toDouble().coerceAtLeast(0.5))
                        readTo[r] = end + 1 + (target?.length ?: 0)
                    }
                    '/', '\\' -> Regex("""^\d{1,2}""").find(row.substring(end + 1))?.value?.let { target ->
                        technique = Technique("slide_to", fret = target.toInt())
                        readTo[r] = end + 1 + target.length
                    }
                    '~' -> technique = technique ?: Technique("vibrato")
                    'h', 'p' -> pending[r] = row[end].toString()
                }
                if (row.getOrNull(c - 1) == '<') technique = Technique("harmonic")
                notes += TabNote(r + 1, fret, technique)
            }
            if (notes.isNotEmpty()) events += TabEvent(events.size.toDouble(), null, notes)
        }
        if (events.isNotEmpty()) measures += Measure(events)
        return measures
    }
}
