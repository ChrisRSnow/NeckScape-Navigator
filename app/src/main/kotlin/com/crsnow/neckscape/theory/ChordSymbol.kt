package com.crsnow.neckscape.theory

/** A parsed chord symbol: "F♯m7♭5" → root F♯, half-diminished; "C/G" adds a bass note. */
data class ChordSymbol(val root: Note, val type: ChordType, val bass: Note? = null) {
    val name: String get() = type.displayName(root) + (bass?.let { "/${it.name}" } ?: "")

    /** Pitch classes the chord contains, bass included. */
    val pitchClasses: Set<Int>
        get() = type.intervals.map { (root.pitchClass + it.semitones) % 12 }.toSet() + setOfNotNull(bass?.pitchClass)

    companion object {
        /** Common ways of writing the same suffix, mapped to the catalogue's symbols. */
        private val ALIASES = listOf(
            "maj" to "", "M" to "", "major" to "", "min" to "m", "mi" to "m", "-" to "m", "minor" to "m",
            "M7" to "maj7", "Δ7" to "maj7", "△7" to "maj7", "Δ" to "maj7", "△" to "maj7", "ma7" to "maj7",
            "M9" to "maj9", "M13" to "maj13", "mM7" to "m(maj7)", "m(M7)" to "m(maj7)", "mmaj7" to "m(maj7)",
            "min7" to "m7", "-7" to "m7", "mi7" to "m7", "min9" to "m9", "-9" to "m9", "min11" to "m11", "min6" to "m6", "-6" to "m6",
            "°" to "dim", "o" to "dim", "°7" to "dim7", "o7" to "dim7", "ø" to "m7♭5", "ø7" to "m7♭5", "m7-5" to "m7♭5",
            "+" to "aug", "+7" to "7♯5", "7+" to "7♯5", "aug7" to "7♯5", "sus" to "sus4", "7sus" to "7sus4",
            "add2" to "add9", "2" to "sus2", "4" to "sus4", "madd9" to "m(add9)", "m(add9)" to "m(add9)",
            "69" to "6/9", "6add9" to "6/9", "m69" to "m6/9", "7alt" to "7alt", "alt" to "7alt", "5" to "5",
        )

        /** Parses "Am", "F#m7b5", "Bbmaj7", "C/G", "E7#9"; null if the root or suffix isn't recognised. */
        fun parse(text: String): ChordSymbol? {
            val t = text.trim()
            if (t.isEmpty() || t[0].uppercaseChar() !in "ABCDEFG") return null
            val slash = t.lastIndexOf('/').takeIf { it > 0 && t.substring(it + 1).let { b -> b.isNotEmpty() && b[0].uppercaseChar() in "ABCDEFG" && b.length <= 3 } }
            val head = if (slash != null) t.substring(0, slash) else t
            val bass = slash?.let { runCatching { Note.parse(t.substring(it + 1)) }.getOrNull() ?: return null }
            // Root: letter plus up to two accidentals ("Bb", "F#", "Ebb").
            var n = 1
            while (n < head.length && n < 3 && head[n] in "#♯b♭") n++
            val root = runCatching { Note.parse(head.substring(0, n)) }.getOrNull() ?: return null
            val type = suffix(head.substring(n)) ?: return null
            return ChordSymbol(root, type, bass)
        }

        private fun normalise(s: String) = s.replace('#', '♯').replace('b', '♭').replace(" ", "")

        private fun suffix(raw: String): ChordType? {
            // "C(add9)" is written both ways.
            val unwrapped = if (raw.startsWith("(") && raw.endsWith(")")) raw.substring(1, raw.length - 1) else raw
            for (candidate in listOf(raw, unwrapped).distinct()) {
                Chords.all.firstOrNull { it.symbol == candidate }?.let { return it }
                Chords.all.firstOrNull { it.symbol == normalise(candidate) }?.let { return it }
                ALIASES.firstOrNull { it.first == candidate }?.let { (_, sym) -> Chords.all.firstOrNull { it.symbol == sym }?.let { c -> return c } }
                // Alias prefix + alteration tail: "min7b5" → "m7♭5", "M7#11" → "maj7♯11".
                for ((alias, sym) in ALIASES.sortedByDescending { it.first.length }) {
                    if (alias.isNotEmpty() && candidate.startsWith(alias) && candidate.length > alias.length) {
                        val rebuilt = normalise(sym + candidate.substring(alias.length))
                        Chords.all.firstOrNull { it.symbol == rebuilt }?.let { return it }
                    }
                }
                // Parenthesised alterations: "7(b9)" → "7♭9".
                val flat = normalise(candidate).replace("(", "").replace(")", "")
                Chords.all.firstOrNull { it.symbol.replace("(", "").replace(")", "") == flat }?.let { return it }
            }
            return null
        }
    }
}

/** What a set of frets actually plays, named if it's a known chord: "F major 7", or the notes. */
fun describeVoicing(tuning: Tuning, frets: List<Int?>): String {
    val sounded = frets.withIndex().mapNotNull { (s, f) -> f?.let { tuning.midi[s] + it } }
    if (sounded.isEmpty()) return "nothing (all strings muted)"
    val pcs = sounded.map { it % 12 }.toSet()
    val bass = sounded.min() % 12
    // Try the bass note as root first, then the others.
    for (root in listOf(bass) + (pcs - bass)) {
        val rel = pcs.map { Math.floorMod(it - root, 12) }.toSet()
        Chords.identify(rel)?.let { type ->
            val note = Note.bestSpelling(root, type.intervals)
            val slash = if (root != bass) "/" + Note.spellings(bass).first().name else ""
            return "${note.name} ${type.name}$slash"
        }
    }
    return sounded.sorted().joinToString(" ") { Tuning.midiName(it) }
}
