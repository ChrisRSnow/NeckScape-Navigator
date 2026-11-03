package com.crsnow.neckscape.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The handful of icons the app needs, drawn on a 24-unit grid (no icon library to ship). */
object AppIcons {
    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (filled) path(fill = SolidColor(Color.Black), pathBuilder = block)
            else path(
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    val Play = icon("Play", filled = true) {
        moveTo(8f, 5.5f); lineTo(19f, 12f); lineTo(8f, 18.5f); close()
    }

    val Stop = icon("Stop", filled = true) {
        moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close()
    }

    val Undo = icon("Undo") {
        moveTo(9f, 7f); lineTo(5f, 11f); lineTo(9f, 15f)
        moveTo(5f, 11f); lineTo(14f, 11f)
        curveTo(17.3f, 11f, 20f, 13.7f, 20f, 17f)
        lineTo(20f, 18f)
    }

    val Open = icon("Open") {
        moveTo(3.5f, 18.5f); lineTo(3.5f, 6.5f); lineTo(9f, 6.5f); lineTo(11f, 8.5f); lineTo(19.5f, 8.5f); lineTo(19.5f, 11f)
        moveTo(3.5f, 18.5f); lineTo(6.5f, 11f); lineTo(21.5f, 11f); lineTo(18.5f, 18.5f); close()
    }

    val Save = icon("Save") {
        moveTo(12f, 4f); lineTo(12f, 15f)
        moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(5f, 19.5f); lineTo(19f, 19.5f)
    }

    /** Three sliders: settings without a fiddly gear at small sizes. */
    val Tune = icon("Tune") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 5f); lineTo(9f, 9f)
        moveTo(15f, 10f); lineTo(15f, 14f)
        moveTo(7f, 15f); lineTo(7f, 19f)
    }

    val Speaker = icon("Speaker") {
        moveTo(4f, 9.5f); lineTo(8f, 9.5f); lineTo(12.5f, 5.5f); lineTo(12.5f, 18.5f); lineTo(8f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(16f, 9f); curveTo(17.3f, 10.6f, 17.3f, 13.4f, 16f, 15f)
        moveTo(18.5f, 6.5f); curveTo(21.2f, 9.9f, 21.2f, 14.1f, 18.5f, 17.5f)
    }

    val Close = icon("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val ChevronLeft = icon("ChevronLeft") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val ChevronRight = icon("ChevronRight") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val ChevronDown = icon("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
}
