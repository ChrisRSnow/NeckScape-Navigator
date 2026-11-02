package com.crsnow.neckscape.theory

/** The list, then back down again without repeating the top: 1 2 3 → 1 2 3 2 1. */
fun <T> List<T>.thereAndBack(): List<T> = if (size > 1) this + dropLast(1).reversed() else this

/** A spot on the neck: string index (lowest-pitched first) and fret. */
data class Position(val string: Int, val fret: Int)

/**
 * The order you'd play [positions] going up in pitch: start on the lowest
 * string, play its notes in fret order, and move to the next string as soon as
 * that string can play the next note. Each pitch is played once, so duplicates
 * further up the neck are left out. Returns each played position's 1-based step.
 *
 * Within a 4–5 fret window this gives the familiar box-pattern order; across
 * the whole neck it gives one path that hugs the nut, then runs up the top string.
 */
fun playingOrder(tuning: Tuning, positions: Collection<Position>): Map<Position, Int> {
    fun midi(p: Position) = tuning.midi[p.string] + p.fret

    // Sorting by open pitch, not index, keeps re-entrant tunings (ukulele) sensible.
    val strings = positions.groupBy { it.string }
        .mapValues { (_, ps) -> ps.sortedBy { it.fret } }
        .entries.sortedBy { tuning.midi[it.key] }
        .map { it.value }

    val order = LinkedHashMap<Position, Int>()
    var last = Int.MIN_VALUE
    strings.forEachIndexed { i, notes ->
        val next = strings.getOrNull(i + 1).orEmpty()
        for (p in notes) {
            val m = midi(p)
            if (m <= last) continue
            // Hand over once the next string reaches this pitch (or anything between).
            if (next.any { midi(it) in (last + 1)..m }) break
            order[p] = order.size + 1
            last = m
        }
    }
    return order
}
