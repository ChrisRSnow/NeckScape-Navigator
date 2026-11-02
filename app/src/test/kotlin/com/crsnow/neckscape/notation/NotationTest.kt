package com.crsnow.neckscape.notation

import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Tunings
import com.crsnow.neckscape.theory.Voicing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotationTest {
    private val std = Tunings.all.first()
    private val cMajor = listOf("C", "D", "E", "F", "G", "A", "B").map(Note::parse)

    private fun music(sequence: List<Position> = emptyList(), shapes: List<Voicing> = emptyList()) = Music(
        title = "C Major ♯♭", subtitle = "test", tuning = std,
        spelling = cMajor.associateBy { it.pitchClass }, legend = emptyList(),
        frets = 0..12, strings = 0..5, dots = emptyList(), sequence = sequence, shapes = shapes,
    )

    @Test fun `every glyph the engraver uses exists`() {
        for (ch in listOf(Glyphs.G_CLEF, Glyphs.G_CLEF_8VB, Glyphs.F_CLEF_8VB, Glyphs.NOTEHEAD_BLACK, Glyphs.NOTEHEAD_WHOLE, Glyphs.SHARP, Glyphs.FLAT, Glyphs.NATURAL)) {
            assertTrue("missing ${ch.code.toString(16)}", ch in Glyphs.music)
        }
        for (ch in "ABCDEFG0123456789♯♭×—·") assertTrue("missing $ch", ch in Glyphs.sans)
        assertTrue(Glyphs.sans.outline('A').first() is Seg.Move)
    }

    @Test fun `long scales wrap onto more lines when narrower`() {
        val seq = (0..5).flatMap { s -> listOf(0, 2, 4).map { Position(s, it) } }
        val wide = Engraver(music(seq), 6f).blocks(Staves.Both, 1200f).size
        val narrow = Engraver(music(seq), 6f).blocks(Staves.Both, 300f).size
        assertEquals(1, wide)
        assertTrue(narrow > 2)
    }

    @Test fun `pdf is well formed`() {
        val blocks = Engraver(music(shapes = listOf(Voicing(listOf(null, 3, 2, 0, 1, 0)))), 6f).blocks(Staves.Both, 500f)
        val bytes = Pdf.write(paginate(blocks, "footer"), "C ♯ test")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        // The xref offset must point at the xref table.
        val start = text.substringAfterLast("startxref").trim().lines().first().toInt()
        assertTrue(text.startsWith("xref", start))
        assertTrue("/Count 1" in text)
    }

    @Test fun `many blocks spill onto more pages`() {
        val tall = List(10) { Block(200f) { _, _ -> } }
        assertEquals(4, paginate(tall, "f").size) // 3 per page at 200 pt
    }
}
