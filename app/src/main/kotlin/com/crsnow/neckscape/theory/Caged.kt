package com.crsnow.neckscape.theory

/**
 * The CAGED system: the five open chord shapes C, A, G, E and D, moved up the
 * neck, tile it in that order for any key. Each shape anchors a position: the
 * chord shape itself, and the scale notes in the frets around it. Defined for
 * standard tuning, where the shapes are what guitarists learn.
 *
 * [major] and [minor] are the open shapes (lowest string first); [lo]..[hi] is
 * the window of frets, relative to the shape's moved "nut", that its scale
 * position covers.
 */
enum class CagedShape(val rootPc: Int, val major: List<Int?>, val minor: List<Int?>, val lo: Int, val hi: Int) {
    C(0, listOf(null, 3, 2, 0, 1, 0), listOf(null, 3, 1, 0, 1, null), 0, 3),
    A(9, listOf(null, 0, 2, 2, 2, 0), listOf(null, 0, 2, 2, 1, 0), -1, 2),
    G(7, listOf(3, 2, 0, 0, 0, 3), listOf(3, 1, 0, 0, 3, 3), 0, 3),
    E(4, listOf(0, 2, 2, 1, 0, 0), listOf(0, 2, 2, 0, 0, 0), -1, 2),
    D(2, listOf(null, null, 0, 2, 3, 2), listOf(null, null, 0, 2, 3, 1), 0, 3),
}

/** One shape placed for a key: [offset] frets up from its open form, covering [frets]. */
data class CagedPosition(val shape: CagedShape, val offset: Int, val frets: IntRange, val voicing: Voicing)

/** Whether CAGED applies: six strings tuned E A D G B E (a capo is fine, it's relative). */
fun Tuning.isStandardShape(): Boolean = midi.zipWithNext { a, b -> b - a } == listOf(5, 5, 5, 4, 5)

/** Minor-third (no major third) content picks the minor shapes. */
fun isMinorQuality(intervals: List<Interval>): Boolean {
    val semis = intervals.map { it.semitones % 12 }.toSet()
    return 3 in semis && 4 !in semis
}

/**
 * The CAGED shapes a chord's triad takes: minor or major, and how far its fifth
 * moves from them, -1 for diminished (B° in C major) or +1 for augmented.
 */
fun cagedQuality(intervals: List<Interval>): Pair<Boolean, Int> {
    val semis = intervals.map { it.semitones % 12 }.toSet()
    val minor = 3 in semis && 4 !in semis
    val fifth = when {
        7 in semis -> 0
        minor && 6 in semis -> -1
        !minor && 8 in semis -> 1
        else -> 0
    }
    return minor to fifth
}

/** Open-string pitch classes of standard tuning, low E first, which the shapes are written for. */
private val STANDARD = listOf(4, 9, 2, 7, 11, 4)

/**
 * Every CAGED position for a root, up the neck in order: in C major, the C
 * shape at the nut, then A (fret 3), G (5), E (8), D (10), C again (12) …
 * [fifth] moves each shape's fifths that many frets (see [cagedQuality]); a
 * fifth flattened below the nut drops out of the shape.
 */
fun cagedPositions(rootPc: Int, minor: Boolean, fretCount: Int, fifth: Int = 0): List<CagedPosition> {
    val out = ArrayList<CagedPosition>()
    for (shape in CagedShape.entries) {
        // Open shapes stay at the nut; their window just stops at fret 0.
        var t = Math.floorMod(rootPc - shape.rootPc, 12)
        while (t + shape.hi <= fretCount + 1) {
            val open = if (minor) shape.minor else shape.major
            val placed = open.mapIndexed { s, f ->
                if (f == null) null
                else if (fifth != 0 && Math.floorMod(STANDARD[s] + f - shape.rootPc, 12) == 7) (f + t + fifth).takeIf { it >= 0 }
                else f + t
            }.map { f -> f?.takeIf { it <= fretCount } }
            // At the top of the neck a shape can run past the last fret: keep what's playable,
            // as long as it's still the whole triad (root, third and fifth).
            val kept = open.indices.filter { placed[it] != null }.map { Math.floorMod(STANDARD[it] + open[it]!! - shape.rootPc, 12) }.toSet()
            if (0 !in kept || 7 !in kept || (3 !in kept && 4 !in kept)) { t += 12; continue }
            // A moved fifth can step a fret outside the window; the window still covers the whole shape.
            val sounding = placed.filterNotNull()
            val frets = minOf(t + shape.lo, sounding.min()).coerceAtLeast(0)..maxOf(t + shape.hi, sounding.max()).coerceAtMost(fretCount)
            out += CagedPosition(shape, t, frets, Voicing(placed))
            t += 12
        }
    }
    return out.sortedBy { it.offset + it.shape.lo }
}
