package com.crsnow.neckscape.theory

/**
 * A spelled note: a letter (0 = C … 6 = B) plus an accidental (+1 = ♯, -1 = ♭).
 *
 * Spelling matters — the third of D♭ major is F, not E♯, and the seventh of
 * C diminished seventh is B♭♭, not A — so everything is built from letters and
 * [Interval]s rather than bare pitch classes.
 */
data class Note(val letter: Int, val accidental: Int = 0) {
    init {
        require(letter in 0..6) { "letter must be 0..6, was $letter" }
    }

    /** 0 = C … 11 = B. */
    val pitchClass: Int get() = Math.floorMod(LETTER_PITCH[letter] + accidental, 12)

    val name: String get() = LETTER_NAMES[letter] + accidentalSymbol(accidental)

    /** The note [interval] above this one, spelled on the interval's letter. */
    operator fun plus(interval: Interval): Note {
        val newLetter = (letter + interval.degree - 1) % 7
        val target = pitchClass + interval.semitones
        val acc = Math.floorMod(target - LETTER_PITCH[newLetter] + 6, 12) - 6
        return Note(newLetter, acc)
    }

    /** The name with its octave for a sounding pitch: C4 is middle C (MIDI 60), and C♭4 is MIDI 59. */
    fun withOctave(midi: Int): String = name + (Math.floorDiv(midi - accidental, 12) - 1)

    override fun toString() = name

    companion object {
        val LETTER_NAMES = listOf("C", "D", "E", "F", "G", "A", "B")
        private val LETTER_PITCH = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        /** Parses "C", "F#", "Bb", "E♭♭" etc. */
        fun parse(text: String): Note {
            val letter = LETTER_NAMES.indexOf(text.take(1).uppercase())
            require(letter >= 0) { "Not a note: $text" }
            val acc = text.drop(1).sumOf { ch ->
                when (ch) {
                    '#', '♯' -> 1
                    'b', '♭' -> -1
                    else -> throw IllegalArgumentException("Not a note: $text")
                }
            }
            return Note(letter, acc)
        }

        /** Single-accidental spellings of a pitch class: [C] for 0, [C♯, D♭] for 1. */
        fun spellings(pitchClass: Int): List<Note> =
            (0..6).flatMap { letter ->
                (-1..1).map { acc -> Note(letter, acc) }
            }.filter { it.pitchClass == Math.floorMod(pitchClass, 12) }
                .let { all -> all.filter { it.accidental == 0 }.ifEmpty { all } }
                .sortedByDescending { it.accidental }

        /**
         * Picks the spelling of [pitchClass] whose resulting notes (root +
         * [intervals]) need the fewest accidentals — so pitch class 1 as a major
         * scale is D♭ (5 flats, not 7 sharps) but as a minor scale is C♯.
         */
        fun bestSpelling(pitchClass: Int, intervals: List<Interval>): Note =
            spellings(pitchClass).minWith(
                compareBy<Note> { root ->
                    intervals.sumOf { iv ->
                        val acc = Math.abs((root + iv).accidental)
                        if (acc > 1) acc + 5 else acc
                    }
                }.thenBy { root ->
                    // Tie-break: flats, except F♯ (F♯ vs G♭ major is a coin toss).
                    if (Math.floorMod(pitchClass, 12) == 6) -root.accidental else root.accidental
                }
            )
    }
}

fun accidentalSymbol(accidental: Int): String = when {
    accidental > 0 -> "♯".repeat(accidental)
    accidental < 0 -> "♭".repeat(-accidental)
    else -> ""
}
