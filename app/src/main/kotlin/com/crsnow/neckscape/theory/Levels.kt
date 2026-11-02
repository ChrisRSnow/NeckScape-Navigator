package com.crsnow.neckscape.theory

/**
 * How much of the catalogue to offer. Each level includes the ones below it:
 *  - Beginner: major and minor chords and arpeggios; major, natural minor and the two pentatonics.
 *  - Intermediate: adds extended chords (sixths, sevenths, ninths …, sus, dim, aug) and modes.
 *  - Advanced: everything, including altered dominants, exotic scales and the tonal dictionary.
 */
enum class Level { Beginner, Intermediate, Advanced }

private val BEGINNER_CHORDS = setOf("", "m")
private val INTERMEDIATE_TRIADS = setOf("dim", "aug", "sus2", "sus4", "5")
private val INTERMEDIATE_CHORD_GROUPS = setOf("Sixths & added", "Sevenths", "Ninths", "Elevenths", "Thirteenths")

private val BEGINNER_SCALES = setOf("Ionian (Major)", "Aeolian (Natural Minor)", "Major Pentatonic", "Minor Pentatonic")
private val INTERMEDIATE_FAMILIES = setOf("Major (diatonic)", "Pentatonic", "Blues")
private val INTERMEDIATE_SCALES = setOf("Harmonic Minor", "Melodic Minor (Jazz Minor)")

fun Level.allows(chord: ChordType): Boolean = when (this) {
    Level.Beginner -> chord.group == "Triads" && chord.symbol in BEGINNER_CHORDS
    Level.Intermediate -> Level.Beginner.allows(chord) ||
        (chord.group == "Triads" && chord.symbol in INTERMEDIATE_TRIADS) || chord.group in INTERMEDIATE_CHORD_GROUPS
    Level.Advanced -> true
}

fun Level.allows(family: ScaleFamily, scale: Scale): Boolean = when (this) {
    Level.Beginner -> scale.name in BEGINNER_SCALES
    Level.Intermediate -> Level.Beginner.allows(family, scale) ||
        family.name in INTERMEDIATE_FAMILIES || scale.name in INTERMEDIATE_SCALES
    Level.Advanced -> true
}

fun Level.allowsScale(family: Int, scale: Int): Boolean =
    Scales.families[family].let { allows(it, it.scales[scale]) }

/** Indices into [Chords.all] offered at this level. */
fun Level.chordIndices(): List<Int> = Chords.all.indices.filter { allows(Chords.all[it]) }

/** (family, scale) indices offered at this level, in catalogue order. */
fun Level.scaleIndices(): List<Pair<Int, Int>> = Scales.families.flatMapIndexed { f, family ->
    family.scales.indices.filter { allows(family, family.scales[it]) }.map { f to it }
}

/** How alike two pitch sets sound: shared notes, minus a little for each extra one. */
private fun likeness(a: Set<Int>, b: Set<Int>) = (a intersect b).size * 4 - (b - a).size

/** The allowed chord closest to [chord] (Cm7 → Cm at Beginner), or [chord] itself if allowed. */
fun Level.nearestChord(chord: ChordType): ChordType =
    if (allows(chord)) chord else chordIndices().map { Chords.all[it] }.maxBy { likeness(chord.pitchSet, it.pitchSet) }

/** The allowed scale closest to the one at ([family], [scale]). */
fun Level.nearestScale(family: Int, scale: Int): Pair<Int, Int> {
    if (allowsScale(family, scale)) return family to scale
    val current = Scales.families[family].scales[scale].pitchSet
    return scaleIndices().maxBy { (f, s) -> likeness(current, Scales.families[f].scales[s].pitchSet) }
}
