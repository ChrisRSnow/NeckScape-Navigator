package com.crsnow.neckscape.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import com.crsnow.neckscape.notation.A4_WIDTH
import com.crsnow.neckscape.notation.Block
import com.crsnow.neckscape.notation.Engraver
import com.crsnow.neckscape.notation.LegendEntry
import com.crsnow.neckscape.notation.Mark
import com.crsnow.neckscape.notation.Music
import com.crsnow.neckscape.notation.Pdf
import com.crsnow.neckscape.notation.PrintDot
import com.crsnow.neckscape.notation.Staves
import com.crsnow.neckscape.notation.headerBlock
import com.crsnow.neckscape.notation.headingBlock
import com.crsnow.neckscape.notation.neckBlocks
import com.crsnow.neckscape.notation.paginate
import com.crsnow.neckscape.notation.shapeBlocks
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Voicing

/** Saves a file the user asked for: a save dialog on desktop, the document picker on Android. */
fun interface FileSaver {
    fun save(fileName: String, mimeType: String, bytes: ByteArray)
}

val LocalFileSaver = staticCompositionLocalOf<FileSaver?> { null }

/** Lets the user pick a file to open: a file dialog on desktop, the document picker on Android. */
fun interface FileOpener {
    fun open(onFile: (name: String, bytes: ByteArray) -> Unit)
}

val LocalFileOpener = staticCompositionLocalOf<FileOpener?> { null }

/** The local AI bridge, for the Settings dialog; null in previews and tests. */
val LocalBridge = staticCompositionLocalOf<com.crsnow.neckscape.bridge.BridgeServer?> { null }

/** What's on screen, for the engraver: [sequence] for scales/arpeggios, [shapes] for chords. */
fun musicFor(state: UiState, dots: List<Dot>, sequence: List<Position>, shapes: List<Voicing>): Music {
    val tuning = state.tuning
    val notes = state.intervals.map { state.root + it }
    val strings = state.stringRange
    fun stringNo(s: Int) = tuning.strings - s
    return Music(
        title = state.title.substringBefore(" — "),
        subtitle = listOf(
            state.title.substringAfter(" — ", "").takeIf { it.isNotEmpty() },
            "${tuning.name} tuning (${tuning.stringNames.joinToString(" ")})",
            "frets ${state.fretRange.first}–${state.fretRange.last}",
            "strings ${stringNo(strings.first)}–${stringNo(strings.last)}",
        ).filterNotNull().joinToString("  ·  "),
        tuning = tuning,
        spelling = notes.associateBy { it.pitchClass },
        legend = state.intervals.zip(notes).map { (iv, n) -> LegendEntry(n.name, iv.label, degreeColor(iv).toArgb()) },
        frets = state.fretRange,
        strings = strings,
        dots = dots.filter { !it.faded }.map { PrintDot(it.position, Mark(it.mark.label, it.mark.color.toArgb()), it.step) },
        sequence = if (state.viewMode == ViewMode.Chord) emptyList() else sequence,
        shapes = if (state.viewMode == ViewMode.Chord) shapes else emptyList(),
        chordName = state.chord.displayName(state.root),
        colors = state.intervals.zip(notes).associate { (iv, n) -> n.pitchClass to degreeColor(iv).toArgb() },
    )
}

/** Staff space for the on-screen score and tab, in dp: bigger on wide screens. */
fun screenStaffSpace(width: Float) = (width / 95f).coerceIn(7f, 12f)
private const val PDF_STAFF_SPACE = 6f
private const val PDF_MARGIN = 40f

/**
 * A printable PDF of [music]: the current [display], or with [everything]
 * the neck, chord shapes and score-with-tab together.
 */
fun pdfFor(music: Music, display: Display, everything: Boolean): ByteArray {
    val width = A4_WIDTH - 2 * PDF_MARGIN
    val blocks = mutableListOf(headerBlock(music, width))
    fun section(title: String, content: List<Block>) {
        if (content.isEmpty()) return
        blocks += headingBlock(title)
        blocks += content
    }
    if (everything || display == Display.Fretboard) {
        section("Fretboard", neckBlocks(music, width))
        section("Chord shapes", shapeBlocks(music, music.chordName, width))
    }
    val staves = when {
        everything -> Staves.Both
        display == Display.Score -> Staves.Score
        display == Display.Tab -> Staves.Tab
        else -> null
    }
    if (staves != null) {
        val title = when (staves) {
            Staves.Both -> "Score and tab"
            Staves.Score -> "Score"
            Staves.Tab -> "Tab"
        }
        section(title, Engraver(music, PDF_STAFF_SPACE).blocks(staves, width))
    }
    return Pdf.write(paginate(blocks, footer = "NeckScape-Navigator  ·  ${music.title}", margin = PDF_MARGIN), music.title)
}

/** "C Dorian - score.pdf", safe on every platform's file system. */
fun pdfFileName(music: Music, display: Display, everything: Boolean): String {
    val what = if (everything) "fretboard, score and tab" else display.name.lowercase()
    return "${music.title} - $what.pdf".replace(Regex("""[\\/:*?"<>|]"""), "-")
}
