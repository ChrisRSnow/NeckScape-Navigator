package com.crsnow.neckscape.notation

import com.crsnow.neckscape.theory.Position
import java.util.concurrent.ConcurrentHashMap

/*
 * A tiny vector drawing model shared by the on-screen score/tab views and the
 * PDF writer. Units are points (1/72 inch) on paper and dp on screen; y points
 * down. Text and music symbols are filled outlines from [Glyphs], so a sheet
 * looks the same everywhere and needs no fonts.
 */

/** One glyph outline: [path] is "M x y L x y Q … C … Z" in font units, y down. */
class Glyph(val advance: Float, val path: String)

class GlyphFont(val unitsPerEm: Float, private val glyphs: Map<Char, Glyph>) {
    private val outlines = ConcurrentHashMap<Char, List<Seg>>()

    operator fun contains(ch: Char) = ch in glyphs

    /** Advance width of [ch] in ems. */
    fun advance(ch: Char): Float = (glyphs[ch] ?: glyphs['?'])!!.advance / unitsPerEm

    fun width(text: String): Float = text.sumOf { advance(it).toDouble() }.toFloat()

    /** The outline of [ch] in ems, quadratic curves already raised to cubics. */
    fun outline(ch: Char): List<Seg> = outlines.getOrPut(ch) {
        parse((glyphs[ch] ?: glyphs['?'])!!.path, 1f / unitsPerEm)
    }
}

sealed interface Seg {
    data class Move(val x: Float, val y: Float) : Seg
    data class Line(val x: Float, val y: Float) : Seg
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg
    data object Close : Seg
}

private fun parse(path: String, scale: Float): List<Seg> {
    val out = ArrayList<Seg>()
    var i = 0
    var cx = 0f
    var cy = 0f
    fun num(): Float {
        while (path[i] == ' ') i++
        val start = i
        if (path[i] == '-') i++
        while (i < path.length && path[i].isDigit()) i++
        return path.substring(start, i).toInt() * scale
    }
    while (i < path.length) {
        when (path[i++]) {
            'M' -> { cx = num(); cy = num(); out += Seg.Move(cx, cy) }
            'L' -> { cx = num(); cy = num(); out += Seg.Line(cx, cy) }
            'Q' -> {
                val qx = num(); val qy = num(); val x = num(); val y = num()
                out += Seg.Cubic(cx + (qx - cx) * 2 / 3, cy + (qy - cy) * 2 / 3, x + (qx - x) * 2 / 3, y + (qy - y) * 2 / 3, x, y)
                cx = x; cy = y
            }
            'C' -> {
                val c = Seg.Cubic(num(), num(), num(), num(), num(), num())
                out += c
                cx = c.x; cy = c.y
            }
            'Z' -> out += Seg.Close
        }
    }
    return out
}

/** Identifies the drawing of one note, so a view can colour what's sounding or selected. */
data class Tag(val position: Position, val group: Int)

/** Colours are ARGB. */
sealed interface Item {
    val tag: Tag?

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Int, override val tag: Tag? = null) : Item
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, override val tag: Tag? = null) : Item
    data class Circle(val cx: Float, val cy: Float, val r: Float, val fill: Int?, val stroke: Int? = null, val strokeWidth: Float = 0f, override val tag: Tag? = null) : Item
    /** A filled outline (non-zero winding). */
    data class Shape(val segs: List<Seg>, val color: Int, override val tag: Tag? = null) : Item
}

class Sheet(val width: Float, val height: Float, val items: List<Item>)

const val INK = 0xFF000000.toInt()
const val GREY = 0xFF757575.toInt()
const val LIGHT = 0xFFBDBDBD.toInt()
const val PAPER = 0xFFFFFFFF.toInt()

enum class Align { Left, Center, Right }

/** Collects items; everything is drawn at absolute sheet coordinates. */
class Ink {
    val items = ArrayList<Item>()

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int = INK, tag: Tag? = null) {
        items += Item.Line(x1, y1, x2, y2, width, color, tag)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, tag: Tag? = null) {
        items += Item.Rect(x, y, w, h, color, tag)
    }

    fun circle(cx: Float, cy: Float, r: Float, fill: Int?, stroke: Int? = null, strokeWidth: Float = 0f, tag: Tag? = null) {
        items += Item.Circle(cx, cy, r, fill, stroke, strokeWidth, tag)
    }

    /** Draws [ch] with its origin at ([x], [y]), [size] points to the em. */
    fun glyph(font: GlyphFont, ch: Char, x: Float, y: Float, size: Float, color: Int = INK, tag: Tag? = null) {
        val segs = font.outline(ch).map { s ->
            when (s) {
                is Seg.Move -> Seg.Move(x + s.x * size, y + s.y * size)
                is Seg.Line -> Seg.Line(x + s.x * size, y + s.y * size)
                is Seg.Cubic -> Seg.Cubic(x + s.x1 * size, y + s.y1 * size, x + s.x2 * size, y + s.y2 * size, x + s.x * size, y + s.y * size)
                Seg.Close -> Seg.Close
            }
        }
        if (segs.isNotEmpty()) items += Item.Shape(segs, color, tag)
    }

    /** Sets [text] on the baseline [y]; returns its width. */
    fun text(
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int = INK,
        bold: Boolean = false,
        align: Align = Align.Left,
        tag: Tag? = null,
    ): Float {
        val font = if (bold) Glyphs.sansBold else Glyphs.sans
        val width = font.width(text) * size
        var pen = when (align) {
            Align.Left -> x
            Align.Center -> x - width / 2
            Align.Right -> x - width
        }
        for (ch in text) {
            glyph(font, ch, pen, y, size, color, tag)
            pen += font.advance(ch) * size
        }
        return width
    }
}

fun textWidth(text: String, size: Float, bold: Boolean = false) =
    (if (bold) Glyphs.sansBold else Glyphs.sans).width(text) * size

/** A horizontal strip of a sheet: a system of music, a neck diagram, a heading. */
class Block(
    val height: Float,
    /** Headings: never left alone at the foot of a page. */
    val keepWithNext: Boolean = false,
    val draw: Ink.(left: Float, top: Float) -> Unit,
)

/** All [blocks] one under another on a single sheet [width] wide (the screen views). */
fun stack(width: Float, blocks: List<Block>, gap: Float, margin: Float = 0f): Sheet {
    val ink = Ink()
    var y = margin
    blocks.forEachIndexed { i, b ->
        if (i > 0) y += gap
        ink.(b.draw)(margin, y)
        y += b.height
    }
    return Sheet(width, y + margin, ink.items)
}

/** A4 portrait, in points. */
const val A4_WIDTH = 595.28f
const val A4_HEIGHT = 841.89f

/**
 * Lays [blocks] onto A4 pages, never splitting a block; each page gets a
 * footer. Blocks should be laid out [A4_WIDTH] - 2 × [margin] wide.
 */
fun paginate(blocks: List<Block>, footer: String, margin: Float = 40f, gap: Float = 14f): List<Sheet> {
    val pages = ArrayList<MutableList<Block>>()
    var used = Float.MAX_VALUE
    val room = A4_HEIGHT - 2 * margin - 16f
    for ((i, b) in blocks.withIndex()) {
        val next = blocks.getOrNull(i + 1)?.takeIf { b.keepWithNext }?.let { gap + it.height } ?: 0f
        val need = if (pages.isEmpty()) b.height else used + gap + b.height
        if (pages.isEmpty() || need + next > room) {
            pages += mutableListOf(b)
            used = b.height
        } else {
            pages.last() += b
            used = need
        }
    }
    return pages.mapIndexed { n, page ->
        val ink = Ink()
        var y = margin
        page.forEachIndexed { i, b ->
            if (i > 0) y += gap
            ink.(b.draw)(margin, y)
            y += b.height
        }
        ink.text("$footer  ·  page ${n + 1} of ${pages.size}", A4_WIDTH / 2, A4_HEIGHT - margin / 2, 7f, GREY, align = Align.Center)
        Sheet(A4_WIDTH, A4_HEIGHT, ink.items)
    }
}
