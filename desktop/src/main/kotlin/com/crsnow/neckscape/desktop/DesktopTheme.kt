package com.crsnow.neckscape.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import javax.imageio.ImageIO

/** Inter, from the same font files Android uses (app/src/main/res/font, on the desktop classpath). */
val InterFamily: FontFamily by lazy {
    fun font(file: String, weight: FontWeight) =
        Font(file, object {}.javaClass.getResourceAsStream("/$file")!!.readBytes(), weight)
    FontFamily(
        font("inter_regular.ttf", FontWeight.Normal),
        font("inter_medium.ttf", FontWeight.Medium),
        font("inter_semibold.ttf", FontWeight.SemiBold),
        font("inter_bold.ttf", FontWeight.Bold),
    )
}

/** The wheel without its tile, for the top bar. */
val LogoPainter: BitmapPainter by lazy {
    BitmapPainter(ImageIO.read(object {}.javaClass.getResource("/ic_launcher_foreground.png")).toComposeImageBitmap())
}
