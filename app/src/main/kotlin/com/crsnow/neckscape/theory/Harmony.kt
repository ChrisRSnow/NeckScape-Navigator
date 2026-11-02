package com.crsnow.neckscape.theory

/** A chord built by stacking thirds on one degree of a scale. */
data class DiatonicChord(
    /** "I", "ii", "♭VII" … */
    val numeral: String,
    val root: Note,
    /** Null when the stack doesn't match any chord in [Chords.all]. */
    val type: ChordType?,
)

private val NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII")

fun ChordType.fitsIn(scale: Scale): Boolean = scale.pitchSet.containsAll(pitchSet)

/**
 * The scale's home chord: C Dorian → Cm (Cm7 with [sevenths]). Scales that
 * don't stack in thirds get the first listed chord that fits, if any.
 */
fun tonicChord(scale: Scale, sevenths: Boolean): ChordType? =
    diatonicChords(scale, Note(0), sevenths).firstOrNull()?.type
        ?: Chords.all.firstOrNull { it.pitchSet.size >= 3 && it.fitsIn(scale) }

/** Major-family modes in order of how often you'd reach for them: Ionian, Aeolian, Mixolydian … */
private val MAJOR_MODE_PREFERENCE = listOf(0, 5, 4, 1, 3, 2, 6)

/**
 * (family, scale) indices of a scale containing every note of [chord]. Tries
 * [preferFamily] first, then seven-note scales, then anything; only scales
 * passing [allowed] are considered.
 */
fun scaleFor(chord: ChordType, preferFamily: Int, allowed: (Int, Int) -> Boolean = { _, _ -> true }): Pair<Int, Int>? {
    val families = Scales.families
    fun ranked(family: Int) =
        (if (family == 0) MAJOR_MODE_PREFERENCE else families[family].scales.indices.toList()).map { family to it }
    val others = (families.indices - preferFamily).flatMap(::ranked)
        .sortedBy { (f, s) -> !families[f].scales[s].isHeptatonic }
    return (ranked(preferFamily) + others).firstOrNull { (f, s) -> allowed(f, s) && chord.fitsIn(families[f].scales[s]) }
}

/**
 * The triads (or seventh chords) of a seven-note scale, one per degree.
 * Returns an empty list for scales that don't harmonise in thirds.
 */
fun diatonicChords(scale: Scale, root: Note, sevenths: Boolean): List<DiatonicChord> {
    if (!scale.isHeptatonic) return emptyList()
    val semis = scale.intervals.map { it.semitones }
    return scale.intervals.mapIndexed { i, degree ->
        val stack = (0 until if (sevenths) 4 else 3).map { k -> semis[(i + 2 * k) % 7] }
        val pitchSet = stack.map { Math.floorMod(it - semis[i], 12) }.toSet()
        val type = Chords.identify(pitchSet)
        val minorish = 3 in pitchSet && 4 !in pitchSet
        val accidental = accidentalSymbol(degree.semitones - Interval.parse("${degree.degree}").semitones)
        val numeral = NUMERALS[degree.degree - 1].let { if (minorish) it.lowercase() else it }
        DiatonicChord(accidental + numeral, root + degree, type)
    }
}
