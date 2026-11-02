package com.crsnow.neckscape.theory

data class Scale(val name: String, val intervals: List<Interval>) {
    fun notes(root: Note): List<Note> = intervals.map { root + it }

    val isHeptatonic: Boolean get() = intervals.size == 7

    /** Pitch classes relative to the root, octave-folded. */
    val pitchSet: Set<Int> get() = intervals.map { it.semitones % 12 }.toSet()
}

/**
 * A parent scale and its modes, or a loose group of related scales. When
 * [modal], scale i+k is scale i started from its (k+1)th degree.
 */
data class ScaleFamily(val name: String, val scales: List<Scale>, val modal: Boolean = false)

object Scales {
    val families: List<ScaleFamily> by lazy { curated + tonalExtras() }

    /** Scales from tonal's dictionary whose notes aren't already listed above (see tools/import_tonal.py). */
    private fun tonalExtras(): ScaleFamily {
        val known = curated.flatMap { it.scales }.map { s -> s.intervals.map { it.semitones % 12 }.toSet() }.toSet()
        val extras = TonalData.scales
            .map { (name, intervals) -> Scale(name, Interval.parseList(intervals)) }
            .filter { s -> s.intervals.map { it.semitones % 12 }.toSet() !in known }
            .distinctBy { s -> s.intervals.map { it.semitones % 12 }.toSet() }
        return ScaleFamily("More (tonal dictionary)", extras)
    }

    private val curated: List<ScaleFamily> = listOf(
        modesOf(
            "Major (diatonic)", "0 2 4 5 7 9 11",
            "Ionian (Major)", "Dorian", "Phrygian", "Lydian", "Mixolydian",
            "Aeolian (Natural Minor)", "Locrian",
        ),
        modesOf(
            "Melodic Minor", "0 2 3 5 7 9 11",
            "Melodic Minor (Jazz Minor)", "Dorian ♭2", "Lydian Augmented", "Lydian Dominant",
            "Mixolydian ♭6", "Locrian ♮2", "Altered (Super Locrian)",
        ),
        modesOf(
            "Harmonic Minor", "0 2 3 5 7 8 11",
            "Harmonic Minor", "Locrian ♮6", "Ionian ♯5", "Dorian ♯4", "Phrygian Dominant",
            "Lydian ♯2", "Ultralocrian",
        ),
        modesOf(
            "Harmonic Major", "0 2 4 5 7 8 11",
            "Harmonic Major", "Dorian ♭5", "Phrygian ♭4", "Lydian ♭3", "Mixolydian ♭2",
            "Lydian Augmented ♯2", "Locrian ♭♭7",
        ),
        modesOf(
            "Double Harmonic", "0 1 4 5 7 8 11",
            "Double Harmonic Major", "Lydian ♯2 ♯6", "Ultraphrygian", "Hungarian Minor",
            "Oriental", "Ionian ♯2 ♯5", "Locrian ♭♭3 ♭♭7",
        ),
        modesOf(
            "Pentatonic", "0 2 4 7 9",
            "Major Pentatonic", "Suspended Pentatonic (Egyptian)", "Blues Minor (Man Gong)",
            "Blues Major (Ritsusen)", "Minor Pentatonic",
        ),
        group(
            "Blues",
            "Minor Blues" to "0 3 5 6 7 10",
            "Major Blues" to "0 2 3 4 7 9",
        ),
        group(
            "Bebop",
            "Bebop Dominant" to "0 2 4 5 7 9 10 11",
            "Bebop Major" to "0 2 4 5 7 8 9 11",
            "Bebop Dorian" to "0 2 3 4 5 7 9 10",
            "Bebop Melodic Minor" to "0 2 3 5 7 8 9 11",
        ),
        group(
            "Symmetric",
            "Whole Tone" to "0 2 4 6 8 10",
            "Diminished (Half-Whole)" to "0 1 3 4 6 7 9 10",
            "Diminished (Whole-Half)" to "0 2 3 5 6 8 9 11",
            "Augmented" to "0 3 4 7 8 11",
            "Chromatic" to "0 1 2 3 4 5 6 7 8 9 10 11",
        ),
        group(
            "World & Exotic",
            "Hirajoshi" to "0 2 3 7 8",
            "In Sen" to "0 1 5 7 10",
            "Iwato" to "0 1 5 6 10",
            "Hungarian Major" to "0 3 4 6 7 9 10",
            "Neapolitan Major" to "0 1 3 5 7 9 11",
            "Neapolitan Minor" to "0 1 3 5 7 8 11",
            "Persian" to "0 1 4 5 6 8 11",
            "Enigmatic" to "0 1 4 6 8 10 11",
            "Prometheus" to "0 2 4 6 9 10",
        ),
    )
}

/** Every mode of a parent scale, by rotation. */
private fun modesOf(family: String, semitones: String, vararg modeNames: String): ScaleFamily {
    val parent = semitones.split(" ").map(String::toInt)
    require(parent.size == modeNames.size) { "$family: ${parent.size} notes, ${modeNames.size} names" }
    val scales = parent.indices.map { k ->
        val rotated = parent.indices.map { i -> Math.floorMod(parent[(k + i) % parent.size] - parent[k], 12) }
        Scale(modeNames[k], spell(rotated))
    }
    return ScaleFamily(family, scales, modal = true)
}

private fun group(family: String, vararg scales: Pair<String, String>) =
    ScaleFamily(family, scales.map { (name, semis) -> Scale(name, spell(semis.split(" ").map(String::toInt))) })

/**
 * Assigns a degree to each semitone offset. A seven-note scale gets one letter
 * per note, which is always the correct spelling; for other sizes there is no
 * single right answer, so use the conventional names.
 */
internal fun spell(semitones: List<Int>): List<Interval> =
    if (semitones.size == 7) {
        semitones.mapIndexed { i, s -> Interval(s, i + 1) }
    } else {
        semitones.map { s ->
            val degree = when (s) {
                0 -> 1
                1, 2 -> 2
                3, 4 -> 3
                5 -> 4
                6 -> if (5 in semitones) 5 else 4 // blues ♭5 vs whole-tone ♯4
                7 -> 5
                8 -> if (7 in semitones) 6 else 5 // ♭6 vs augmented ♯5
                9 -> 6
                else -> 7
            }
            Interval(s, degree)
        }
    }
