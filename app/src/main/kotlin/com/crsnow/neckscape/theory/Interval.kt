package com.crsnow.neckscape.theory

/**
 * An interval above a root. [degree] is the scale step the interval is named
 * after (♭3 → 3, ♯11 → 11) and fixes which letter the resulting note gets.
 */
data class Interval(val semitones: Int, val degree: Int) {
    /** Degree folded into one octave: 9 → 2, 11 → 4, 13 → 6. */
    val simpleDegree: Int get() = (degree - 1) % 7 + 1

    /** "R", "♭3", "5", "♯11", "♭♭7" … */
    val label: String
        get() {
            if (degree == 1 && semitones == 0) return "R"
            val natural = NATURAL[simpleDegree - 1] + 12 * ((degree - 1) / 7)
            return accidentalSymbol(semitones - natural) + degree
        }

    override fun toString() = label

    companion object {
        private val NATURAL = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        /** Parses "1", "b3", "#11", "bb7", "♭9". */
        fun parse(text: String): Interval {
            val acc = text.takeWhile { it in "b#♭♯" }
            val degree = text.drop(acc.length).toInt()
            require(degree >= 1) { "Bad interval: $text" }
            val shift = acc.sumOf { if (it == '#' || it == '♯') 1 else -1 }
            val natural = NATURAL[(degree - 1) % 7] + 12 * ((degree - 1) / 7)
            return Interval(natural + shift, degree)
        }

        fun parseList(text: String): List<Interval> =
            text.trim().split(Regex("\\s+")).map(::parse)
    }
}
