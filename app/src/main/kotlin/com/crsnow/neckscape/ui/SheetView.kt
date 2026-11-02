package com.crsnow.neckscape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.crsnow.neckscape.notation.Item
import com.crsnow.neckscape.notation.Seg
import com.crsnow.neckscape.notation.Sheet
import com.crsnow.neckscape.notation.Tag

/** Draws a [Sheet] at 1 unit = 1 dp; [highlight] can recolour tagged notes (sounding, selected). */
@Composable
fun SheetView(sheet: Sheet, modifier: Modifier = Modifier, highlight: (Tag) -> Color? = { null }) {
    val paths = remember(sheet) {
        sheet.items.map { item -> (item as? Item.Shape)?.let { composePath(it.segs) } }
    }
    Canvas(modifier.size(sheet.width.dp, sheet.height.dp)) {
        val scale = density
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            sheet.items.forEachIndexed { i, item ->
                val color = item.tag?.let(highlight)
                when (item) {
                    is Item.Line -> drawLine(
                        color ?: Color(item.color), Offset(item.x1, item.y1), Offset(item.x2, item.y2),
                        item.width, cap = StrokeCap.Butt,
                    )
                    is Item.Rect -> drawRect(color ?: Color(item.color), Offset(item.x, item.y), Size(item.w, item.h))
                    is Item.Circle -> {
                        item.fill?.let { drawCircle(color ?: Color(it), item.r, Offset(item.cx, item.cy)) }
                        item.stroke?.let { drawCircle(Color(it), item.r, Offset(item.cx, item.cy), style = Stroke(item.strokeWidth)) }
                    }
                    is Item.Shape -> drawPath(paths[i]!!, color ?: Color(item.color))
                }
            }
        }
    }
}

private fun composePath(segs: List<Seg>) = Path().apply {
    for (s in segs) when (s) {
        is Seg.Move -> moveTo(s.x, s.y)
        is Seg.Line -> lineTo(s.x, s.y)
        is Seg.Cubic -> cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
        Seg.Close -> close()
    }
}
