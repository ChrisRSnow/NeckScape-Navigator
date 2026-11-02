package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Tuning
import org.w3c.dom.Element

/**
 * Guitar Pro files: the binary .gp3/.gp4/.gp5 formats, and .gp (Guitar Pro
 * 7/8: a zip holding Content/score.gpif XML). Reads one track's first voice:
 * frets, rhythm, section markers and the common techniques. .gpx (Guitar
 * Pro 6) uses a proprietary container and isn't supported.
 */
object GuitarProImporter {
    fun read(bytes: ByteArray, fileName: String, part: String?, track: String?): ImportResult {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return Gp7.read(bytes, fileName, part, track)
        if (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "BCFZ") {
            throw BridgeError("UNSUPPORTED_FORMAT", "Guitar Pro 6 (.gpx) files aren't supported; export as .gp5 or MusicXML from Guitar Pro", "format")
        }
        return Gp345(bytes, fileName).read(part, track)
    }
}

/** The binary formats. Field layouts follow the published format notes (as implemented by PyGuitarPro and TuxGuitar). */
private class Gp345(private val b: ByteArray, private val fileName: String) {
    private var pos = 0
    private var major = 0
    private var minor = 0

    private fun need(n: Int) { if (n < 0 || pos + n > b.size) throw BridgeError("PARSE_FAILED", "Guitar Pro file ends early (at byte $pos)") }
    private fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
    private fun i8(): Int = u8().toByte().toInt()
    private fun bool() = u8() != 0
    private fun i16(): Int { val lo = u8(); val hi = u8(); return ((hi shl 8) or lo).toShort().toInt() }
    private fun i32(): Int { val a = u8(); val c = u8(); val d = u8(); val e = u8(); return a or (c shl 8) or (d shl 16) or (e shl 24) }
    private fun skip(n: Int) { need(n); pos += n }
    private fun byteSizeString(count: Int): String {
        val len = u8()
        need(count)
        val s = String(b, pos, minOf(len, count), Charsets.ISO_8859_1)
        pos += count
        return s
    }
    private fun intByteSizeString(): String {
        val count = i32()
        if (count < 1 || count > 1 shl 20) { if (count <= 0) return ""; throw BridgeError("PARSE_FAILED", "Bad string length $count") }
        return byteSizeString(count - 1)
    }
    private fun intSizeString(): String {
        val count = i32()
        if (count < 0 || count > 1 shl 20) throw BridgeError("PARSE_FAILED", "Bad string length $count")
        need(count)
        return String(b, pos, count, Charsets.ISO_8859_1).also { pos += count }
    }

    private val v5 get() = major == 5
    private val v4 get() = major == 4
    private val v510 get() = major == 5 && minor >= 10

    private class GTrack(val name: String, val tuning: List<Int>, val drums: Boolean, val capo: Int)
    private class Header(val beats: Int, val unit: Int, val marker: String?)
    private class RawNote(val string: Int, val fret: Int, val tie: Boolean, val dead: Boolean, var technique: Technique?, val hammer: Boolean, val slide: Boolean)

    fun read(part: String?, trackWanted: String?): ImportResult {
        val version = byteSizeString(30)
        val m = Regex("""v(\d)\.(\d+)""").find(version) ?: throw BridgeError("PARSE_FAILED", "Not a Guitar Pro 3–5 file")
        major = m.groupValues[1].toInt()
        minor = m.groupValues[2].toInt()
        if (major !in 3..5) throw BridgeError("UNSUPPORTED_FORMAT", "Guitar Pro version $major isn't supported", "format")
        if (version.startsWith("CLIPBOARD")) throw BridgeError("UNSUPPORTED_FORMAT", "Guitar Pro clipboard files aren't supported", "format")

        val title = intByteSizeString()
        intByteSizeString() // subtitle
        val artist = intByteSizeString()
        repeat(if (v5) 6 else 5) { intByteSizeString() } // album, words, (music), copyright, tab, instructions
        repeat(i32().coerceIn(0, 1000)) { intByteSizeString() } // notices
        var tempo: Int
        if (!v5) {
            bool() // triplet feel
            if (v4) lyrics()
            tempo = i32()
            i32() // key
            if (v4) i8() // octave
        } else {
            lyrics()
            if (v510) { i32(); i32(); skip(11) } // RSE master effect
            skip(4 * 2 + 4 * 4 + 4 + 2) // page size, margins, score size, header/footer flags
            repeat(10) { intByteSizeString() }
            intByteSizeString() // tempo name
            tempo = i32()
            if (v510) bool()
            i8() // key
            i32() // octave
        }
        val channelIsDrums = BooleanArray(64)
        repeat(64) { i ->
            i32(); skip(6); skip(2)
            channelIsDrums[i] = i % 16 == 9
        }
        if (v5) { skip(19 * 2); i32() } // directions, master reverb
        val measureCount = i32()
        val trackCount = i32()
        if (measureCount !in 1..20000 || trackCount !in 1..128) throw BridgeError("PARSE_FAILED", "Implausible song size ($measureCount measures, $trackCount tracks)")

        val headers = ArrayList<Header>()
        var prev: Header? = null
        repeat(measureCount) { n ->
            if (v5 && n > 0) skip(1)
            val flags = u8()
            val num = if (flags and 1 != 0) i8() else prev?.beats ?: 4
            val den = if (flags and 2 != 0) i8() else prev?.unit ?: 4
            if (flags and 8 != 0) i8() // repeat close
            if (!v5 && flags and 16 != 0) u8() // alternative ending
            val marker = if (flags and 32 != 0) intByteSizeString().also { skip(4) } else null
            if (flags and 64 != 0) { i8(); i8() } // key change
            if (v5) {
                if (flags and 16 != 0) u8()
                if (flags and 3 != 0) skip(4) // beaming
                if (flags and 16 == 0) skip(1)
                u8() // triplet feel
            }
            val h = Header(num.coerceIn(1, 32), den.coerceIn(1, 32), marker?.trim()?.takeIf { it.isNotEmpty() })
            headers += h
            prev = h
        }

        val tracks = ArrayList<GTrack>()
        repeat(trackCount) { t ->
            if (v5 && (t == 0 || !v510)) skip(1)
            val flags = u8()
            val name = byteSizeString(40).trim()
            val strings = i32().coerceIn(1, 7)
            val tuning = (0 until 7).map { i32() }.take(strings)
            i32() // port
            val channel = i32() - 1
            i32() // effect channel
            i32() // fret count
            val capo = i32()
            skip(4) // colour
            if (v5) {
                i16(); u8(); u8() // settings, accentuation, bank
                u8(); repeat(3) { i32() }; skip(12) // RSE humanize etc.
                i32(); i32(); i32() // RSE instrument
                if (v510) i32() else { i16(); skip(1) }
                if (v510) { skip(4); intByteSizeString(); intByteSizeString() }
            }
            tracks += GTrack(name.ifEmpty { "Track ${t + 1}" }, tuning, flags and 1 != 0 || channel in 0..63 && channelIsDrums[channel], capo.coerceIn(0, 12))
        }
        if (v5) skip(if (v510) 1 else 2)

        val candidates = tracks.withIndex().filter { !it.value.drums }
        if (candidates.isEmpty()) throw BridgeError("PARSE_FAILED", "Only drum tracks in $fileName")
        val chosen = candidates[chooseTrack(candidates.map { it.value.name }, trackWanted, part)].index

        val raw = ArrayList<Pair<Header, List<RawEvent>>>()
        val lastFret = tracks.map { HashMap<Int, Int>() }
        for (h in headers) {
            for ((ti, t) in tracks.withIndex()) {
                repeat(if (v5) 2 else 1) { v ->
                    val events = readVoice(t, lastFret[ti])
                    if (ti == chosen && v == 0) raw += h to events
                }
                if (v5 && pos < b.size) u8() // line break
            }
        }
        val t = tracks[chosen]
        val tuning = Tuning.fromMidi("From file", t.tuning.reversed())
        val first = headers.first()
        val tab = Tab(
            title = title.ifBlank { Importers.fileTitle(fileName) }, attribution = artist.ifBlank { null },
            source = Source("user_file", file = fileName), tuning = tuning, capo = t.capo,
            tempoBpm = tempo.toDouble().takeIf { it in 20.0..400.0 }?.let { it / TimeSignature(first.beats, first.unit).pulse },
            timeSignature = TimeSignature(first.beats, first.unit),
            measures = linkLegato(raw),
        )
        val note = if (candidates.size > 1) "track '${t.name}' of ${candidates.size}" else null
        return ImportResult(tab, note = note)
    }

    private fun lyrics() {
        i32()
        repeat(5) { i32(); intSizeString() }
    }

    /** A beat as read: its time, length and notes with their legato flags still attached. */
    private class RawEvent(val at: Double, val dur: Double, val notes: List<RawNote>)

    private fun readVoice(track: GTrack, lastFret: HashMap<Int, Int>): List<RawEvent> {
        val beats = i32()
        if (beats !in 0..1024) throw BridgeError("PARSE_FAILED", "Implausible beat count $beats at byte $pos")
        val events = ArrayList<RawEvent>()
        var at = 0.0
        repeat(beats) {
            val (dur, empty, notes) = readBeat(track, lastFret)
            if (!empty) {
                // Tied notes keep ringing rather than sounding again; dead notes have no pitch.
                events += RawEvent(at, dur, notes.filter { !it.tie && !it.dead })
                at += dur
            }
        }
        return events
    }

    private fun readBeat(track: GTrack, lastFret: HashMap<Int, Int>): Triple<Double, Boolean, List<RawNote>> {
        val flags = u8()
        val status = if (flags and 64 != 0) u8() else 1
        val value = 1 shl (i8() + 2).coerceIn(0, 8)
        var quarters = 4.0 / value
        if (flags and 1 != 0) quarters *= 1.5
        if (flags and 32 != 0) {
            val tuplet = i32()
            val times = when (tuplet) { 3 -> 2; 5, 6, 7 -> 4; 9, 10, 11, 12, 13 -> 8; else -> tuplet }
            if (tuplet > 0) quarters = quarters * times / tuplet
        }
        if (flags and 2 != 0) readChord()
        if (flags and 4 != 0) intByteSizeString()
        var beatTechnique: Technique? = null
        if (flags and 8 != 0) beatTechnique = readBeatEffects()
        if (flags and 16 != 0) readMixTable()
        val stringFlags = u8()
        val notes = ArrayList<RawNote>()
        for (s in 1..track.tuning.size) {
            if (stringFlags and (1 shl (7 - s)) != 0) notes += readNote(s, lastFret, beatTechnique)
        }
        if (v5) {
            val f2 = i16()
            if (f2 and 2048 != 0) u8()
        }
        return Triple(quarters, status == 0, notes)
    }

    private fun readChord() {
        val newFormat = bool()
        if (!newFormat) {
            intByteSizeString()
            if (i32() != 0) repeat(6) { i32() }
            return
        }
        if (major == 3) {
            bool(); skip(3); repeat(5) { i32() }; bool(); byteSizeString(22); repeat(3) { i32() }; i32()
            repeat(6) { i32() }; i32(); repeat(6) { i32() }; repeat(7) { bool() }; skip(1)
        } else {
            bool(); skip(3); u8(); u8(); u8(); i32(); i32(); bool(); byteSizeString(22); u8(); u8(); u8(); i32()
            repeat(7) { i32() }; u8(); skip(15); repeat(7) { bool() }; skip(1); skip(7); bool()
        }
    }

    private fun readBeatEffects(): Technique? {
        var t: Technique? = null
        if (major == 3) {
            val f1 = u8()
            if (f1 and 1 != 0 || f1 and 2 != 0) t = Technique("vibrato")
            if (f1 and 32 != 0) { if (u8() == 0) i32() else i32() }
            if (f1 and 64 != 0) { i8(); i8() }
            if (f1 and 12 != 0) t = Technique("harmonic")
        } else {
            val f1 = i8()
            val f2 = i8()
            if (f1 and 2 != 0) t = Technique("vibrato")
            if (f1 and 32 != 0) i8()
            if (f2 and 4 != 0) readBend()
            if (f1 and 64 != 0) { i8(); i8() }
            if (f2 and 2 != 0) i8()
        }
        return t
    }

    private fun readMixTable() {
        i8() // instrument
        if (v5) {
            i32(); i32(); i32()
            if (v510) i32() else { i16(); skip(1) }
            if (!v510) skip(1)
        }
        val values = (0 until 6).map { i8() }
        if (v5) intByteSizeString()
        val tempo = i32()
        repeat(values.count { it >= 0 }) { i8() }
        if (tempo >= 0) { i8(); if (v510) bool() }
        if (major >= 4) i8() // all-tracks flags
        if (v5) { i8(); if (v510) { intByteSizeString(); intByteSizeString() } }
    }

    /** Peak bend in semitones (GP stores 25 units per semitone). */
    private fun readBend(): Double {
        i8(); i32()
        val points = i32().coerceIn(0, 64)
        var peak = 0
        repeat(points) { i32(); peak = maxOf(peak, i32()); bool() }
        return peak / 25.0
    }

    private fun readNote(string: Int, lastFret: HashMap<Int, Int>, beatTechnique: Technique?): RawNote {
        val flags = u8()
        val type = if (flags and 32 != 0) u8() else 1
        if (!v5 && flags and 1 != 0) { i8(); i8() }
        if (flags and 16 != 0) i8()
        var fret = if (flags and 32 != 0) i8() else lastFret[string] ?: 0
        if (type == 2) fret = lastFret[string] ?: fret
        fret = fret.coerceIn(0, 99)
        if (flags and 128 != 0) { i8(); i8() }
        if (v5) {
            if (flags and 1 != 0) skip(8)
            u8()
        }
        var technique = beatTechnique
        var hammer = false
        var slide = false
        if (flags and 8 != 0) {
            if (major == 3) {
                val f = u8()
                hammer = f and 2 != 0
                if (f and 8 != 0) technique = technique ?: Technique("let_ring")
                if (f and 1 != 0) technique = Technique("bend", semitones = readBend().takeIf { it > 0 } ?: 2.0)
                if (f and 16 != 0) { i8(); u8(); u8(); i8() }
                if (f and 4 != 0) slide = true
            } else {
                val f1 = i8()
                val f2 = i8()
                hammer = f1 and 2 != 0
                if (f1 and 8 != 0) technique = technique ?: Technique("let_ring")
                if (f2 and 2 != 0) technique = Technique("palm_mute")
                if (f2 and 64 != 0) technique = Technique("vibrato")
                if (f1 and 1 != 0) technique = Technique("bend", semitones = readBend().takeIf { it > 0 } ?: 2.0)
                if (f1 and 16 != 0) { if (v5) skip(5) else { i8(); u8(); u8(); i8() } }
                if (f2 and 4 != 0) i8()
                if (f2 and 8 != 0) { val sl = if (v5) u8() else i8(); slide = if (v5) sl and 3 != 0 else sl in 1..2 }
                if (f2 and 16 != 0) {
                    val h = i8()
                    if (v5) when (h) { 2 -> { u8(); i8(); u8() }; 3 -> u8() }
                    technique = Technique("harmonic")
                }
                if (f2 and 32 != 0) { i8(); i8() }
            }
        }
        if (type != 3) lastFret[string] = fret
        return RawNote(string, fret, tie = type == 2, dead = type == 3, technique = technique, hammer = hammer, slide = slide)
    }

    /**
     * Hammer-ons, pull-offs and slides are flagged on the note they leave. The
     * schema marks a hammer/pull on the note it arrives at, and a slide on the
     * note it leaves, with the fret it slides to.
     */
    private fun linkLegato(raw: List<Pair<Header, List<RawEvent>>>): List<Measure> {
        val flat = raw.flatMap { (_, events) -> events.flatMap { e -> e.notes } }
        val technique = HashMap<RawNote, Technique?>()
        for ((i, n) in flat.withIndex()) {
            if (!n.hammer && !n.slide) continue
            val next = flat.subList(i + 1, flat.size).firstOrNull { it.string == n.string } ?: continue
            if (n.slide) technique[n] = Technique("slide_to", fret = next.fret)
            else if (next.technique == null && next !in technique) technique[next] = Technique(if (next.fret >= n.fret) "hammer_on" else "pull_off")
        }
        return raw.map { (h, events) ->
            Measure(
                events.map { e -> TabEvent(e.at, e.dur, e.notes.map { n -> TabNote(n.string, n.fret, if (n in technique) technique[n] else n.technique) }) },
                h.marker,
            )
        }
    }
}

/** Guitar Pro 7/8: Content/score.gpif inside a zip. */
private object Gp7 {
    private val NOTE_VALUES = mapOf("Whole" to 4.0, "Half" to 2.0, "Quarter" to 1.0, "Eighth" to 0.5, "16th" to 0.25, "32nd" to 0.125, "64th" to 0.0625, "128th" to 0.03125)

    fun read(bytes: ByteArray, fileName: String, part: String?, track: String?): ImportResult {
        val files = unzip(bytes)
        val gpif = files.entries.firstOrNull { it.key.endsWith("score.gpif") }?.value
            ?: throw BridgeError("PARSE_FAILED", "No Content/score.gpif inside $fileName")
        val root = parseXml(gpif).documentElement
        fun ids(e: Element?, tag: String): List<Int> = e?.text(tag)?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        fun byId(container: String, tag: String) = root.kid(container)?.kids(tag)?.associateBy { it.getAttribute("id").toIntOrNull() ?: -1 } ?: emptyMap()

        val score = root.kid("Score")
        val title = score?.text("Title")?.ifBlank { null } ?: Importers.fileTitle(fileName)
        val artist = score?.text("Artist")?.ifBlank { null }
        val trackEls = root.kid("Tracks")?.kids("Track").orEmpty()
        val names = trackEls.map { it.text("Name")?.ifBlank { null } ?: "Track ${it.getAttribute("id")}" }
        val pitchesOf = trackEls.map { t ->
            t.all("Property").firstOrNull { it.getAttribute("name") == "Tuning" }?.text("Pitches")
                ?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
        }
        val usable = trackEls.indices.filter { pitchesOf[it] != null }
        if (usable.isEmpty()) throw BridgeError("PARSE_FAILED", "No fretted tracks in $fileName")
        val chosen = usable[chooseTrack(usable.map { names[it] }, track, part)]
        val pitches = pitchesOf[chosen]!!
        val capo = trackEls[chosen].all("Property").firstOrNull { it.getAttribute("name") == "CapoFret" }?.text("Fret")?.toIntOrNull() ?: 0

        val bars = byId("Bars", "Bar")
        val voices = byId("Voices", "Voice")
        val beats = byId("Beats", "Beat")
        val notes = byId("Notes", "Note")
        val rhythms = byId("Rhythms", "Rhythm")
        val tempo = root.kid("MasterTrack")?.all("Automation")?.firstOrNull { it.text("Type") == "Tempo" }
            ?.text("Value")?.split(" ")?.firstOrNull()?.toDoubleOrNull()

        var time = TimeSignature(4, 4)
        var firstTime: TimeSignature? = null
        val measures = ArrayList<Measure>()
        for (mb in root.kid("MasterBars")?.kids("MasterBar").orEmpty()) {
            mb.text("Time")?.split("/")?.let { if (it.size == 2) time = TimeSignature(it[0].toIntOrNull() ?: 4, it[1].toIntOrNull() ?: 4) }
            if (firstTime == null) firstTime = time
            val section = mb.kid("Section")?.let { s -> s.text("Text")?.ifBlank { null } ?: s.text("Letter") }
            val barId = ids(mb, "Bars").getOrNull(chosen)
            val voiceId = ids(bars[barId], "Voices").firstOrNull { it >= 0 }
            val events = ArrayList<TabEvent>()
            var at = 0.0
            for (beatId in ids(voices[voiceId], "Beats")) {
                val beat = beats[beatId] ?: continue
                val r = rhythms[beat.kid("Rhythm")?.getAttribute("ref")?.toIntOrNull()]
                var q = NOTE_VALUES[r?.text("NoteValue")] ?: 1.0
                r?.kid("AugmentationDot")?.getAttribute("count")?.toIntOrNull()?.let { q *= if (it >= 2) 1.75 else 1.5 }
                r?.kid("PrimaryTuplet")?.let { t ->
                    val num = t.getAttribute("num").toDoubleOrNull()
                    val den = t.getAttribute("den").toDoubleOrNull()
                    if (num != null && den != null && num > 0) q = q * den / num
                }
                val tabNotes = ids(beat, "Notes").mapNotNull { notes[it] }.mapNotNull { n -> note(n, pitches.size) }
                events += TabEvent(at, q, tabNotes)
                at += q
            }
            measures += Measure(events, section)
        }
        if (measures.isEmpty()) throw BridgeError("PARSE_FAILED", "No bars in $fileName")
        val ts = firstTime ?: TimeSignature(4, 4)
        val tab = Tab(
            title = title, attribution = artist, source = Source("user_file", file = fileName),
            tuning = Tuning.fromMidi("From file", pitches), capo = capo.coerceIn(0, 12),
            tempoBpm = tempo?.let { it / ts.pulse }, timeSignature = ts, measures = measures,
        )
        return ImportResult(tab, note = if (usable.size > 1) "track '${names[chosen]}' of ${usable.size}" else null)
    }

    private fun note(n: Element, strings: Int): TabNote? {
        val props = n.kid("Properties")?.kids("Property").orEmpty().associateBy { it.getAttribute("name") }
        val string = props["String"]?.text("String")?.toIntOrNull() ?: return null
        val fret = props["Fret"]?.text("Fret")?.toIntOrNull() ?: return null
        if (n.kid("Tie")?.getAttribute("destination") == "true") return null
        val technique = when {
            props.containsKey("Bended") -> Technique("bend", semitones = (props["BendDestinationValue"]?.text("Float")?.toDoubleOrNull() ?: 100.0) / 50.0)
            props.containsKey("Slide") -> null
            props.containsKey("HopoDestination") -> Technique("hammer_on")
            props.containsKey("HarmonicType") -> Technique("harmonic")
            props.containsKey("PalmMuted") -> Technique("palm_mute")
            n.kid("Vibrato") != null -> Technique("vibrato")
            n.kid("LetRing") != null -> Technique("let_ring")
            else -> null
        }
        // GPIF counts strings from the lowest (0); tab numbering counts from the highest (1).
        return TabNote(strings - string, fret, technique)
    }
}
