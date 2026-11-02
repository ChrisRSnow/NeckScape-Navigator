package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Tuning
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Safe XML parsing: no external entities or DTDs (files come from anywhere). */
internal fun parseXml(bytes: ByteArray): Document {
    val f = DocumentBuilderFactory.newInstance()
    for ((feature, on) in listOf(
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )) runCatching { f.setFeature(feature, on) }
    f.isExpandEntityReferences = false
    f.isNamespaceAware = false
    return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

internal fun Element.kids(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

internal fun Element.kid(tag: String): Element? = kids(tag).firstOrNull()

internal fun Element.text(tag: String): String? = kid(tag)?.textContent?.trim()

internal fun Element.all(tag: String): List<Element> =
    getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

/**
 * Entries of a zip, by name, up to a size limit. Reads the central directory
 * (which has the true sizes) rather than streaming local headers, which some
 * writers, Guitar Pro among them, fill in loosely.
 */
internal fun unzip(bytes: ByteArray, limit: Int = 32 * 1024 * 1024): Map<String, ByteArray> {
    fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    fun bad(why: String): Nothing = throw BridgeError("PARSE_FAILED", "Damaged archive: $why")
    // End of central directory: the last "PK\u0005\u0006" within the final 64 KB.
    val eocd = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65_535)).firstOrNull { i ->
        u32(i) == 0x06054b50L
    } ?: bad("no central directory")
    val count = u16(eocd + 10)
    var at = u32(eocd + 16).toInt()
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    repeat(count) {
        if (at + 46 > bytes.size || u32(at) != 0x02014b50L) bad("bad directory entry")
        val method = u16(at + 10)
        val compressed = u32(at + 20)
        val size = u32(at + 24)
        val nameLen = u16(at + 28)
        val extra = u16(at + 30)
        val comment = u16(at + 32)
        val local = u32(at + 42).toInt()
        val name = String(bytes, at + 46, nameLen, Charsets.UTF_8)
        at += 46 + nameLen + extra + comment
        if (name.endsWith("/")) return@repeat
        total += size
        if (size > limit || total > limit) throw BridgeError("PARSE_FAILED", "Archive expands to more than ${limit / 1024 / 1024} MB")
        if (local + 30 > bytes.size || u32(local) != 0x04034b50L) bad("bad entry header for $name")
        val start = local + 30 + u16(local + 26) + u16(local + 28)
        if (start + compressed > bytes.size) bad("$name runs past the end")
        out[name] = when (method) {
            0 -> bytes.copyOfRange(start, start + compressed.toInt())
            8 -> java.util.zip.Inflater(true).run {
                setInput(bytes, start, compressed.toInt())
                val data = ByteArray(size.toInt())
                var n = 0
                while (n < data.size && !finished()) {
                    val got = inflate(data, n, data.size - n)
                    if (got == 0 && (needsInput() || needsDictionary())) break
                    n += got
                }
                end()
                data.copyOf(n)
            }
            else -> bad("$name uses unsupported compression $method")
        }
    }
    return out
}

/** Which of several tracks to use: [track] by name or 1-based number, else a lead part for a solo, else the first guitar. */
internal fun chooseTrack(names: List<String>, track: String?, part: String?): Int {
    if (names.isEmpty()) throw BridgeError("PARSE_FAILED", "The file has no tracks")
    track?.let { t ->
        t.toIntOrNull()?.let { n -> if (n in 1..names.size) return n - 1 }
        names.indexOfFirst { it.contains(t, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
        throw BridgeError("NOT_FOUND", "No track '$t'; tracks: ${names.joinToString()}", "track")
    }
    val lower = names.map { it.lowercase() }
    if (part != null && Regex("solo|lead|riff").containsMatchIn(part.lowercase())) {
        lower.indexOfFirst { "lead" in it || "solo" in it }.takeIf { it >= 0 }?.let { return it }
    }
    return lower.indexOfFirst { "guitar" in it || "gtr" in it }.takeIf { it >= 0 }
        ?: lower.indexOfFirst { "drum" !in it && "bass" !in it && "perc" !in it }.takeIf { it >= 0 } ?: 0
}

object MusicXmlImporter {
    private val STEPS = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)

    fun read(bytes: ByteArray, fileName: String, part: String?, track: String?): ImportResult {
        val xml = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) fromMxl(bytes) else bytes
        val doc = parseXml(xml)
        val root = doc.documentElement
        if (root.tagName != "score-partwise") {
            throw BridgeError("UNSUPPORTED_FORMAT", "Only partwise MusicXML is supported (this is ${root.tagName})", "format")
        }
        val title = root.kid("work")?.text("work-title") ?: root.text("movement-title") ?: Importers.fileTitle(fileName)
        val creators = root.kid("identification")?.kids("creator").orEmpty()
        val artist = (creators.firstOrNull { it.getAttribute("type") == "composer" } ?: creators.firstOrNull())?.textContent?.trim()

        val partList = root.kid("part-list")?.kids("score-part").orEmpty()
        val names = partList.map { it.text("part-name") ?: it.getAttribute("id") }
        val parts = root.kids("part")
        val chosen = chooseTrack(names.ifEmpty { parts.map { it.getAttribute("id") } }, track, part)
        val id = partList.getOrNull(chosen)?.getAttribute("id")
        val p = parts.firstOrNull { it.getAttribute("id") == id } ?: parts.getOrNull(chosen)
            ?: throw BridgeError("PARSE_FAILED", "Part not found")

        // Tuning, from the tab staff if there is one.
        val tuningEls = p.all("staff-tuning").sortedBy { it.getAttribute("line").toIntOrNull() ?: 0 }
        val tuning = if (tuningEls.isNotEmpty()) {
            Tuning.fromMidi("From file", tuningEls.map { t ->
                (STEPS[t.text("tuning-step")] ?: 0) + (t.text("tuning-alter")?.toDoubleOrNull()?.toInt() ?: 0) + 12 * ((t.text("tuning-octave")?.toIntOrNull() ?: 3) + 1)
            })
        } else com.crsnow.neckscape.theory.Tunings.all.first()
        val capo = p.all("capo").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0
        val fingering = Fingering(tuning)
        val hasTab = p.all("fret").isNotEmpty()

        var divisions = 1.0
        var time = TimeSignature(4, 4)
        var tempo: Double? = null
        var transpose = 0
        val measures = ArrayList<Measure>()
        for (m in p.kids("measure")) {
            m.kid("attributes")?.let { a ->
                a.text("divisions")?.toDoubleOrNull()?.let { divisions = it }
                a.kid("time")?.let { t ->
                    val beats = t.text("beats")?.toIntOrNull()
                    val unit = t.text("beat-type")?.toIntOrNull()
                    if (beats != null && unit != null) time = TimeSignature(beats, unit)
                }
                a.kid("transpose")?.let { tr ->
                    transpose = (tr.text("chromatic")?.toDoubleOrNull()?.toInt() ?: 0) + 12 * (tr.text("octave-change")?.toIntOrNull() ?: 0)
                }
            }
            var marker: String? = null
            var cursor = 0.0
            var lastStart = 0.0
            // at → notes, built up as <chord/> notes join the previous one.
            val byTime = LinkedHashMap<Double, Pair<Double?, MutableList<TabNote>>>()
            val pitchesAt = LinkedHashMap<Double, Pair<Double?, MutableList<Int>>>()
            val elements = (0 until m.childNodes.length).map { m.childNodes.item(it) }.filterIsInstance<Element>()
            for (e in elements) when (e.tagName) {
                "direction" -> {
                    e.all("rehearsal").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { marker = marker ?: it }
                    e.all("words").map { it.textContent.trim() }.firstOrNull { Importers.SECTION_WORDS.matches(it) }?.let { marker = marker ?: it.trim('[', ']', ':') }
                    e.all("sound").firstOrNull()?.getAttribute("tempo")?.toDoubleOrNull()?.let { tempo = tempo ?: it }
                }
                "sound" -> e.getAttribute("tempo").toDoubleOrNull()?.let { tempo = tempo ?: it }
                "backup" -> cursor -= (e.text("duration")?.toDoubleOrNull() ?: 0.0)
                "forward" -> cursor += (e.text("duration")?.toDoubleOrNull() ?: 0.0)
                "note" -> {
                    if (e.kid("grace") != null || e.kid("cue") != null) continue
                    val dur = (e.text("duration")?.toDoubleOrNull() ?: 0.0)
                    val isChord = e.kid("chord") != null
                    val start = if (isChord) lastStart else cursor
                    if (!isChord) cursor += dur
                    lastStart = start
                    val at = start / divisions
                    val q = dur / divisions
                    if (e.kid("rest") != null) {
                        if (!isChord) byTime.getOrPut(at) { q to ArrayList() }
                        continue
                    }
                    val tech = e.kid("notations")?.kid("technical")
                    val string = tech?.text("string")?.toIntOrNull()
                    val fret = tech?.text("fret")?.toIntOrNull()
                    if (hasTab) {
                        if (string == null || fret == null) continue
                        val list = byTime.getOrPut(at) { q to ArrayList() }.second
                        if (list.none { it.string == string && it.fret == fret }) list += TabNote(string, fret, technique(e))
                    } else {
                        val pitch = e.kid("pitch") ?: continue
                        val midi = (STEPS[pitch.text("step")] ?: 0) + (pitch.text("alter")?.toDoubleOrNull()?.toInt() ?: 0) +
                            12 * ((pitch.text("octave")?.toIntOrNull() ?: 4) + 1) + transpose
                        pitchesAt.getOrPut(at) { q to ArrayList() }.second += midi
                    }
                }
            }
            for ((at, pair) in pitchesAt) byTime.getOrPut(at) { pair.first to ArrayList() }.second += fingering.place(pair.second.map { it - capo })
            val events = byTime.entries.sortedBy { it.key }.map { (at, v) -> TabEvent(at, v.first?.takeIf { it > 0 }, v.second) }
            measures += Measure(linkSlides(events), marker)
        }
        if (measures.isEmpty()) throw BridgeError("PARSE_FAILED", "No measures in ${names.getOrNull(chosen) ?: "the part"}")
        val tab = Tab(
            title = title, attribution = artist, source = Source("user_file", file = fileName),
            tuning = tuning, capo = capo, tempoBpm = tempo, timeSignature = time, measures = measures,
        )
        val note = if (names.size > 1) "track '${names[chosen]}' of ${names.size}" else null
        return ImportResult(tab, note = note)
    }

    private fun technique(note: Element): Technique? {
        val n = note.kid("notations") ?: return null
        val t = n.kid("technical")
        t?.kid("bend")?.let { b -> return Technique("bend", semitones = b.text("bend-alter")?.toDoubleOrNull() ?: 2.0) }
        if (t?.kid("hammer-on")?.getAttribute("type") == "stop") return Technique("hammer_on")
        if (t?.kid("pull-off")?.getAttribute("type") == "stop") return Technique("pull_off")
        if (t?.kid("harmonic") != null) return Technique("harmonic")
        if (n.kid("slide")?.getAttribute("type") == "start" || n.kid("glissando")?.getAttribute("type") == "start") return Technique("slide_to", fret = -1)
        if (n.kid("ornaments")?.let { it.kid("vibrato") != null || it.kid("wavy-line") != null } == true) return Technique("vibrato")
        return null
    }

    /** Slides are marked on the start note; their target is the next note on that string. */
    private fun linkSlides(events: List<TabEvent>): List<TabEvent> = events.mapIndexed { i, e ->
        e.copy(notes = e.notes.map { n ->
            if (n.technique?.type == "slide_to" && n.technique.fret == -1) {
                val target = events.drop(i + 1).firstNotNullOfOrNull { later -> later.notes.firstOrNull { it.string == n.string }?.fret }
                n.copy(technique = target?.let { Technique("slide_to", fret = it) })
            } else n
        })
    }

    private fun fromMxl(bytes: ByteArray): ByteArray {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"]?.let { parseXml(it) }
        val path = container?.documentElement?.all("rootfile")?.firstOrNull()?.getAttribute("full-path")
        return files[path] ?: files.entries.firstOrNull { !it.key.startsWith("META-INF") && it.key.endsWith(".xml") }?.value
            ?: throw BridgeError("PARSE_FAILED", "No score inside the .mxl archive")
    }
}
