package com.crsnow.neckscape.theory

/** Open-string pitches as MIDI note numbers, lowest-pitched string first. */
data class Tuning(val name: String, val stringNames: List<String>, val midi: List<Int>) {
    val strings: Int get() = midi.size

    fun pitchClassAt(string: Int, fret: Int): Int = (midi[string] + fret) % 12

    /** Same strings with one retuned by [semitones]; names fall back to sharps. */
    fun retune(string: Int, semitones: Int, name: String = "Custom"): Tuning =
        fromMidi(name, midi.mapIndexed { i, m -> if (i == string) (m + semitones).coerceIn(12, 96) else m })

    companion object {
        fun fromMidi(name: String, midi: List<Int>) =
            Tuning(name, midi.map { Note.spellings(it % 12).first().name }, midi)

        /** "E2", "F♯3" … */
        fun midiName(midi: Int): String = Note.spellings(midi % 12).first().name + (midi / 12 - 1)

        /** "E2 A2 D3 G3 B3 E4" → MIDI 40 45 50 55 59 64. */
        fun parse(name: String, spec: String): Tuning {
            val parts = spec.trim().split(Regex("\\s+"))
            val notes = parts.map { Note.parse(it.dropLastWhile(Char::isDigit)) }
            val midi = parts.zip(notes).map { (part, note) ->
                val octave = part.takeLastWhile(Char::isDigit).toInt()
                12 * (octave + 1) + note.pitchClass
            }
            return Tuning(name, notes.map { it.name }, midi)
        }
    }
}

object Tunings {
    val all: List<Tuning> = listOf(
        Tuning.parse("Standard (EADGBE)", "E2 A2 D3 G3 B3 E4"),
        Tuning.parse("Drop D", "D2 A2 D3 G3 B3 E4"),
        Tuning.parse("Half-step down", "Eb2 Ab2 Db3 Gb3 Bb3 Eb4"),
        Tuning.parse("Drop C", "C2 G2 C3 F3 A3 D4"),
        Tuning.parse("DADGAD", "D2 A2 D3 G3 A3 D4"),
        Tuning.parse("Open G", "D2 G2 D3 G3 B3 D4"),
        Tuning.parse("Open D", "D2 A2 D3 F#3 A3 D4"),
        Tuning.parse("Open E", "E2 B2 E3 G#3 B3 E4"),
        Tuning.parse("7-string (BEADGBE)", "B1 E2 A2 D3 G3 B3 E4"),
        Tuning.parse("Bass (EADG)", "E1 A1 D2 G2"),
        Tuning.parse("Ukulele (GCEA)", "G4 C4 E4 A4"),
    )
}
