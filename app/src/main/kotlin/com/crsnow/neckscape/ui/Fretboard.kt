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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What to draw on every fret position sounding a given pitch class. */
data class NoteMark(val label: String, val color: Color)

// Rosewood, nickel frets, a bone nut, pearl inlays and wound/plain strings.
private val BoardDark = Color(0xFF24160F)
private val BoardMid = Color(0xFF3A2418)
private val FretShadow = Color(0xFF6E777C)
private val FretShine = Color(0xFFE9EEF0)
private val Nut = Color(0xFFEDE5D3)
private val StringColor = Color(0xFFD9D3C7)
private val WoundString = Color(0xFFC9B48A)
private val PearlLight = Color(0xFFF4F0E6)
private val PearlDark = Color(0xFFC8BFAE)
private val Shade = Color(0xFF05080A).copy(alpha = 0.58f)
private val BadgeText = Color(0xFF212121)
private val Unmarked = Color(0xFF9E9E9E)

private val NAMES = 20.dp
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

/**
 * A band above the neck naming a CAGED shape over its frets, with a cloud drawn
 * round the notes of its chord [shape]; [selected] draws both solid.
 */
data class Region(val label: String, val frets: IntRange, val color: Color, val selected: Boolean, val shape: Set<Position> = emptySet())

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
    /** Tighter string spacing, for short screens (phones in landscape). */
    compact: Boolean = false,
    onTap: (Position) -> Unit = {},
    /** A CAGED band tapped (index into [regions]). */
    onRegionTap: (Int) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnRegionTap by rememberUpdatedState(onRegionTap)
    val currentRegions by rememberUpdatedState(regions)
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant
    val width = NAMES + OPEN_AREA + (1..fretCount).fold(0.dp) { acc, f -> acc + fretWidth(f) } + 8.dp
    val gap = if (compact) 27.dp else STRING_GAP
    val edge = if (compact) 16.dp else EDGE
    val numbers = if (compact) 18.dp else NUMBER_ROW
    val top = edge + if (regions.isNotEmpty()) REGION_STRIP else 0.dp
    val height = top + edge + gap * (tuning.strings - 1) + numbers

    val scroll = rememberScrollState()
    Column(modifier) {
    Box(Modifier.horizontalScroll(scroll, reverseScrolling = leftHanded)) {
        Canvas(
            Modifier
                .size(width, height)
                .pointerInput(tuning, fretCount, leftHanded, top, gap) {
                    detectTapGestures { tap ->
                        val wirePx = wirePositions(fretCount)
                        val px = if (leftHanded) size.width - tap.x else tap.x
                        // The CAGED bands: anywhere in the strip over a band's frets, its lane picking between overlapping ones.
                        val bands = currentRegions
                        if (bands.isNotEmpty() && tap.y < REGION_STRIP.toPx()) {
                            val over = bands.indices.filter { i ->
                                val r = bands[i]
                                r.frets.first <= fretCount &&
                                    px in (if (r.frets.first == 0) 0f else wirePx[r.frets.first - 1])..wirePx[r.frets.last.coerceAtMost(fretCount)]
                            }
                            val lane = if (tap.y < (3 + 12.5f).dp.toPx()) 0 else 1
                            (over.firstOrNull { it % 2 == lane } ?: over.firstOrNull())?.let(currentOnRegionTap)
                            return@detectTapGestures
                        }
                        val fret = (0..fretCount).firstOrNull { px < wirePx[it] } ?: fretCount
                        val fromTop = ((tap.y - top.toPx()) / gap.toPx()).roundToInt()
                        val string = tuning.strings - 1 - fromTop
                        if (string in 0 until tuning.strings) currentOnTap(Position(string, fret))
                    }
                },
        ) {
            val wirePx = wirePositions(fretCount)
            fun x(px: Float) = if (leftHanded) size.width - px else px
            fun fretCenter(fret: Int) = if (fret == 0) (NAMES + OPEN_AREA / 2).toPx() else (wirePx[fret - 1] + wirePx[fret]) / 2
            fun stringY(string: Int) = top.toPx() + (tuning.strings - 1 - string) * gap.toPx()
            // Half a string gap of wood beyond the outer strings, so their dots sit on the board.
            val boardTop = stringY(tuning.strings - 1) - gap.toPx() / 2
            val boardBottom = stringY(0) + gap.toPx() / 2

            // Wood (a soft gradient across the neck), pearl inlays, nickel frets, a bone nut.
            val left = minOf(x(wirePx[0]), x(wirePx[fretCount]))
            val boardSize = Size(wirePx[fretCount] - wirePx[0], boardBottom - boardTop)
            drawRoundRect(
                Brush.verticalGradient(listOf(BoardDark, BoardMid, BoardDark), startY = boardTop, endY = boardBottom),
                Offset(left, boardTop), boardSize, CornerRadius(4.dp.toPx()),
            )
            val midY = (boardTop + boardBottom) / 2
            fun pearl(center: Offset) {
                val r = 6.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(PearlLight, PearlDark), center = center - Offset(r / 3, r / 3), radius = r * 1.4f), r, center)
            }
            for (f in 1..fretCount) {
                val cx = x(fretCenter(f))
                when (f) {
                    in SINGLE_INLAYS -> pearl(Offset(cx, midY))
                    in DOUBLE_INLAYS -> {
                        val off = gap.toPx()
                        pearl(Offset(cx, midY - off))
                        pearl(Offset(cx, midY + off))
                    }
                }
                // A fret wire: dark edge, bright crown.
                val wx = x(wirePx[f])
                drawLine(FretShadow, Offset(wx, boardTop), Offset(wx, boardBottom), 3.dp.toPx())
                drawLine(FretShine, Offset(wx - 0.5f.dp.toPx(), boardTop), Offset(wx - 0.5f.dp.toPx(), boardBottom), 1.2f.dp.toPx())
                label(measurer, "$f", Offset(cx, boardBottom + numbers.toPx() / 2), numberColor, 11f)
            }
            drawLine(Nut, Offset(x(wirePx[0]), boardTop), Offset(x(wirePx[0]), boardBottom), 7.dp.toPx())

            // Shade the frets outside the selected range (fret 1 starts at the nut).
            val shadeFrom = if (frets.first == 0) 0 else frets.first - 1
            for ((from, to) in listOf(0 to shadeFrom, frets.last to fretCount)) {
                if (to <= from) continue
                val a = x(wirePx[from])
                val b = x(wirePx[to])
                drawRect(Shade, Offset(minOf(a, b), boardTop), Size(Math.abs(b - a), boardBottom - boardTop))
            }

            // Strings, thicker towards the bass (the lower half wound, bronze-ish), with a faint shadow; unselected ones fade.
            // Each one's open note is named at the far end, past the open-string dots.
            val start = NAMES.toPx()
            for (s in 0 until tuning.strings) {
                val y = stringY(s)
                val thickness = (1f + (tuning.strings - 1 - s) * 0.35f).dp.toPx()
                val base = if (s < tuning.strings / 2) WoundString else StringColor
                val color = if (s in strings) base else base.copy(alpha = 0.3f)
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(x(start), y + 1.5f.dp.toPx()), Offset(x(wirePx[fretCount]), y + 1.5f.dp.toPx()), thickness)
                drawLine(color, Offset(x(start), y), Offset(x(wirePx[fretCount]), y), thickness)
                label(measurer, tuning.stringNames[s], Offset(x(start / 2), y), if (s in strings) numberColor else numberColor.copy(alpha = 0.4f), 12f, bold = true)
                if (s in muted) label(measurer, "×", Offset(x(fretCenter(0)), y), numberColor, 16f)
            }

            val radius = minOf(gap.toPx(), fretWidth(fretCount).toPx()) * 0.42f
            val anySelected = regions.any { it.selected }
            fun regionColor(r: Region) = if (!anySelected || r.selected) r.color else r.color.copy(alpha = 0.3f)

            // CAGED chord shapes: a cloud round each one's notes, under the dots.
            for (r in regions) {
                val centers = r.shape.filter { it.fret <= fretCount && it.string < tuning.strings }
                    .map { Offset(x(fretCenter(it.fret)), stringY(it.string)) }
                if (centers.isNotEmpty()) cloud(centers, radius - 1.dp.toPx(), 6.dp.toPx(), 2.dp.toPx(), regionColor(r))
            }

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
                // A soft drop shadow and a fine light rim lift the marker off the wood.
                drawCircle(Color.Black.copy(alpha = 0.4f), radius, center + Offset(0f, 1.5f.dp.toPx()))
                drawCircle(dot.mark.color, radius, center)
                drawCircle(Color.White.copy(alpha = 0.55f), radius - 0.75f.dp.toPx(), center, style = Stroke(1.5f.dp.toPx()))
                label(measurer, dot.mark.label, center, Color.White, 11f, bold = true)
                dot.step?.let { step ->
                    val at = center + Offset(radius * 0.8f, -radius * 0.8f)
                    drawCircle(Color.White, badge, at)
                    drawCircle(dot.mark.color, badge, at, style = Stroke(1.dp.toPx()))
                    label(measurer, "$step", at, BadgeText, if (step < 100) 9f else 7f, bold = true)
                }
            }

            // CAGED shapes: lettered bands above the neck, alternating lanes where neighbours overlap.
            regions.forEachIndexed { i, r ->
                if (r.frets.first > fretCount) return@forEachIndexed
                val from = if (r.frets.first == 0) 0f else wirePx[r.frets.first - 1] + 2.dp.toPx()
                val to = wirePx[r.frets.last.coerceAtMost(fretCount)] - 2.dp.toPx()
                val laneY = 3.dp.toPx() + (i % 2) * 13.dp.toPx()
                val a = minOf(x(from), x(to))
                val w = Math.abs(x(to) - x(from))
                val color = regionColor(r)
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

/**
 * A cloud outline round [centers]: scallops of radius [bump] spaced along their
 * hull, [reach] out from each centre, with a faint fill. Each cloud draws in a
 * layer of its own, so the inner edges can be cleared away without touching the
 * board or a neighbouring cloud.
 */
private fun DrawScope.cloud(centers: List<Offset>, reach: Float, bump: Float, width: Float, color: Color) {
    val hull = convexHull(centers.flatMap { c ->
        (0 until 16).map { k -> c + Offset(cos(k * PI / 8).toFloat() * reach, sin(k * PI / 8).toFloat() * reach) }
    })
    val bumps = aroundLoop(hull, bump * 1.4f)
    val inside = Path().apply {
        moveTo(hull[0].x, hull[0].y)
        for (p in hull.drop(1)) lineTo(p.x, p.y)
        close()
    }
    val bounds = Rect(Offset.Zero, size)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bounds, Paint().apply { alpha = 0.12f })
        drawPath(inside, color.copy(alpha = 1f))
        for (b in bumps) drawCircle(color.copy(alpha = 1f), bump, b)
        canvas.restore()
        canvas.saveLayer(bounds, Paint())
        for (b in bumps) drawCircle(color, bump + width, b)
        drawPath(inside, Color.Black, blendMode = BlendMode.Clear)
        for (b in bumps) drawCircle(Color.Black, bump, b, blendMode = BlendMode.Clear)
        canvas.restore()
    }
}

/** The convex hull of [points], anticlockwise (Andrew's monotone chain). */
private fun convexHull(points: List<Offset>): List<Offset> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size < 3) return sorted
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    fun chain(ps: List<Offset>) = ArrayList<Offset>().also { h ->
        for (p in ps) {
            while (h.size >= 2 && cross(h[h.size - 2], h[h.size - 1], p) <= 0) h.removeAt(h.size - 1)
            h += p
        }
        h.removeAt(h.size - 1)
    }
    return chain(sorted) + chain(sorted.reversed())
}

/** Points evenly spaced, about [spacing] apart, round the closed loop through [loop]. */
private fun aroundLoop(loop: List<Offset>, spacing: Float): List<Offset> {
    val edges = loop.indices.map { loop[it] to loop[(it + 1) % loop.size] }
    val perimeter = edges.sumOf { (a, b) -> (b - a).getDistance().toDouble() }.toFloat()
    val n = max(8, (perimeter / spacing).roundToInt())
    val step = perimeter / n
    val out = ArrayList<Offset>(n)
    var along = 0f
    for ((a, b) in edges) {
        val length = (b - a).getDistance()
        while (out.size < n && along <= length) {
            out += a + (b - a) * (along / length)
            along += step
        }
        along -= length
    }
    return out
}

/** X of the nut (index 0) and every fret wire, in px, before any left-handed mirroring. */
private fun Density.wirePositions(fretCount: Int) = FloatArray(fretCount + 1).also { xs ->
    xs[0] = (NAMES + OPEN_AREA).toPx()
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
