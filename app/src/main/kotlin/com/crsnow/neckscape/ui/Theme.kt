package com.crsnow.neckscape.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The brand look, the same on every platform: the logo's deep teal and cyan
 * with lime accents, Inter type in two main weights (semibold for headings and
 * labels, regular for body), and soft rounded shapes. Dark follows the system.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD4DE), onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF0A4F57), onPrimaryContainer = Color(0xFFA9F0F5),
    secondary = Color(0xFF9CCFD4), onSecondary = Color(0xFF02363A),
    secondaryContainer = Color(0xFF1C4A4F), onSecondaryContainer = Color(0xFFB8EBF0),
    tertiary = Color(0xFFC6DD3E), onTertiary = Color(0xFF2E3500),
    tertiaryContainer = Color(0xFF434D00), onTertiaryContainer = Color(0xFFE2F95A),
    background = Color(0xFF0B1416), onBackground = Color(0xFFDDE6E7),
    surface = Color(0xFF0B1416), onSurface = Color(0xFFDDE6E7),
    surfaceVariant = Color(0xFF223234), onSurfaceVariant = Color(0xFFA6B5B7),
    surfaceContainerLowest = Color(0xFF060E10), surfaceContainerLow = Color(0xFF111C1E),
    surfaceContainer = Color(0xFF152224), surfaceContainerHigh = Color(0xFF1B2A2C),
    surfaceContainerHighest = Color(0xFF243537),
    outline = Color(0xFF6F8082), outlineVariant = Color(0xFF2C3B3D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A6A73), onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EEF2), onPrimaryContainer = Color(0xFF002023),
    secondary = Color(0xFF4A6366), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8EB), onSecondaryContainer = Color(0xFF051F22),
    tertiary = Color(0xFF5B6600), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFF27A), onTertiaryContainer = Color(0xFF1A1E00),
    background = Color(0xFFF5FAFA), onBackground = Color(0xFF161D1E),
    surface = Color(0xFFF5FAFA), onSurface = Color(0xFF161D1E),
    surfaceVariant = Color(0xFFDAE4E5), onSurfaceVariant = Color(0xFF3F4949),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F5),
    surfaceContainer = Color(0xFFE9EFEF), surfaceContainerHigh = Color(0xFFE3E9E9),
    surfaceContainerHighest = Color(0xFFDDE4E4),
    outline = Color(0xFF6F7979), outlineVariant = Color(0xFFBEC8C9),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun typography(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.inter(weight: FontWeight, tracking: Double? = null) =
        copy(fontFamily = family, fontWeight = weight, letterSpacing = tracking?.sp ?: letterSpacing)
    return Typography(
        displayLarge = base.displayLarge.inter(FontWeight.SemiBold),
        displayMedium = base.displayMedium.inter(FontWeight.SemiBold),
        displaySmall = base.displaySmall.inter(FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.inter(FontWeight.SemiBold, -0.4),
        headlineMedium = base.headlineMedium.inter(FontWeight.SemiBold, -0.3),
        headlineSmall = base.headlineSmall.inter(FontWeight.SemiBold, -0.2),
        titleLarge = base.titleLarge.inter(FontWeight.SemiBold, -0.2),
        titleMedium = base.titleMedium.inter(FontWeight.SemiBold),
        titleSmall = base.titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.inter(FontWeight.Normal),
        bodyMedium = base.bodyMedium.inter(FontWeight.Normal),
        bodySmall = base.bodySmall.inter(FontWeight.Normal),
        labelLarge = base.labelLarge.inter(FontWeight.SemiBold),
        labelMedium = base.labelMedium.inter(FontWeight.SemiBold, 0.3),
        labelSmall = base.labelSmall.inter(FontWeight.Medium, 0.4),
    )
}

/** The logo, provided by each platform, for the top bar; null in previews. */
val LocalLogo = staticCompositionLocalOf<Painter?> { null }

@Composable
fun NeckScapeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    fontFamily: FontFamily = FontFamily.Default,
    logo: Painter? = null,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = typography(fontFamily),
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(LocalLogo provides logo, content = content)
    }
}
