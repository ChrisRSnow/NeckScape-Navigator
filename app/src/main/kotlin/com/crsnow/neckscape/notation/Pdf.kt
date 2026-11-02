package com.crsnow.neckscape.notation

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.roundToLong

/**
 * Writes [Sheet]s as a vector PDF, one page each. Everything is a filled or
 * stroked path (text included), so no fonts are embedded and the output looks
 * like the screen. Plain JVM code, so Android and desktop share it.
 */
object Pdf {
    fun write(pages: List<Sheet>, title: String): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun raw(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: String) {
            offsets += out.size()
            raw("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        fun stream(data: ByteArray) {
            offsets += out.size()
            raw("${offsets.size} 0 obj\n<< /Length ${data.size} /Filter /FlateDecode >>\nstream\n")
            out.write(data)
            raw("\nendstream\nendobj\n")
        }

        raw("%PDF-1.4\n%âãÏÓ\n")
        // 1 catalog, 2 page tree, 3 info, then page + contents per page.
        val pageIds = pages.indices.map { 4 + 2 * it }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        obj("<< /Title ${text(title)} /Producer (NeckScape-Navigator) >>")
        for ((i, page) in pages.withIndex()) {
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(page.width)} ${n(page.height)}] " +
                    "/Resources << >> /Contents ${pageIds[i] + 1} 0 R >>",
            )
            stream(deflate(content(page)))
        }
        val xref = out.size()
        raw("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { raw(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        raw("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun content(sheet: Sheet): ByteArray {
        val sb = StringBuilder()
        // Flip to y-down so sheet coordinates can be used as they are.
        sb.append("1 0 0 -1 0 ").append(n(sheet.height)).append(" cm 1 J 1 j\n")
        var fill: Int? = null
        var stroke: Int? = null
        fun fill(c: Int) {
            if (c != fill) sb.append(rgb(c)).append(" rg\n").also { fill = c }
        }
        fun stroke(c: Int) {
            if (c != stroke) sb.append(rgb(c)).append(" RG\n").also { stroke = c }
        }
        fun pt(x: Float, y: Float) = sb.append(n(x)).append(' ').append(n(y)).append(' ')
        fun circle(cx: Float, cy: Float, r: Float) {
            val k = 0.5523f * r
            pt(cx + r, cy).append("m ")
            pt(cx + r, cy + k); pt(cx + k, cy + r); pt(cx, cy + r).append("c ")
            pt(cx - k, cy + r); pt(cx - r, cy + k); pt(cx - r, cy).append("c ")
            pt(cx - r, cy - k); pt(cx - k, cy - r); pt(cx, cy - r).append("c ")
            pt(cx + k, cy - r); pt(cx + r, cy - k); pt(cx + r, cy).append("c h ")
        }
        for (item in sheet.items) when (item) {
            is Item.Line -> {
                stroke(item.color)
                sb.append(n(item.width)).append(" w ")
                pt(item.x1, item.y1).append("m ")
                pt(item.x2, item.y2).append("l S\n")
            }
            is Item.Rect -> {
                fill(item.color)
                pt(item.x, item.y); pt(item.w, item.h).append("re f\n")
            }
            is Item.Circle -> {
                item.fill?.let { fill(it) }
                item.stroke?.let { stroke(it); sb.append(n(item.strokeWidth)).append(" w ") }
                circle(item.cx, item.cy, item.r)
                sb.append(
                    when {
                        item.fill != null && item.stroke != null -> "B\n"
                        item.fill != null -> "f\n"
                        else -> "S\n"
                    },
                )
            }
            is Item.Shape -> {
                fill(item.color)
                for (s in item.segs) when (s) {
                    is Seg.Move -> pt(s.x, s.y).append("m ")
                    is Seg.Line -> pt(s.x, s.y).append("l ")
                    is Seg.Cubic -> { pt(s.x1, s.y1); pt(s.x2, s.y2); pt(s.x, s.y).append("c ") }
                    Seg.Close -> sb.append("h ")
                }
                sb.append("f\n")
            }
        }
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun rgb(c: Int) = listOf(16, 8, 0).joinToString(" ") { n(((c shr it) and 0xFF) / 255f) }

    /** Up to two decimals, no exponent, locale-independent. */
    private fun n(v: Float): String {
        val r = (v * 100).roundToLong()
        val sign = if (r < 0) "-" else ""
        val a = kotlin.math.abs(r)
        val frac = a % 100
        return when {
            frac == 0L -> "$sign${a / 100}"
            frac % 10 == 0L -> "$sign${a / 100}.${frac / 10}"
            else -> "$sign${a / 100}.${if (frac < 10) "0" else ""}$frac"
        }
    }

    /** A PDF text string: UTF-16BE with a byte-order mark, as hex. */
    private fun text(s: String): String =
        "<FEFF" + s.toByteArray(Charsets.UTF_16BE).joinToString("") { String.format(Locale.ROOT, "%02X", it) } + ">"

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
