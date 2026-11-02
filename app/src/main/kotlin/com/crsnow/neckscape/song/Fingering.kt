package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Tuning
import kotlin.math.abs

/**
 * Puts pitches on the neck for formats that only give pitch (MIDI, MusicXML
 * without tab): each note or chord goes where the hand already is, on
 * different strings, preferring the lower frets. Returns tab-style notes
 * (string 1 = first/highest), or an empty list for notes out of range.
 */
class Fingering(private val tuning: Tuning, private val fretCount: Int = 22) {
    private var anchor = 3.0

    fun place(midis: List<Int>): List<TabNote> {
        val out = ArrayList<TabNote>()
        val used = HashSet<Int>()
        // Highest first: melody notes get the best strings.
        for (m in midis.distinct().sortedDescending()) {
            val options = (0 until tuning.strings)
                .filter { it !in used }
                .map { s -> s to m - tuning.midi[s] }
                .filter { (_, f) -> f in 0..fretCount }
            val best = options.minByOrNull { (_, f) -> abs((if (f == 0) anchor else f.toDouble()) - anchor) + f * 0.08 } ?: continue
            used += best.first
            out += TabNote(tuning.strings - best.first, best.second)
        }
        val fretted = out.map { it.fret }.filter { it > 0 }
        if (fretted.isNotEmpty()) anchor = anchor * 0.4 + fretted.average() * 0.6
        return out
    }
}
