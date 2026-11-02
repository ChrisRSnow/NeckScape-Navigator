package com.crsnow.neckscape.theory

data class ChordType(
    /** Suffix after the root: "" for major, "m7", "7♯9" … */
    val symbol: String,
    val name: String,
    val group: String,
    val intervals: List<Interval>,
) {
    fun notes(root: Note): List<Note> = intervals.map { root + it }

    fun displayName(root: Note): String = root.name + symbol

    /** Pitch classes relative to the root, octave-folded. */
    val pitchSet: Set<Int> = intervals.map { it.semitones % 12 }.toSet()
}

object Chords {
    /** Hand-picked and grouped; these win over tonal's entries for the same chord. */
    private val curated: List<ChordType> = buildList {
        fun add(group: String, symbol: String, name: String, intervals: String) =
            add(ChordType(symbol, name, group, Interval.parseList(intervals)))

        "Triads".let {
            add(it, "", "major", "1 3 5")
            add(it, "m", "minor", "1 b3 5")
            add(it, "dim", "diminished", "1 b3 b5")
            add(it, "aug", "augmented", "1 3 #5")
            add(it, "(♭5)", "major flat five", "1 3 b5")
            add(it, "sus2", "suspended second", "1 2 5")
            add(it, "sus4", "suspended fourth", "1 4 5")
            add(it, "5", "power chord", "1 5")
        }
        "Sixths & added".let {
            add(it, "6", "major sixth", "1 3 5 6")
            add(it, "m6", "minor sixth", "1 b3 5 6")
            add(it, "6/9", "six nine", "1 3 5 6 9")
            add(it, "m6/9", "minor six nine", "1 b3 5 6 9")
            add(it, "add9", "added ninth", "1 3 5 9")
            add(it, "m(add9)", "minor added ninth", "1 b3 5 9")
            add(it, "add11", "added eleventh", "1 3 5 11")
            add(it, "m(add11)", "minor added eleventh", "1 b3 5 11")
        }
        "Sevenths".let {
            add(it, "maj7", "major seventh", "1 3 5 7")
            add(it, "7", "dominant seventh", "1 3 5 b7")
            add(it, "m7", "minor seventh", "1 b3 5 b7")
            add(it, "m(maj7)", "minor major seventh", "1 b3 5 7")
            add(it, "m7♭5", "half-diminished", "1 b3 b5 b7")
            add(it, "dim7", "diminished seventh", "1 b3 b5 bb7")
            add(it, "7♯5", "augmented seventh", "1 3 #5 b7")
            add(it, "maj7♯5", "augmented major seventh", "1 3 #5 7")
            add(it, "7♭5", "dominant seventh flat five", "1 3 b5 b7")
            add(it, "maj7♭5", "major seventh flat five", "1 3 b5 7")
            add(it, "7sus4", "dominant seventh suspended fourth", "1 4 5 b7")
            add(it, "7sus2", "dominant seventh suspended second", "1 2 5 b7")
        }
        "Ninths".let {
            add(it, "maj9", "major ninth", "1 3 5 7 9")
            add(it, "9", "dominant ninth", "1 3 5 b7 9")
            add(it, "m9", "minor ninth", "1 b3 5 b7 9")
            add(it, "m(maj9)", "minor major ninth", "1 b3 5 7 9")
            add(it, "m9♭5", "half-diminished ninth", "1 b3 b5 b7 9")
            add(it, "9sus4", "ninth suspended fourth", "1 4 5 b7 9")
            add(it, "9♯5", "augmented ninth", "1 3 #5 b7 9")
            add(it, "9♭5", "ninth flat five", "1 3 b5 b7 9")
        }
        "Elevenths".let {
            add(it, "11", "dominant eleventh", "1 3 5 b7 9 11")
            add(it, "m11", "minor eleventh", "1 b3 5 b7 9 11")
            add(it, "m7(add11)", "minor seventh added eleventh", "1 b3 5 b7 11")
            add(it, "maj7♯11", "major seventh sharp eleven", "1 3 5 7 #11")
            add(it, "maj9♯11", "major ninth sharp eleven", "1 3 5 7 9 #11")
            add(it, "7♯11", "dominant seventh sharp eleven", "1 3 5 b7 #11")
            add(it, "9♯11", "dominant ninth sharp eleven", "1 3 5 b7 9 #11")
        }
        "Thirteenths".let {
            add(it, "13", "dominant thirteenth", "1 3 5 b7 9 13")
            add(it, "maj13", "major thirteenth", "1 3 5 7 9 13")
            add(it, "m13", "minor thirteenth", "1 b3 5 b7 9 13")
            add(it, "13sus4", "thirteenth suspended fourth", "1 4 5 b7 9 13")
            add(it, "13♯11", "thirteenth sharp eleven", "1 3 b7 9 #11 13")
            add(it, "13♭9", "thirteenth flat nine", "1 3 5 b7 b9 13")
            add(it, "7♭13", "dominant seventh flat thirteen", "1 3 5 b7 b13")
        }
        "Altered dominants".let {
            add(it, "7♭9", "seventh flat nine", "1 3 5 b7 b9")
            add(it, "7♯9", "seventh sharp nine", "1 3 5 b7 #9")
            add(it, "7♯5♯9", "seventh sharp five sharp nine", "1 3 #5 b7 #9")
            add(it, "7♯5♭9", "seventh sharp five flat nine", "1 3 #5 b7 b9")
            add(it, "7♭5♯9", "seventh flat five sharp nine", "1 3 b5 b7 #9")
            add(it, "7♭5♭9", "seventh flat five flat nine", "1 3 b5 b7 b9")
            add(it, "7♭9♭13", "seventh flat nine flat thirteen", "1 3 5 b7 b9 b13")
            add(it, "7alt", "altered dominant", "1 3 b7 b9 #9 #11 b13")
        }
    }

    /** Everything else in tonal's chord dictionary (see tools/import_tonal.py). */
    private val tonalExtras: List<ChordType> = TonalData.chords
        .map { (symbol, name, intervals) ->
            val ivs = Interval.parseList(intervals)
            ChordType(symbol, name.ifBlank { ivs.joinToString(" ") { it.label } }, "More (tonal dictionary)", ivs)
        }
        .filter { extra -> curated.none { it.pitchSet == extra.pitchSet || it.symbol == extra.symbol } }
        .distinctBy { it.pitchSet }
        .distinctBy { it.symbol }

    val all: List<ChordType> = curated + tonalExtras

    /** The first chord type whose root-relative pitch set is exactly [pitchSet]. */
    fun identify(pitchSet: Set<Int>): ChordType? = all.firstOrNull { it.pitchSet == pitchSet }
}
