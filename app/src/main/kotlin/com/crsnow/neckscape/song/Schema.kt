package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.ChordSymbol
import com.crsnow.neckscape.theory.Interval
import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Scales
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.VoicingFinder
import com.crsnow.neckscape.theory.describeVoicing

/** A payload read and checked: the content plus anything worth warning about. */
data class Checked<T>(val value: T, val warnings: List<Warning>)

/** Largest stretch (fretted notes, lowest to highest fret) the app calls playable. */
const val MAX_STRETCH = 4

/**
 * Reads schema v1 payloads. Anything structurally wrong throws [BridgeError]
 * INVALID_SCHEMA with the path of the bad field; musical problems (wrong
 * frets, too many strings, overfull bars) come back as warnings.
 */
class SchemaReader(private val currentTuning: Tuning, private val fretCount: Int) {

    fun read(payload: Any?): Checked<Any> {
        val root = Node(payload, "").obj()
        checkVersion(root)
        return when (val type = root.req("type").str()) {
            "progression" -> progression(root)
            "tab" -> tab(root)
            "scale" -> scale(root)
            else -> throw root.req("type").bad("type must be progression, tab or scale, not '$type'")
        }
    }

    fun expect(payload: Any?, type: String): Checked<Any> {
        val node = Node(payload, "").obj()
        val actual = node.opt("type")?.str()
        if (actual != null && actual != type) throw node.req("type").bad("Expected a $type payload, got '$actual'")
        return read(if (actual == null) (payload as Map<*, *>) + ("type" to type) else payload)
    }

    private fun checkVersion(root: Node) {
        val v = root.req("schema_version").str()
        val major = Regex("""^(\d+)\.(\d+)$""").find(v)?.groupValues?.get(1)?.toInt()
            ?: throw root.req("schema_version").bad("schema_version must look like \"1.0\"")
        if (major > 1) throw BridgeError("UNSUPPORTED_VERSION", "schema_version $v is newer than this app understands (1.x)", "schema_version")
    }

    private fun source(root: Node): Source {
        val kind = root.opt("source")?.str() ?: "ai_generated"
        if (kind !in Source.KINDS) throw root.req("source").bad("source must be one of ${Source.KINDS.joinToString()}")
        return Source(
            kind = kind,
            url = root.opt("source_url")?.str(),
            author = root.opt("author")?.str(),
            rating = root.opt("rating")?.num(),
            file = root.opt("file_name")?.str(),
        )
    }

    fun tuning(node: Node?): Tuning {
        if (node == null) return currentTuning
        val t = node.obj()
        val strings = t.req("strings").arr()
        if (strings.size !in 1..12) throw t.req("strings").bad("A tuning needs 1 to 12 strings")
        val spec = strings.map { s ->
            val text = s.str()
            if (!Regex("""^[A-Ga-g][#b♯♭]{0,2}-?\d$""").matches(text)) throw s.bad("'$text' isn't a note with an octave, like E2 or F#3")
            text
        }
        return Tuning.parse(t.opt("name")?.str() ?: "Custom", spec.joinToString(" "))
    }

    private fun capo(root: Node) = root.opt("capo")?.int(0, 12) ?: 0

    private fun timeSignature(root: Node): TimeSignature {
        val n = root.opt("time_signature") ?: return TimeSignature(4, 4)
        val m = Regex("""^(\d{1,2})/(1|2|4|8|16|32)$""").find(n.str()) ?: throw n.bad("time_signature must look like 4/4 or 6/8")
        val beats = m.groupValues[1].toInt()
        if (beats !in 1..32) throw n.bad("time_signature needs 1 to 32 beats")
        return TimeSignature(beats, m.groupValues[2].toInt())
    }

    private fun tempo(root: Node) = root.opt("tempo_bpm")?.num()?.also {
        if (it < 20 || it > 400) throw root.req("tempo_bpm").bad("tempo_bpm must be between 20 and 400")
    }

    fun chordSymbol(n: Node): ChordSymbol =
        ChordSymbol.parse(n.str()) ?: throw n.bad("Unknown chord symbol '${n.str()}'")

    private fun frets(n: Node): List<Int?> = n.arr().map { f -> if (f.value == null) null else f.int(0, 36) }

    private fun progression(root: Node): Checked<Any> {
        val warnings = ArrayList<Warning>()
        val tuning = tuning(root.opt("tuning"))
        val style = root.opt("voicing_style")?.str() ?: "open"
        if (!Regex("""^(open|barre|drop2|position_\d{1,2})$""").matches(style)) {
            throw root.req("voicing_style").bad("voicing_style must be open, barre, drop2 or position_N")
        }
        val key = root.opt("key")?.obj()?.let { k ->
            val tonic = k.req("tonic").let { t -> runCatching { Note.parse(t.str()) }.getOrNull() ?: throw t.bad("'${t.str()}' isn't a note name") }
            Key(tonic, k.opt("mode")?.str() ?: "major")
        }
        val chords = LinkedHashMap<String, List<Int?>>()
        root.opt("chords")?.let { table ->
            for ((symbol, entry) in table.entries()) {
                val parsed = chordSymbol(Node(symbol, entry.path))
                val f = frets(entry.obj().req("frets"))
                chords[symbol] = f
                warnings += checkVoicing(tuning, parsed, f, entry.path)
            }
        }
        val sections = root.req("sections").arr().also { if (it.isEmpty()) throw root.req("sections").bad("sections can't be empty") }.map { s ->
            val sec = s.obj()
            Section(
                name = sec.opt("name")?.str() ?: "Section",
                bars = sec.req("bars").arr().map { bar ->
                    bar.arr().map { c ->
                        val text = c.str()
                        if (text.uppercase() !in NO_CHORD) chordSymbol(c)
                        text
                    }
                },
                repeat = sec.opt("repeat")?.int(1, 64) ?: 1,
            )
        }
        val p = Progression(
            title = root.req("title").str(),
            attribution = root.opt("attribution")?.str(),
            source = source(root),
            tuning = tuning,
            capo = capo(root),
            tempoBpm = tempo(root),
            timeSignature = timeSignature(root),
            key = key,
            voicingStyle = style,
            chords = chords,
            sections = sections,
        )
        return Checked(p, warnings)
    }

    /** STRING_COUNT, then VOICING_MISMATCH and UNPLAYABLE_VOICING for one chord's frets. */
    fun checkVoicing(tuning: Tuning, symbol: ChordSymbol, frets: List<Int?>, path: String): List<Warning> {
        if (frets.size != tuning.strings) {
            return listOf(Warning("STRING_COUNT", path, "${frets.size} frets given for a ${tuning.strings}-string tuning"))
        }
        val out = ArrayList<Warning>()
        val sounded = frets.withIndex().mapNotNull { (s, f) -> f?.let { (tuning.midi[s] + it) % 12 } }.toSet()
        val wanted = symbol.pitchClasses
        val essential = essentialIntervals(symbol).map { (symbol.root.pitchClass + it.semitones) % 12 }.toSet() +
            setOfNotNull(symbol.bass?.pitchClass)
        if (sounded.isEmpty() || !wanted.containsAll(sounded) || !sounded.containsAll(essential)) {
            out += Warning("VOICING_MISMATCH", path, "Frets give ${describeVoicing(tuning, frets)}, not ${symbol.name}")
        }
        val fretted = frets.filterNotNull().filter { it > 0 }
        val stretch = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
        when {
            fretted.any { it > fretCount } -> out += Warning("UNPLAYABLE_VOICING", path, "Fret ${fretted.max()} is beyond the ${fretCount}-fret neck")
            stretch > MAX_STRETCH -> out += Warning("UNPLAYABLE_VOICING", path, "Stretch of ${stretch + 1} frets (${fretted.min()}–${fretted.max()}) is over the limit of ${MAX_STRETCH + 1}")
        }
        return out
    }

    /** Notes a voicing must have to count as the chord: everything bar a perfect 5th; big chords may drop more. */
    private fun essentialIntervals(symbol: ChordSymbol): List<Interval> {
        val ivs = symbol.type.intervals
        val base = if (ivs.size > 4) VoicingFinder.requiredIntervals(symbol.type) else ivs
        return if (ivs.size >= 3) base.filterNot { it.degree == 5 && it.semitones == 7 } else base
    }

    private fun tab(root: Node): Checked<Any> {
        val warnings = ArrayList<Warning>()
        val tuning = tuning(root.opt("tuning"))
        val ts = timeSignature(root)
        val measures = root.req("measures").arr().also { if (it.isEmpty()) throw root.req("measures").bad("measures can't be empty") }.map { m ->
            val mo = m.obj()
            val events = mo.req("events").arr().map { e ->
                val eo = e.obj()
                val at = eo.req("at").num().also { if (it < 0) throw eo.req("at").bad("at can't be negative") }
                val dur = eo.opt("dur")?.let { d -> if (d.value == null) null else d.num().also { if (it <= 0) throw d.bad("dur must be positive") } }
                val notes = eo.req("notes").arr().map { n ->
                    val no = n.obj()
                    val string = no.req("string").int(1, 12)
                    val fret = no.req("fret").int(0, 36)
                    if (string > tuning.strings) warnings += Warning("STRING_COUNT", no.path + ".string", "String $string on a ${tuning.strings}-string tuning")
                    if (fret > fretCount) warnings += Warning("UNPLAYABLE_VOICING", no.path + ".fret", "Fret $fret is beyond the ${fretCount}-fret neck")
                    TabNote(string, fret, no.opt("technique")?.let(::technique))
                }
                TabEvent(at, dur, notes)
            }
            if (events.isNotEmpty() && events.all { it.dur != null }) {
                val end = events.maxOf { it.at + it.dur!! }
                if (end > ts.quarters + 1e-6) warnings += Warning("BAR_LENGTH", mo.path, "Events run to ${fmt(end)} quarter notes in a ${fmt(ts.quarters)}-quarter $ts measure")
                else if (end < ts.quarters - 1e-6) warnings += Warning("BAR_LENGTH", mo.path, "Events fill ${fmt(end)} of ${fmt(ts.quarters)} quarter notes in $ts")
            }
            Measure(events, mo.opt("marker")?.str())
        }
        val tab = Tab(
            title = root.req("title").str(),
            attribution = root.opt("attribution")?.str(),
            source = source(root),
            tuning = tuning,
            capo = capo(root),
            tempoBpm = tempo(root),
            timeSignature = ts,
            measures = measures,
        )
        return Checked(tab, warnings)
    }

    private fun technique(n: Node): Technique {
        val t = n.obj()
        val type = t.req("type").str()
        if (type !in Technique.TYPES) throw t.req("type").bad("Unknown technique '$type'")
        val semitones = t.opt("semitones")?.num()
        if (type == "bend" && semitones == null) throw t.req("semitones").bad("A bend needs semitones")
        val fret = t.opt("fret")?.int(0, 36)
        if (type == "slide_to" && fret == null) throw t.req("fret").bad("slide_to needs the fret to slide to")
        return Technique(type, semitones, fret)
    }

    private fun scale(root: Node): Checked<Any> {
        val rootNode = root.req("root")
        val note = runCatching { Note.parse(rootNode.str()) }.getOrNull() ?: throw rootNode.bad("'${rootNode.str()}' isn't a note name")
        val named = root.opt("scale")?.let { s -> ScaleNames.lookup(s.str()) ?: throw s.bad("Unknown scale '${s.str()}'; get_app_info lists the names") }
        val intervals = root.opt("intervals")?.arr()?.map { it.int(0, 11) }
        if (named == null && intervals == null) throw root.bad("A scale needs scale (a name) or intervals")
        val (f, s) = named ?: (-1 to -1)
        val scale = named?.let { Scales.families[f].scales[s] }
        val range = root.opt("fret_range")?.let { r ->
            val a = r.arr()
            if (a.size != 2) throw r.bad("fret_range is [from, to]")
            val from = a[0].int(0, 36)
            val to = a[1].int(from, 36)
            from..to
        }
        val hl = root.opt("highlight")?.obj()
        val chordTones = hl?.opt("chord_tones_of")?.also { chordSymbol(it) }?.str()
        return Checked(
            ScaleOverlay(
                root = note,
                scale = named,
                intervals = intervals?.distinct()?.sorted() ?: scale!!.intervals.map { it.semitones % 12 },
                name = scale?.name ?: "Custom scale",
                fretRange = range,
                highlightRoots = hl?.opt("roots")?.bool() ?: true,
                chordTonesOf = chordTones,
            ),
            emptyList(),
        )
    }

    companion object {
        val NO_CHORD = setOf("N.C.", "NC", "N.C", "%")
        private fun fmt(d: Double) = if (d % 1.0 == 0.0) "${d.toInt()}" else "$d"
    }
}

/** A JSON value with its path ("sections[0].bars[3]") for error messages. */
class Node(val value: Any?, val path: String) {
    fun bad(detail: String) = BridgeError("INVALID_SCHEMA", detail, path.ifEmpty { "(root)" })

    private fun child(key: String) = if (path.isEmpty()) key else "$path.$key"

    fun obj(): Node = if (value is Map<*, *>) this else throw bad("Expected an object")

    fun opt(key: String): Node? = (obj().value as Map<*, *>).let { m -> if (m.containsKey(key) && m[key] != null) Node(m[key], child(key)) else null }

    fun req(key: String): Node = opt(key) ?: throw Node(null, child(key)).bad("Missing required field '$key'")

    fun entries(): List<Pair<String, Node>> = (obj().value as Map<*, *>).map { (k, v) -> k.toString() to Node(v, child(k.toString())) }

    fun arr(): List<Node> = (value as? List<*>)?.mapIndexed { i, v -> Node(v, "$path[$i]") } ?: throw bad("Expected an array")

    fun str(): String = value as? String ?: throw bad("Expected a string")

    fun num(): Double = (value as? Number)?.toDouble() ?: throw bad("Expected a number")

    fun bool(): Boolean = value as? Boolean ?: throw bad("Expected true or false")

    fun int(min: Int, max: Int): Int {
        val d = num()
        if (d % 1.0 != 0.0) throw bad("Expected a whole number")
        if (d < min || d > max) throw bad("Must be between $min and $max")
        return d.toInt()
    }
}
