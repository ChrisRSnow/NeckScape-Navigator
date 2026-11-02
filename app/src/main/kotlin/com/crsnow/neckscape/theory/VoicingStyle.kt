package com.crsnow.neckscape.theory

import kotlin.math.abs

/**
 * Picks a shape for [symbol] in a voicing style: "open" (near the nut, open
 * strings), "barre" (every string, nothing open), "position_N" (nearest fret
 * N) or "drop2" (four adjacent strings, no open strings). Slash chords look for
 * a shape with that bass note first.
 */
fun pickVoicing(tuning: Tuning, symbol: ChordSymbol, style: String, fretCount: Int): Voicing? {
    val bass = symbol.bass
    val shapes = if (bass != null) {
        VoicingFinder.find(tuning, symbol.root, symbol.type, fretCount, allowInversions = true)
            .filter { v -> lowestPc(tuning, v) == bass.pitchClass }
            .ifEmpty { VoicingFinder.find(tuning, symbol.root, symbol.type, fretCount) }
    } else {
        VoicingFinder.find(tuning, symbol.root, symbol.type, fretCount)
    }
    if (shapes.isEmpty()) return null
    fun Voicing.hasOpen() = frets.any { it == 0 }
    fun Voicing.sounding() = frets.count { it != null }
    return when {
        style == "open" -> shapes.filter { it.position <= 3 && it.hasOpen() }.minByOrNull { it.position }
            ?: shapes.minBy { it.position }
        style == "barre" -> shapes.filter { !it.hasOpen() && it.sounding() == tuning.strings }.minByOrNull { it.position }
            ?: shapes.filter { !it.hasOpen() }.minByOrNull { it.position } ?: shapes.first()
        style.startsWith("position_") -> {
            val n = style.removePrefix("position_").toIntOrNull() ?: 0
            shapes.filter { n <= 1 || !it.hasOpen() }.ifEmpty { shapes }.minBy { abs(it.position - n) }
        }
        style == "drop2" -> shapes.filter { v ->
            val used = v.frets.withIndex().filter { it.value != null }.map { it.index }
            used.size == 4 && used.last() - used.first() == 3 && !v.hasOpen()
        }.minByOrNull { abs(it.position - 5) } ?: shapes.filter { it.sounding() == 4 }.minByOrNull { it.position } ?: shapes.first()
        else -> shapes.first()
    }
}

private fun lowestPc(tuning: Tuning, v: Voicing): Int? =
    v.frets.withIndex().filter { it.value != null }.minByOrNull { tuning.midi[it.index] + it.value!! }
        ?.let { (tuning.midi[it.index] + it.value!!) % 12 }

/** The same strings with a capo at [capo]: fret 0 is now the capo. */
fun Tuning.withCapo(capo: Int): Tuning =
    if (capo == 0) this else Tuning.fromMidi("$name, capo $capo", midi.map { it + capo })
