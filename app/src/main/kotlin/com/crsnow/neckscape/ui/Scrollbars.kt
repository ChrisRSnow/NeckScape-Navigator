package com.crsnow.neckscape.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Visible scroll bars, where the platform has them: the desktop apps provide
 * them (mouse users need something to drag); Android, which uses swipes and
 * fading indicators, doesn't. They only appear when there's somewhere to scroll.
 */
interface Scrollbars {
    @Composable fun Vertical(state: ScrollState, modifier: Modifier)
    @Composable fun Horizontal(state: ScrollState, modifier: Modifier, reverse: Boolean = false)
    @Composable fun HorizontalLazy(state: LazyListState, modifier: Modifier)
}

val LocalScrollbars = staticCompositionLocalOf<Scrollbars?> { null }
