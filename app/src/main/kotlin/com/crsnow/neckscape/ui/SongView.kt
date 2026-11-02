package com.crsnow.neckscape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.crsnow.neckscape.notation.LegendEntry
import com.crsnow.neckscape.notation.Mark
import com.crsnow.neckscape.notation.Music
import com.crsnow.neckscape.notation.PrintDot
import com.crsnow.neckscape.notation.SheetEvent
import com.crsnow.neckscape.song.Midi
import com.crsnow.neckscape.song.MidiNote
import com.crsnow.neckscape.song.Progression
import com.crsnow.neckscape.song.ScaleOverlay
import com.crsnow.neckscape.song.Song
import com.crsnow.neckscape.song.Tab
import com.crsnow.neckscape.song.TimeSignature
import com.crsnow.neckscape.song.position
import com.crsnow.neckscape.song.positions
import com.crsnow.neckscape.song.voicingFor
import com.crsnow.neckscape.theory.ChordSymbol
import com.crsnow.neckscape.theory.Interval
import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Scales
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.thereAndBack

/** Tab notes have no scale degree to colour by, so they share one colour. */
private val TabBlue = Color(0xFF1E88E5)
/** The ring around notes marked by highlight_notes. */
val HighlightPink = Color(0xFFD81B60)

/** How to spell each pitch class in a song: from its key if it has one, else sharps. */
fun songSpelling(song: Song?): Map<Int, Note> {
    val key = (song as? Progression)?.key
    val scale = key?.let { k -> Scales.families[0].scales[if (k.mode.lowercase().startsWith("min")) 5 else 0] }
    val inKey = scale?.intervals?.map { key.tonic + it }.orEmpty()
    val flats = inKey.any { it.accidental < 0 }
    return (0..11).associateWith { pc ->
        inKey.firstOrNull { it.pitchClass == pc } ?: Note.spellings(pc).let { if (flats) it.last() else it.first() }
    }
}

/** The chord symbol's interval for each pitch class it contains (bass note as a plain "B"). */
private fun chordMarks(symbol: ChordSymbol, labels: LabelMode): Map<Int, NoteMark> {
    val out = HashMap<Int, NoteMark>()
    for (iv in symbol.type.intervals) {
        val note = symbol.root + iv
        out[note.pitchClass] = NoteMark(if (labels == LabelMode.Notes) note.name else iv.label, degreeColor(iv))
    }
    symbol.bass?.let { b -> out.putIfAbsent(b.pitchClass, NoteMark(if (labels == LabelMode.Notes) b.name else "B", Color(0xFF6D4C41))) }
    return out
}

/** What the neck shows in the Song view: the current chord's shape, or the current measure's notes. */
fun songDots(state: UiState): Pair<List<Dot>, Set<Int>> {
    val tuning = state.tuning
    return when (val song = state.song) {
        is Progression -> {
            val symbol = state.songChord ?: return emptyList<Dot>() to emptySet()
            val parsed = ChordSymbol.parse(symbol) ?: return emptyList<Dot>() to emptySet()
            val v = song.voicingFor(symbol, tuning, state.fretCount) ?: return emptyList<Dot>() to emptySet()
            val marks = chordMarks(parsed, state.labelMode)
            val dots = v.positions().map { p ->
                Dot(p, marks[tuning.pitchClassAt(p.string, p.fret)] ?: NoteMark("?", Color.Gray))
            }
            dots to v.frets.withIndex().filter { it.value == null }.map { it.index }.toSet()
        }
        is Tab -> {
            val spelling = songSpelling(song)
            val measure = song.measures.getOrNull(state.songBar) ?: return emptyList<Dot>() to emptySet()
            val dots = ArrayList<Dot>()
            measure.events.forEachIndexed { i, e ->
                for (n in e.notes) {
                    val p = n.position(tuning) ?: continue
                    if (dots.any { it.position == p }) continue
                    val label = if (state.labelMode == LabelMode.Notes) spelling.getValue(tuning.pitchClassAt(p.string, p.fret)).name else "${p.fret}"
                    dots += Dot(p, NoteMark(label, TabBlue), step = if (state.showOrder) i + 1 else null)
                }
            }
            dots to emptySet()
        }
        null -> emptyList<Dot>() to emptySet()
    }
}

/**
 * A scale overlay as dots: faint under other content (roots and the named
 * chord's tones a little stronger), or full dots when it's all that's showing.
 */
fun overlayDots(state: UiState, overlay: ScaleOverlay, alone: Boolean): List<Dot> {
    val tuning = state.tuning
    val frets = overlay.fretRange ?: state.fretRange
    val chordTones = overlay.chordTonesOf?.let { ChordSymbol.parse(it) }?.pitchClasses.orEmpty()
    val degree = overlay.intervals.associateBy { (overlay.root.pitchClass + it) % 12 }
    val named = overlay.scale?.let { (f, s) -> Scales.families[f].scales[s].intervals }
    return state.stringRange.flatMap { s -> frets.map { f -> Position(s, f) } }.mapNotNull { p ->
        val pc = tuning.pitchClassAt(p.string, p.fret)
        val semis = degree[pc] ?: return@mapNotNull null
        val iv = named?.firstOrNull { it.semitones % 12 == semis } ?: Interval(semis, 1)
        val label = if (state.labelMode == LabelMode.Notes) (overlay.root + iv).name else iv.label
        val strong = (overlay.highlightRoots && semis == 0) || pc in chordTones
        Dot(p, NoteMark(label, degreeColor(iv)), faded = !alone, emphasis = strong)
    }
}

/** Where the song came from (AI-generated content is labelled "check by ear"), its attribution and capo. */
@Composable
fun SongHeader(state: UiState) {
    val song = state.song
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        song?.source?.let { src ->
            val (bg, fg) = when (src.kind) {
                "ai_generated" -> Color(0xFFFFE0B2) to Color(0xFF7A4100)
                "online" -> Color(0xFFBBDEFB) to Color(0xFF0D47A1)
                else -> Color(0xFFE0E0E0) to Color(0xFF424242)
            }
            Text(
                src.label,
                color = fg,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        song?.attribution?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.capo > 0) Text("Capo ${state.capo}", style = MaterialTheme.typography.labelLarge)
    }
}

/** A progression's sections as rows of bars, or a tab's measure stepper. */
@Composable
fun SongBars(state: UiState, onSelect: (Int) -> Unit) {
    when (val song = state.song) {
        is Progression -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var index = 0
            for (section in song.sections) {
                Text(section.name + if (section.repeat > 1) "  ×${section.repeat}" else "", style = MaterialTheme.typography.titleSmall)
                val start = index
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    section.bars.forEachIndexed { i, bar ->
                        val bi = start + i
                        FilterChip(
                            selected = state.songBar == bi,
                            onClick = { onSelect(bi) },
                            label = { Text(bar.joinToString("  ").ifEmpty { "—" }) },
                        )
                    }
                }
                index += section.bars.size * section.repeat
            }
        }
        is Tab -> Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onSelect(state.songBar - 1) }, enabled = state.songBar > 0) { Text("‹") }
            val marker = song.measures.take(state.songBar + 1).lastOrNull { it.marker != null }?.marker
            Text(
                "Measure ${state.songBar + 1} of ${song.measures.size}" + (marker?.let { "  ·  $it" } ?: ""),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = { onSelect(state.songBar + 1) }, enabled = state.songBar < song.measures.size - 1) { Text("›") }
        }
        null -> Text(
            if (state.overlay != null) "Scale overlay" else "Nothing loaded. Open a file, or ask your AI to send a progression or tab.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The song for the engraver and the PDF: chords as block chords with symbols, tab events with technique marks. */
fun musicForSong(state: UiState, dots: List<Dot>): Music {
    val song = state.song
    val tuning = state.tuning
    val events = ArrayList<SheetEvent>()
    val boxes = LinkedHashMap<String, Voicing>()
    when (song) {
        is Progression -> song.flatBars.forEachIndexed { b, (_, chords) ->
            chords.forEachIndexed { i, symbol ->
                val v = song.voicingFor(symbol, tuning, state.fretCount)
                if (v != null) boxes.putIfAbsent(symbol, v)
                events += SheetEvent(v?.positions()?.toList().orEmpty(), whole = true, label = symbol, barAfter = i == chords.lastIndex, group = b)
            }
        }
        is Tab -> song.measures.forEachIndexed { m, measure ->
            measure.events.forEachIndexed { i, e ->
                events += SheetEvent(
                    positions = e.notes.mapNotNull { it.position(tuning) },
                    whole = false,
                    label = if (i == 0) measure.marker else null,
                    barAfter = i == measure.events.lastIndex,
                    mark = e.notes.mapNotNull { it.technique?.mark }.distinct().joinToString(" ").ifEmpty { null },
                    group = m,
                )
            }
        }
        null -> {}
    }
    val current = state.songChord?.let { ChordSymbol.parse(it) }
    return Music(
        title = song?.title ?: state.title,
        subtitle = listOfNotNull(
            song?.attribution,
            song?.source?.label,
            "${state.baseTuning.name} tuning (${state.baseTuning.stringNames.joinToString(" ")})",
            if (state.capo > 0) "capo ${state.capo}" else null,
            song?.timeSignature?.toString(),
        ).joinToString("  ·  "),
        tuning = tuning,
        spelling = songSpelling(song),
        legend = current?.let { c -> c.type.intervals.map { iv -> LegendEntry((c.root + iv).name, iv.label, degreeColor(iv).toArgb()) } }.orEmpty(),
        frets = state.fretRange,
        strings = state.stringRange,
        dots = dots.filter { !it.faded }.map { PrintDot(it.position, Mark(it.mark.label, it.mark.color.toArgb()), it.step) },
        shapes = boxes.values.toList(),
        shapeNames = boxes.keys.toList(),
        chordName = state.songChord ?: "",
        events = events,
    )
}

/**
 * What's showing as a standard MIDI file: a scale or arpeggio up and down at
 * the tempo slider's speed, chord shapes strummed a bar each, a progression
 * strummed on the beat, or a tab's notes in rhythm.
 */
fun midiFor(state: UiState, sequence: List<Position>, shapes: List<Voicing>): ByteArray {
    val t = state.tuning
    fun midi(p: Position) = t.midi[p.string] + p.fret
    fun strum(v: Voicing, at: Double, length: Double) =
        v.positions().map(::midi).sorted().mapIndexed { i, m -> MidiNote(at + i * 0.04, length - i * 0.04, m) }
    val four = TimeSignature(4, 4)
    return when (state.viewMode) {
        ViewMode.Scale, ViewMode.Arpeggio ->
            Midi.write(sequence.thereAndBack().mapIndexed { i, p -> MidiNote(i.toDouble(), 1.0, midi(p)) }, state.title, state.tempo.toDouble(), four)
        ViewMode.Chord -> {
            val chosen = shapes.getOrNull(state.voicingIndex)?.let { listOf(it) } ?: shapes
            Midi.write(chosen.flatMapIndexed { i, v -> strum(v, i * 4.0, 4.0) }, state.title, 80.0, four)
        }
        ViewMode.Song -> when (val song = state.song) {
            is Progression -> {
                val ts = song.timeSignature
                val notes = ArrayList<MidiNote>()
                val markers = ArrayList<Pair<Double, String>>()
                var lastSection: Any? = null
                song.flatBars.forEachIndexed { b, (section, chords) ->
                    val barStart = b * ts.quarters
                    if (section !== lastSection) { markers += barStart to section.name; lastSection = section }
                    val share = ts.quarters / chords.size.coerceAtLeast(1)
                    chords.forEachIndexed { i, symbol ->
                        val v = song.voicingFor(symbol, t, state.fretCount) ?: return@forEachIndexed
                        var beat = 0.0
                        while (beat < share - 1e-6) {
                            notes += strum(v, barStart + i * share + beat, minOf(ts.pulse, share - beat))
                            beat += ts.pulse
                        }
                    }
                }
                Midi.write(notes, song.title, (song.tempoBpm ?: 90.0) * ts.pulse, ts, markers)
            }
            is Tab -> {
                val ts = song.timeSignature
                val notes = ArrayList<MidiNote>()
                val markers = ArrayList<Pair<Double, String>>()
                song.measures.forEachIndexed { m, measure ->
                    val start = m * ts.quarters
                    measure.marker?.let { markers += start to it }
                    for (e in measure.events) for (n in e.notes) {
                        val p = n.position(t) ?: continue
                        notes += MidiNote(start + e.at, e.dur ?: 1.0, midi(p))
                    }
                }
                Midi.write(notes, song.title, (song.tempoBpm ?: 100.0) * ts.pulse, ts, markers)
            }
            null -> throw IllegalStateException("Nothing to export — load a progression or tab first")
        }
    }
}
