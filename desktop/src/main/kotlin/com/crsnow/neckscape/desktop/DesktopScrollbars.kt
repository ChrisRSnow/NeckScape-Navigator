package com.crsnow.neckscape.desktop

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.crsnow.neckscape.ui.Scrollbars

/** Compose Desktop's own scroll bars, shown only when the content overflows. */
object DesktopScrollbars : Scrollbars {
    @Composable
    override fun Vertical(state: ScrollState, modifier: Modifier) {
        if (state.maxValue > 0) VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
    }

    @Composable
    override fun Horizontal(state: ScrollState, modifier: Modifier, reverse: Boolean) {
        if (state.maxValue > 0) HorizontalScrollbar(rememberScrollbarAdapter(state), modifier, reverseLayout = reverse)
    }

    @Composable
    override fun HorizontalLazy(state: LazyListState, modifier: Modifier) {
        if (state.canScrollForward || state.canScrollBackward) HorizontalScrollbar(rememberScrollbarAdapter(state), modifier)
    }
}
