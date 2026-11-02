package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.ChordSymbol
import com.crsnow.neckscape.theory.NoteEvent
import com.crsnow.neckscape.theory.PluckSynth
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.pickVoicing
import java.util.concurrent.ConcurrentHashMap

/** Tab string number (1 = first/highest) → the app's string index (0 = lowest). */
fun tabStringIndex(tuning: Tuning, string: Int): Int = tuning.strings - string

fun TabNote.position(tuning: Tuning): Position? =
    tabStringIndex(tuning, string).takeIf { it in 0 until tuning.strings }?.let { Position(it, fret) }

private data class VoicingKey(val tuning: Tuning, val symbol: String, val style: String, val frets: Int)
private val voicingCache = ConcurrentHashMap<VoicingKey, Voicing>()

/**
 * The shape for [symbol]: from the progression's chord table, else picked in
 * its voicing style. With a capo, chord symbols name shapes relative to the
 * capo (as songbooks write them), so [tuning] is the strings without it.
 */
fun Progression.voicingFor(symbol: String, tuning: Tuning, fretCount: Int): Voicing? {
    chords[symbol]?.let { return Voicing(it) }
    if (symbol.uppercase() in SchemaReader.NO_CHORD) return null
    val parsed = ChordSymbol.parse(symbol) ?: return null
    val key = VoicingKey(tuning, symbol, voicingStyle, fretCount)
    voicingCache[key]?.let { return it }
    return pickVoicing(tuning, parsed, voicingStyle, fretCount)?.also { voicingCache[key] = it }
}

fun Voicing.positions(): Set<Position> = frets.withIndex().mapNotNull { (s, f) -> f?.let { Position(s, it) } }.toSet()

/** When to ring what during playback: [at] in seconds, [bar] the bar/measure index. */
data class Step(val at: Double, val bar: Int, val positions: Set<Position>)

data class Schedule(val notes: List<NoteEvent>, val steps: List<Step>, val seconds: Double, val summary: String)

/** Bars (progression) or measures (tab) belonging to [section]: a section name, or a tab marker. */
fun Song.barsOf(section: String?): IntRange? {
    val all = when (this) {
        is Progression -> 0 until flatBars.size
        is Tab -> measures.indices
    }
    if (section.isNullOrBlank()) return all
    val want = section.lowercase()
    return when (this) {
        is Progression -> {
            val idx = flatBars.indices.filter { flatBars[it].first.name.lowercase().contains(want) }
            if (idx.isEmpty()) null else idx.first()..idx.last()
        }
        is Tab -> {
            val start = measures.indexOfFirst { it.marker?.lowercase()?.contains(want) == true }
            if (start < 0) null else {
                val next = (start + 1 until measures.size).firstOrNull { measures[it].marker != null } ?: measures.size
                start until next
            }
        }
    }
}

/**
 * Notes and ring positions to play [song] (or one [section]) at its tempo
 * scaled by [tempoPct]. Chords strum on every beat of their share of the bar.
 */
fun schedule(song: Song, tuning: Tuning, fretCount: Int, section: String?, tempoPct: Double, shapes: Tuning = tuning): Schedule {
    val bars = song.barsOf(section) ?: throw BridgeError("NOT_FOUND", "No section or marker named '$section'")
    val ts = song.timeSignature
    val bpm = (song.tempoBpm ?: if (song is Tab) 100.0 else 90.0) * tempoPct / 100.0
    // tempo_bpm counts the beats you'd tap: quarters in 4/4, dotted quarters in 6/8.
    val secPerQuarter = 60.0 / bpm / ts.pulse
    val notes = ArrayList<NoteEvent>()
    val steps = ArrayList<Step>()
    var t = 0.0
    when (song) {
        is Progression -> {
            val flat = song.flatBars
            for (b in bars) {
                val chords = flat[b].second
                val share = ts.quarters / chords.size.coerceAtLeast(1)
                chords.forEachIndexed { i, symbol ->
                    val v = song.voicingFor(symbol, shapes, fretCount)
                    val start = t + i * share * secPerQuarter
                    var beat = 0.0
                    while (beat < share - 1e-6) {
                        val at = start + beat * secPerQuarter
                        if (v != null) notes += PluckSynth.strum(v.positions().map { tuning.midi[it.string] + it.fret }).map { it.copy(at = it.at + at) }
                        beat += ts.pulse
                    }
                    steps += Step(start, b, v?.positions().orEmpty())
                }
                if (chords.isEmpty()) steps += Step(t, b, emptySet())
                t += ts.quarters * secPerQuarter
            }
        }
        is Tab -> {
            if (!song.playable) throw BridgeError("UNSUPPORTED_FORMAT", "This tab has no rhythm (it came from plain text), so it can be shown but not played")
            for (m in bars) {
                for (e in song.measures[m].events.sortedBy { it.at }) {
                    val at = t + e.at * secPerQuarter
                    val ps = e.notes.mapNotNull { n -> n.position(tuning)?.let { p -> p to n } }
                    for ((p, n) in ps) {
                        val bend = if (n.technique?.type == "bend") n.technique.semitones?.toInt() ?: 0 else 0
                        notes += NoteEvent(tuning.midi[p.string] + p.fret + bend, at)
                    }
                    steps += Step(at, m, ps.map { it.first }.toSet())
                }
                t += ts.quarters * secPerQuarter
            }
        }
    }
    val what = if (song is Progression) "bars" else "measures"
    val summary = "Playing ${bars.count()} $what of ${song.title} at ${"%.0f".format(java.util.Locale.ROOT, bpm)} bpm" +
        (section?.let { " ($it)" } ?: "")
    return Schedule(notes, steps.sortedBy { it.at }, t, summary)
}
