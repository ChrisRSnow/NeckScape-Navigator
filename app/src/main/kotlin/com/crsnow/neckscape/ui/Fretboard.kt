package com.crsnow.neckscape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crsnow.neckscape.theory.Interval
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.playingOrder
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** What to draw on every fret position sounding a given pitch class. */
data class NoteMark(val label: String, val color: Color)

private val Board = Color(0xFF3E2723)
private val FretWire = Color(0xFFB0BEC5)
private val Nut = Color(0xFFEFEBE9)
private val StringColor = Color(0xFFD7CCC8)
private val Inlay = Color(0xFF8D6E63)
private val Shade = Color.Black.copy(alpha = 0.45f)
private val BadgeText = Color(0xFF212121)
private val Unmarked = Color(0xFF9E9E9E)

private val OPEN_AREA = 40.dp
private val STRING_GAP = 34.dp
private val EDGE = 24.dp
private val NUMBER_ROW = 22.dp
private val SINGLE_INLAYS = setOf(3, 5, 7, 9, 15, 17, 19, 21)
private val DOUBLE_INLAYS = setOf(12, 24)

/** Frets narrow towards the body, but much less than real ones so high frets stay tappable. */
private fun fretWidth(fret: Int): Dp = max(38f, 68f * 0.965f.pow(fret - 1)).dp

/** One colour per scale degree, so shapes read the same in every key. */
fun degreeColor(interval: Interval): Color = when (interval.simpleDegree) {
    1 -> Color(0xFFE53935)
    2 -> Color(0xFF43A047)
    3 -> Color(0xFFFB8C00)
    4 -> Color(0xFF00ACC1)
    5 -> Color(0xFF1E88E5)
    6 -> Color(0xFFC0CA33)
    else -> Color(0xFF8E24AA)
}

/** A band above the neck naming a CAGED shape over its frets; [selected] draws it solid. */
data class Region(val label: String, val frets: IntRange, val color: Color, val selected: Boolean)

/** CAGED shape colours: C red, A orange, G green, E blue, D purple. */
fun cagedColor(letter: String): Color = when (letter) {
    "C" -> Color(0xFFE53935)
    "A" -> Color(0xFFFB8C00)
    "G" -> Color(0xFF43A047)
    "E" -> Color(0xFF1E88E5)
    else -> Color(0xFF8E24AA)
}

private val REGION_STRIP = 30.dp

/**
 * A marker to draw: [step] is its place in the playing order, if numbered.
 * [faded] dots sit in the background (a scale overlay); [emphasis] makes a
 * faded one stand out a little (the overlay's roots).
 */
data class Dot(val position: Position, val mark: NoteMark, val step: Int? = null, val faded: Boolean = false, val emphasis: Boolean = false)

/**
 * The dots for what's on screen: every position in the selected frets/strings
 * whose pitch class is in [marks]. With a [voicing], its notes are drawn
 * full-strength and the rest fade back.
 */
fun buildDots(
    tuning: Tuning,
    marks: Map<Int, NoteMark>,
    frets: IntRange,
    strings: IntRange,
    voicing: Voicing?,
    numbered: Boolean,
): List<Dot> {
    val marked = strings.flatMap { s -> frets.map { f -> Position(s, f) } }
        .filter { tuning.pitchClassAt(it.string, it.fret) in marks }
    val sounded = voicing?.frets?.withIndex()
        ?.mapNotNull { (s, f) -> f?.let { Position(s, it) } }
        ?.toSet()
    val lit = sounded ?: marked
    val order = if (numbered) playingOrder(tuning, lit) else emptyMap()
    fun dot(p: Position, faded: Boolean) = Dot(p, marks.getValue(tuning.pitchClassAt(p.string, p.fret)), order[p], faded)
    return marked.filter { sounded != null && it !in sounded }.map { dot(it, faded = true) } + lit.map { dot(it, faded = false) }
}

/**
 * Horizontal fretboard, highest string on top as in tab. Frets and strings
 * outside [frets]/[strings] are shaded; [muted] strings get an × at the nut.
 */
@Composable
fun Fretboard(
    tuning: Tuning,
    fretCount: Int,
    leftHanded: Boolean,
    dots: List<Dot>,
    frets: IntRange,
    strings: IntRange,
    muted: Set<Int>,
    modifier: Modifier = Modifier,
    sounding: Set<Position> = emptySet(),
    /** Notes marked by highlight_notes: a pink ring. */
    marked: Set<Position> = emptySet(),
    /** CAGED shape bands above the neck. */
    regions: List<Region> = emptyList(),
    /** A chord shape inside the notes (the CAGED shape): a dark ring. */
    outlined: Set<Position> = emptySet(),
    onTap: (Position) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val currentOnTap by rememberUpdatedState(onTap)
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant
    val width = OPEN_AREA + (1..fretCount).fold(0.dp) { acc, f -> acc + fretWidth(f) } + 8.dp
    val top = EDGE + if (regions.isNotEmpty()) REGION_STRIP else 0.dp
    val height = top + EDGE + STRING_GAP * (tuning.strings - 1) + NUMBER_ROW

    val scroll = rememberScrollState()
    Column(modifier) {
    Box(Modifier.horizontalScroll(scroll, reverseScrolling = leftHanded)) {
        Canvas(
            Modifier
                .size(width, height)
                .pointerInput(tuning, fretCount, leftHanded, top) {
                    detectTapGestures { tap ->
                        val wirePx = wirePositions(fretCount)
                        val px = if (leftHanded) size.width - tap.x else tap.x
                        val fret = (0..fretCount).firstOrNull { px < wirePx[it] } ?: fretCount
                        val fromTop = ((tap.y - top.toPx()) / STRING_GAP.toPx()).roundToInt()
                        val string = tuning.strings - 1 - fromTop
                        if (string in 0 until tuning.strings) currentOnTap(Position(string, fret))
                    }
                },
        ) {
            val wirePx = wirePositions(fretCount)
            fun x(px: Float) = if (leftHanded) size.width - px else px
            fun fretCenter(fret: Int) = if (fret == 0) OPEN_AREA.toPx() / 2 else (wirePx[fret - 1] + wirePx[fret]) / 2
            fun stringY(string: Int) = top.toPx() + (tuning.strings - 1 - string) * STRING_GAP.toPx()
            // Half a string gap of wood beyond the outer strings, so their dots sit on the board.
            val boardTop = stringY(tuning.strings - 1) - STRING_GAP.toPx() / 2
            val boardBottom = stringY(0) + STRING_GAP.toPx() / 2

            // Wood, inlays, frets, nut.
            val left = minOf(x(wirePx[0]), x(wirePx[fretCount]))
            drawRect(Board, Offset(left, boardTop), Size(wirePx[fretCount] - wirePx[0], boardBottom - boardTop))
            val midY = (boardTop + boardBottom) / 2
            for (f in 1..fretCount) {
                val cx = x(fretCenter(f))
                when (f) {
                    in SINGLE_INLAYS -> drawCircle(Inlay, 6.dp.toPx(), Offset(cx, midY))
                    in DOUBLE_INLAYS -> {
                        val off = STRING_GAP.toPx()
                        drawCircle(Inlay, 6.dp.toPx(), Offset(cx, midY - off))
                        drawCircle(Inlay, 6.dp.toPx(), Offset(cx, midY + off))
                    }
                }
                drawLine(FretWire, Offset(x(wirePx[f]), boardTop), Offset(x(wirePx[f]), boardBottom), 2.dp.toPx())
                label(measurer, "$f", Offset(cx, boardBottom + NUMBER_ROW.toPx() / 2), numberColor, 11f)
            }
            drawLine(Nut, Offset(x(wirePx[0]), boardTop), Offset(x(wirePx[0]), boardBottom), 6.dp.toPx())

            // Shade the frets outside the selected range (fret 1 starts at the nut).
            val shadeFrom = if (frets.first == 0) 0 else frets.first - 1
            for ((from, to) in listOf(0 to shadeFrom, frets.last to fretCount)) {
                if (to <= from) continue
                val a = x(wirePx[from])
                val b = x(wirePx[to])
                drawRect(Shade, Offset(minOf(a, b), boardTop), Size(Math.abs(b - a), boardBottom - boardTop))
            }

            // Strings, thicker towards the bass; unselected ones fade.
            for (s in 0 until tuning.strings) {
                val y = stringY(s)
                val thickness = (1f + (tuning.strings - 1 - s) * 0.35f).dp.toPx()
                val color = if (s in strings) StringColor else StringColor.copy(alpha = 0.3f)
                drawLine(color, Offset(x(0f), y), Offset(x(wirePx[fretCount]), y), thickness)
                if (s in muted) label(measurer, "×", Offset(x(fretCenter(0)), y), numberColor, 16f)
            }

            val radius = minOf(STRING_GAP.toPx(), fretWidth(fretCount).toPx()) * 0.42f
            val lit = dots.filter { !it.faded }.map { it.position }.toSet()
            // What's sounding: a glow and a white ring, under the dots so badges stay readable
            // (plus a plain dot if the spot isn't marked).
            for (p in sounding) {
                if (p.fret > fretCount || p.string >= tuning.strings) continue
                val center = Offset(x(fretCenter(p.fret)), stringY(p.string))
                if (p !in lit) drawCircle(Unmarked, radius * 0.7f, center)
                drawCircle(Color.White.copy(alpha = 0.3f), radius + 9.dp.toPx(), center)
                drawCircle(Color.White, radius + 3.dp.toPx(), center, style = Stroke(3.dp.toPx()))
            }

            // Notes, faded ones first so full ones draw on top.
            val badge = 7.dp.toPx()
            for (dot in dots.sortedBy { !it.faded }) {
                val (s, f) = dot.position
                if (f > fretCount) continue
                val center = Offset(x(fretCenter(f)), stringY(s))
                if (dot.faded) {
                    drawCircle(dot.mark.color.copy(alpha = if (dot.emphasis) 0.6f else 0.25f), radius * if (dot.emphasis) 0.7f else 0.55f, center)
                    continue
                }
                drawCircle(dot.mark.color, radius, center)
                label(measurer, dot.mark.label, center, Color.White, 11f, bold = true)
                dot.step?.let { step ->
                    val at = center + Offset(radius * 0.8f, -radius * 0.8f)
                    drawCircle(Color.White, badge, at)
                    drawCircle(dot.mark.color, badge, at, style = Stroke(1.dp.toPx()))
                    label(measurer, "$step", at, BadgeText, if (step < 100) 9f else 7f, bold = true)
                }
            }

            // CAGED shapes: lettered bands above the neck, alternating lanes where neighbours overlap.
            val anySelected = regions.any { it.selected }
            regions.forEachIndexed { i, r ->
                if (r.frets.first > fretCount) return@forEachIndexed
                val from = if (r.frets.first == 0) 0f else wirePx[r.frets.first - 1] + 2.dp.toPx()
                val to = wirePx[r.frets.last.coerceAtMost(fretCount)] - 2.dp.toPx()
                val laneY = 3.dp.toPx() + (i % 2) * 13.dp.toPx()
                val a = minOf(x(from), x(to))
                val w = Math.abs(x(to) - x(from))
                val color = if (!anySelected || r.selected) r.color else r.color.copy(alpha = 0.3f)
                drawRoundRect(color, Offset(a, laneY), Size(w, 12.dp.toPx()), CornerRadius(6.dp.toPx()))
                label(measurer, r.label, Offset(a + w / 2, laneY + 6.dp.toPx()), Color.White, 9f, bold = true)
            }
            for (p in outlined) {
                if (p.fret > fretCount || p.string >= tuning.strings) continue
                drawCircle(Color(0xFF212121), radius + 2.dp.toPx(), Offset(x(fretCenter(p.fret)), stringY(p.string)), style = Stroke(2.5f.dp.toPx()))
            }

            // Marked notes: a pink ring outside everything else.
            for (p in marked) {
                if (p.fret > fretCount || p.string >= tuning.strings) continue
                val center = Offset(x(fretCenter(p.fret)), stringY(p.string))
                if (p !in lit) drawCircle(HighlightPink.copy(alpha = 0.35f), radius * 0.6f, center)
                drawCircle(HighlightPink, radius + 5.dp.toPx(), center, style = Stroke(3.dp.toPx()))
            }
        }
    }
    LocalScrollbars.current?.Horizontal(scroll, Modifier.fillMaxWidth(), reverse = leftHanded)
    }
}

/** X of the nut (index 0) and every fret wire, in px, before any left-handed mirroring. */
private fun Density.wirePositions(fretCount: Int) = FloatArray(fretCount + 1).also { xs ->
    xs[0] = OPEN_AREA.toPx()
    for (f in 1..fretCount) xs[f] = xs[f - 1] + fretWidth(f).toPx()
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean = false,
) {
    val layout = measurer.measure(
        text,
        TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
    drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}
