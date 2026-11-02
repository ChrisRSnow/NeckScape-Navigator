package com.crsnow.neckscape.notation

import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.thereAndBack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A note marker: its label (name or interval) and degree colour. */
data class Mark(val label: String, val color: Int)

/** A marked spot on the neck diagram; [step] is its playing-order number. */
data class PrintDot(val position: Position, val mark: Mark, val step: Int?)

/**
 * One thing in time for songs (progressions, tabs): its notes, a label above
 * (chord symbol, section), whether a barline follows, a technique mark for the
 * tab, and [group] (the bar it belongs to) for highlighting.
 */
data class SheetEvent(
    val positions: List<Position>,
    val whole: Boolean,
    val label: String? = null,
    val barAfter: Boolean = false,
    val mark: String? = null,
    val group: Int = 0,
)

/** One chip of the legend: note name, interval label, colour. */
data class LegendEntry(val note: String, val interval: String, val color: Int)

/**
 * What's on screen, in a form the engraver can lay out. Scales and arpeggios
 * have a [sequence] (ascending, in playing order); chords have [shapes].
 */
class Music(
    val title: String,
    val subtitle: String,
    val tuning: Tuning,
    /** Pitch class → how to spell it. */
    val spelling: Map<Int, Note>,
    val legend: List<LegendEntry>,
    val frets: IntRange,
    val strings: IntRange,
    val dots: List<PrintDot>,
    val sequence: List<Position> = emptyList(),
    val shapes: List<Voicing> = emptyList(),
    /** "Cmaj7": labels the chord boxes. */
    val chordName: String = "",
    /** A song's events (overrides [sequence] and [shapes]). */
    val events: List<SheetEvent> = emptyList(),
    /** Labels for each of [shapes] in the chord boxes (chord symbols for a song). */
    val shapeNames: List<String> = emptyList(),
    /** Pitch class → colour, for the chord boxes. */
    val colors: Map<Int, Int> = emptyMap(),
)

enum class Staves { Score, Tab, Both }

private class Clef(val glyph: Char, val transpose: Int, val bottomLine: Int, val originStep: Int)

/** A notehead: [step] counts staff positions up from the bottom line (0 = bottom line, 8 = top line). */
private class Head(val position: Position, val step: Int, val accidental: Int, var showAccidental: Int? = null)

/** One thing in time: a single note of a scale, or a whole chord shape. */
private class Event(val heads: List<Head>, val whole: Boolean, val label: String?, val group: Int, val barAfter: Boolean, val mark: String? = null) {
    var accColumns = 0
    val accColumn = HashMap<Head, Int>()
}

/**
 * Lays out standard notation and tab for [music] with staff space [sp].
 * Score and tab share their horizontal layout, so they line up when stacked.
 */
class Engraver(private val music: Music, private val sp: Float) {
    private val tuning = music.tuning
    private val m = Glyphs.music
    private val em = 4 * sp
    private val clef = clefFor(tuning)
    private val blackW = m.advance(Glyphs.NOTEHEAD_BLACK) * em
    private val wholeW = m.advance(Glyphs.NOTEHEAD_WHOLE) * em
    private val accW = 1.15f * sp
    private val tabGap = 1.8f * sp
    private val header = 4.2f * sp
    private val events: List<Event> = buildEvents().also(::chooseAccidentals)

    private fun buildEvents(): List<Event> = if (music.events.isNotEmpty()) {
        music.events.map { e ->
            Event(e.positions.map(::head).sortedBy { it.step }, e.whole, e.label, e.group, e.barAfter, e.mark)
        }
    } else if (music.sequence.isNotEmpty()) {
        music.sequence.thereAndBack().mapIndexed { i, p -> Event(listOf(head(p)), whole = false, label = null, group = i, barAfter = false) }
    } else {
        music.shapes.mapIndexed { i, v ->
            val heads = v.frets.withIndex().mapNotNull { (s, f) -> f?.let { head(Position(s, it)) } }
            Event(heads.sortedBy { it.step }, whole = true, label = "${i + 1}", group = i, barAfter = true)
        }
    }

    private fun clefFor(t: Tuning): Clef {
        val lowest = t.midi.min()
        return when {
            lowest >= 55 -> Clef(Glyphs.G_CLEF, 0, 30, 2)      // ukulele etc.: treble at pitch
            lowest >= 33 -> Clef(Glyphs.G_CLEF_8VB, 12, 30, 2) // guitar: written an octave up
            else -> Clef(Glyphs.F_CLEF_8VB, 12, 18, 6)         // bass
        }
    }

    private fun head(p: Position): Head {
        val midi = tuning.midi[p.string] + p.fret + clef.transpose
        val note = music.spelling[midi % 12] ?: Note.spellings(midi % 12).minBy { abs(it.accidental) }
        val natural = midi - note.accidental
        val diatonic = (Math.floorDiv(natural, 12) - 1) * 7 + note.letter
        return Head(p, diatonic - clef.bottomLine, note.accidental)
    }

    /**
     * No key signature, so every altered note shows its accidental; a natural
     * appears when the previous note on that line or space was altered. Chord
     * shapes are separated by barlines, so each stands alone.
     */
    private fun chooseAccidentals(events: List<Event>) {
        val last = HashMap<Int, Int>()
        for (e in events) {
            for (h in e.heads) {
                val before = last[h.step] ?: 0
                h.showAccidental = when {
                    h.accidental != 0 -> h.accidental
                    before != 0 -> 0
                    else -> null
                }
                last[h.step] = h.accidental
            }
            // Stack accidentals in columns so ones within a sixth don't collide.
            val columns = ArrayList<MutableList<Int>>()
            for (h in e.heads.filter { it.showAccidental != null }.sortedByDescending { it.step }) {
                val col = columns.indexOfFirst { c -> c.all { abs(it - h.step) >= 6 } }
                    .let { if (it < 0) { columns += mutableListOf<Int>(); columns.size - 1 } else it }
                columns[col] += h.step
                e.accColumn[h] = col
            }
            e.accColumns = columns.size
            // A barline cancels accidentals.
            if (e.barAfter) last.clear()
        }
    }

    private val hasLabels get() = events.any { it.label != null }
    private val hasMarks get() = events.any { it.mark != null }

    private fun Event.headW() = if (whole) wholeW else blackW
    private fun Event.hasSeconds() = heads.zipWithNext().any { (a, b) -> b.step - a.step == 1 }
    private fun Event.width() = accColumns * accW + headW() * (if (hasSeconds()) 2 else 1) + if (whole) 3.5f * sp else 2.3f * sp

    private class Line(val events: List<Event>, val xs: List<Float>)

    /** Breaks the events into lines [width] wide, spreading all but the last line to the full width. */
    private fun lines(width: Float): List<Line> {
        val out = ArrayList<List<Event>>()
        var cur = ArrayList<Event>()
        var x = header
        for (e in events) {
            if (cur.isNotEmpty() && x + e.width() > width) {
                out += cur
                cur = ArrayList()
                x = header
            }
            cur += e
            x += e.width()
        }
        if (cur.isNotEmpty()) out += cur
        return out.mapIndexed { i, evs ->
            val used = header + evs.sumOf { it.width().toDouble() }.toFloat()
            val extra = if (i < out.size - 1) (width - used) / evs.size else 0f
            var at = header
            Line(evs, evs.map { e -> at.also { at += e.width() + extra } })
        }
    }

    private fun Event.noteX(cell: Float) = cell + accColumns * accW

    /** The staves for every line of music, one block per line. */
    fun blocks(staves: Staves, width: Float): List<Block> {
        val all = lines(width)
        return all.mapIndexed { i, line ->
            val last = i == all.lastIndex
            val score = if (staves != Staves.Tab) scoreLayout(line) else null
            val marks = if (hasMarks) 1.4f * sp else 0f
            val tabTop = if (score != null) score.height + 0.6f * sp + marks else if (hasLabels) 3f * sp + marks else 0.8f * sp + marks
            val height = if (staves == Staves.Score) score!!.height else tabTop + tabGap * (tuning.strings - 1) + 0.9f * sp
            Block(height) { left, top ->
                if (score != null) drawScore(line, score, left, top, width, last)
                if (staves != Staves.Score) drawTab(line, left, top + tabTop, width, last, labels = score == null)
            }
        }
    }

    private class ScoreLayout(val topPad: Float, val height: Float)

    private fun stemUp(h: Head) = h.step < 4

    private fun scoreLayout(line: Line): ScoreLayout {
        var top = 8
        var bottom = 0
        for (e in line.events) for (h in e.heads) {
            top = max(top, h.step)
            bottom = min(bottom, h.step)
            if (!e.whole) {
                if (stemUp(h)) top = max(top, max(h.step + 7, 4)) else bottom = min(bottom, min(h.step - 7, 4))
            }
        }
        val label = if (hasLabels) 1.8f * sp else 0f
        val topPad = (top - 8) * sp / 2 + 1.2f * sp + label
        return ScoreLayout(topPad, topPad + 4 * sp + (0 - bottom) * sp / 2 + 1.2f * sp)
    }

    private fun Ink.drawScore(line: Line, layout: ScoreLayout, left: Float, top: Float, width: Float, last: Boolean) {
        val staffTop = top + layout.topPad
        val bottomY = staffTop + 4 * sp
        fun y(step: Int) = bottomY - step * sp / 2
        val thin = 0.13f * sp
        for (k in 0..4) line(left, staffTop + k * sp, left + width, staffTop + k * sp, thin)
        line(left, staffTop, left, bottomY, 0.16f * sp)
        glyph(m, clef.glyph, left + 0.6f * sp, y(clef.originStep), em)

        line.events.forEachIndexed { i, e ->
            val cell = left + line.xs[i]
            val x0 = e.noteX(cell)
            val w = e.headW()
            // Noteheads a second apart sit either side of the stem line.
            val shifted = HashSet<Head>()
            e.heads.zipWithNext().forEach { (a, b) -> if (b.step - a.step == 1 && a !in shifted) shifted += b }
            for (h in e.heads) {
                val tag = Tag(h.position, e.group)
                val x = if (h in shifted) x0 + w else x0
                // Ledger lines.
                for (s in -2 downTo h.step step 2) line(x - 0.4f * sp, y(s), x + w + 0.4f * sp, y(s), 0.16f * sp, tag = tag)
                for (s in 10..h.step step 2) line(x - 0.4f * sp, y(s), x + w + 0.4f * sp, y(s), 0.16f * sp, tag = tag)
                glyph(m, if (e.whole) Glyphs.NOTEHEAD_WHOLE else Glyphs.NOTEHEAD_BLACK, x, y(h.step), em, tag = tag)
                h.showAccidental?.let { acc ->
                    val ch = when (acc) {
                        -2 -> Glyphs.DOUBLE_FLAT
                        -1 -> Glyphs.FLAT
                        1 -> Glyphs.SHARP
                        2 -> Glyphs.DOUBLE_SHARP
                        else -> Glyphs.NATURAL
                    }
                    val col = e.accColumn[h] ?: 0
                    val ax = x0 - (col + 1) * accW
                    glyph(m, ch, ax, y(h.step), em, tag = tag)
                }
                if (!e.whole) {
                    val stem = 0.12f * sp
                    if (stemUp(h)) {
                        val sx = x + w - stem / 2
                        line(sx, y(h.step) - 0.17f * sp, sx, y(max(h.step + 7, 4)), stem, tag = tag)
                    } else {
                        val sx = x + stem / 2
                        line(sx, y(h.step) + 0.17f * sp, sx, y(min(h.step - 7, 4)), stem, tag = tag)
                    }
                }
            }
            e.label?.let { text(it, x0 + w / 2, top + 1.4f * sp, 1.3f * sp, GREY, bold = true, align = Align.Center, tag = Tag(Position(-1, -1), e.group)) }
            if (e.barAfter) {
                val bx = cell + e.width() - 1.5f * sp
                if (bx < left + width - 2 * sp) line(bx, staffTop, bx, bottomY, 0.16f * sp)
            }
        }
        // Closing barline: thin + thick.
        if (last) {
            val end = left + width
            line(end - 0.8f * sp, staffTop, end - 0.8f * sp, bottomY, 0.16f * sp)
            line(end - 0.25f * sp, staffTop, end - 0.25f * sp, bottomY, 0.5f * sp)
        }
    }

    private fun Ink.drawTab(line: Line, left: Float, top: Float, width: Float, last: Boolean, labels: Boolean) {
        val n = tuning.strings
        fun y(string: Int) = top + (n - 1 - string) * tabGap
        val bottom = y(0)
        for (s in 0 until n) line(left, y(s), left + width, y(s), 0.13f * sp)
        line(left, top, left, bottom, 0.16f * sp)
        // "TAB" down the start of the staff.
        val letter = min(2.1f * sp, (bottom - top) / 3f * 0.95f)
        "TAB".forEachIndexed { k, ch ->
            val cy = top + (bottom - top) * (k + 0.5f) / 3f
            text("$ch", left + 1.9f * sp, cy + letter * 0.36f, letter, bold = true, align = Align.Center)
        }
        val size = tabGap * 0.85f
        line.events.forEachIndexed { i, e ->
            val cell = left + line.xs[i]
            val cx = e.noteX(cell) + e.headW() / 2
            for (h in e.heads) {
                val tag = Tag(h.position, e.group)
                val label = "${h.position.fret}"
                val w = textWidth(label, size, bold = true)
                rect(cx - w / 2 - 0.15f * sp, y(h.position.string) - size * 0.42f, w + 0.3f * sp, size * 0.84f, PAPER)
                text(label, cx, y(h.position.string) + size * 0.36f, size, bold = true, align = Align.Center, tag = tag)
            }
            if (labels) e.label?.let { text(it, cx, top - 1.4f * sp - if (hasMarks) 1.4f * sp else 0f, 1.3f * sp, GREY, bold = true, align = Align.Center, tag = Tag(Position(-1, -1), e.group)) }
            e.mark?.let { text(it, cx, top - 0.5f * sp, 1.1f * sp, GREY, align = Align.Center) }
            if (e.barAfter) {
                val bx = cell + e.width() - 1.5f * sp
                if (bx < left + width - 2 * sp) line(bx, top, bx, bottom, 0.16f * sp)
            }
        }
        if (last) {
            val end = left + width
            line(end - 0.8f * sp, top, end - 0.8f * sp, bottom, 0.16f * sp)
            line(end - 0.25f * sp, top, end - 0.25f * sp, bottom, 0.5f * sp)
        }
    }
}

/** Title, subtitle and the note/interval legend. */
fun headerBlock(music: Music, width: Float): Block {
    val chip = 30f
    val perRow = max(1, ((width + 4) / (chip + 4)).toInt())
    val rows = (music.legend.size + perRow - 1) / perRow
    return Block(48f + rows * 26f) { left, top ->
        text(music.title, left, top + 16f, 17f, bold = true)
        text(music.subtitle, left, top + 32f, 8.5f, GREY)
        music.legend.forEachIndexed { i, e ->
            val x = left + (i % perRow) * (chip + 4)
            val y = top + 44f + (i / perRow) * 26f
            rect(x, y, chip, 22f, e.color)
            text(e.note, x + chip / 2, y + 10f, 8.5f, PAPER, bold = true, align = Align.Center)
            text(e.interval, x + chip / 2, y + 19f, 6.5f, PAPER, align = Align.Center)
        }
    }
}

fun headingBlock(text: String): Block = Block(16f, keepWithNext = true) { left, top ->
    text(text, left, top + 12f, 11f, bold = true)
}

/**
 * The marked notes on a printable neck, high string on top as in tab. Long
 * ranges split into rows of at most 12 frets.
 */
fun neckBlocks(music: Music, width: Float): List<Block> {
    val tuning = music.tuning
    val n = tuning.strings
    val gap = 15f
    val labelW = 22f
    val openW = 18f
    val first = max(1, music.frets.first)
    val chunks = (first..max(first, music.frets.last)).chunked(12)
    val showOpen = music.frets.first == 0
    return chunks.mapIndexed { rowIndex, frets ->
        val open = showOpen && rowIndex == 0
        val fretW = min(46f, (width - labelW - (if (open) openW else 0f)) / frets.size)
        Block(gap * (n - 1) + 34f) { left, top ->
            val y0 = top + 10f
            fun y(s: Int) = y0 + (n - 1 - s) * gap
            val nutX = left + labelW + if (open) openW else 0f
            val endX = nutX + fretW * frets.size
            fun cx(fret: Int) = if (fret == 0) left + labelW + openW / 2 else nutX + (fret - frets.first() + 0.5f) * fretW
            for (s in 0 until n) {
                line(nutX, y(s), endX, y(s), 0.6f + (n - 1 - s) * 0.15f, if (s in music.strings) INK else LIGHT)
                text(tuning.stringNames[s], left + 4f, y(s) + 3f, 8f, GREY)
            }
            for (k in 0..frets.size) {
                val x = nutX + k * fretW
                val nut = k == 0 && frets.first() == 1
                line(x, y(n - 1), x, y(0), if (nut) 3.5f else 0.8f, if (nut) INK else GREY)
            }
            for (f in frets) text("$f", cx(f), y(0) + 16f, 7.5f, GREY, align = Align.Center)
            if (open) text("open", cx(0), y(0) + 16f, 6.5f, GREY, align = Align.Center)
            val r = min(6.6f, fretW * 0.4f)
            for (d in music.dots) {
                val f = d.position.fret
                if (!(f in frets || (open && f == 0))) continue
                val x = cx(f)
                val y = y(d.position.string)
                circle(x, y, r, d.mark.color)
                text(d.mark.label, x, y + 2.3f, if (d.mark.label.length > 2) 5f else 6.3f, PAPER, bold = true, align = Align.Center)
                d.step?.let { step ->
                    val bx = x + r * 0.85f
                    val by = y - r * 0.85f
                    circle(bx, by, 4f, PAPER, d.mark.color, 0.6f)
                    text("$step", bx, by + 1.8f, if (step < 100) 4.8f else 3.8f, bold = true, align = Align.Center)
                }
            }
        }
    }
}

/** Chord boxes for every shape, laid out in rows. */
fun shapeBlocks(music: Music, title: String, width: Float): List<Block> {
    val tuning = music.tuning
    val n = tuning.strings
    val sGap = 9f
    val fGap = 11f
    val rowsOfFrets = 5
    val boxW = sGap * (n - 1)
    val cellW = boxW + 34f
    val perRow = max(1, (width / cellW).toInt())
    return music.shapes.withIndex().chunked(perRow).map { row ->
        Block(fGap * rowsOfFrets + 50f) { left, top ->
            row.forEachIndexed { col, (index, v) ->
                val x0 = left + col * cellW + 20f
                val y0 = top + 24f
                val fretted = v.frets.filterNotNull().filter { it > 0 }
                val base = if ((fretted.maxOrNull() ?: 0) <= rowsOfFrets) 1 else fretted.min()
                text(music.shapeNames.getOrNull(index) ?: "$title  ${index + 1}", x0 + boxW / 2, top + 9f, 7.5f, bold = true, align = Align.Center)
                for (k in 0..rowsOfFrets) {
                    val y = y0 + k * fGap
                    line(x0, y, x0 + boxW, y, if (k == 0 && base == 1) 2.8f else 0.7f)
                }
                for (s in 0 until n) line(x0 + s * sGap, y0, x0 + s * sGap, y0 + rowsOfFrets * fGap, 0.7f)
                if (base > 1) text("${base}fr", x0 - 4f, y0 + fGap * 0.5f + 2.5f, 7f, GREY, align = Align.Right)
                v.frets.forEachIndexed { s, f ->
                    val x = x0 + s * sGap
                    when {
                        f == null -> text("×", x, y0 - 3f, 7f, GREY, align = Align.Center)
                        f == 0 -> circle(x, y0 - 5f, 2.4f, null, INK, 0.7f)
                        else -> {
                            val y = y0 + (f - base + 0.5f) * fGap
                            val pc = (tuning.midi[s] + f) % 12
                            circle(x, y, 3.6f, music.colors[pc] ?: INK)
                        }
                    }
                }
                text(v.tab, x0 + boxW / 2, y0 + rowsOfFrets * fGap + 12f, 7f, GREY, align = Align.Center)
            }
        }
    }
}
