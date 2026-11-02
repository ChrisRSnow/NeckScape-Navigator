package com.crsnow.neckscape.theory

/** One fingering: a fret per string (lowest-pitched string first), null = muted. */
data class Voicing(val frets: List<Int?>) {
    /** Lowest fretted (non-open) fret, or 0 for an all-open shape. */
    val position: Int get() = frets.filterNotNull().filter { it > 0 }.minOrNull() ?: 0

    /** Chord-chart notation: "x32010", or "x 10 12 12 11 x" once frets reach two digits. */
    val tab: String
        get() {
            val parts = frets.map { it?.toString() ?: "x" }
            return if (parts.any { it.length > 1 }) parts.joinToString(" ") else parts.joinToString("")
        }
}

/**
 * Brute-force search for playable shapes: within each window of [maxSpan]+1
 * frets, try every combination of chord tones / mutes per string and keep the
 * ones a hand could plausibly play.
 */
object VoicingFinder {
    fun find(
        tuning: Tuning,
        root: Note,
        chord: ChordType,
        fretCount: Int,
        maxSpan: Int = 3,
        allowInversions: Boolean = false,
    ): List<Voicing> {
        val chordPcs = chord.intervals.map { (root.pitchClass + it.semitones) % 12 }.toSet()
        val required = requiredIntervals(chord).map { (root.pitchClass + it.semitones) % 12 }.toSet()
        val minTones = minOf(chordPcs.size, 4, tuning.strings)
        val minStrings = if (chordPcs.size <= 2) 2 else minOf(tuning.strings, maxOf(3, minTones))

        val found = HashMap<List<Int?>, Int>() // frets → score
        val current = arrayOfNulls<Int>(tuning.strings)

        fun evaluate() {
            val sounded = current.indices.filter { current[it] != null }
            if (sounded.size < minStrings) return
            val interiorMutes = (sounded.first()..sounded.last()).count { current[it] == null }
            if (interiorMutes > 1) return

            val bassString = sounded.minBy { tuning.midi[it] + current[it]!! }
            val bassPc = tuning.pitchClassAt(bassString, current[bassString]!!)
            if (!allowInversions && bassPc != root.pitchClass) return

            val tones = sounded.map { tuning.pitchClassAt(it, current[it]!!) }.toSet()
            if (tones.size < minTones || !tones.containsAll(required)) return

            val fretted = sounded.map { current[it]!! }.filter { it > 0 }
            if (fretted.isNotEmpty()) {
                val low = fretted.min()
                // Notes at the lowest fret can share one barre finger.
                val fingers = fretted.count { it > low } + 1
                if (fingers > 4) return
            }

            val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
            val score = tones.size * 10 + sounded.size * 3 - interiorMutes * 6 - span
            val key = current.toList()
            if (score > (found[key] ?: Int.MIN_VALUE)) found[key] = score
        }

        fun search(string: Int, options: List<List<Int?>>) {
            if (string == tuning.strings) return evaluate()
            for (fret in options[string]) {
                current[string] = fret
                search(string + 1, options)
            }
        }

        for (start in 1..maxOf(1, fretCount - maxSpan)) {
            val options = (0 until tuning.strings).map { s ->
                buildList {
                    add(null)
                    // Open strings only in shapes near the nut.
                    if (start <= 4 && tuning.pitchClassAt(s, 0) in chordPcs) add(0)
                    for (f in start..minOf(start + maxSpan, fretCount)) {
                        if (tuning.pitchClassAt(s, f) in chordPcs) add(f)
                    }
                }
            }
            search(0, options)
        }

        // Keep the best couple of shapes at each position, ordered up the neck.
        return found.entries
            .map { (frets, score) -> Voicing(frets) to score }
            .groupBy { it.first.position }
            .toSortedMap()
            .flatMap { (_, shapes) -> shapes.sortedByDescending { it.second }.take(2).map { it.first } }
    }

    /**
     * Tones a voicing must contain. Small chords need everything; for bigger
     * ones a guitarist drops the perfect 5th and inner extensions but keeps the
     * root, the 3rd (or sus tone), the 7th/6th, an altered 5th, and the top
     * extension that names the chord.
     */
    internal fun requiredIntervals(chord: ChordType): List<Interval> {
        val ivs = chord.intervals
        if (ivs.size <= 4) return ivs
        val third = ivs.firstOrNull { it.degree == 3 } ?: ivs.firstOrNull { it.degree == 2 || it.degree == 4 }
        return ivs.filter { iv ->
            iv.degree == 1 ||
                iv == third ||
                iv.degree == 6 || iv.degree == 7 ||
                (iv.degree == 5 && iv.semitones != 7) ||
                iv == ivs.last()
        }
    }
}
