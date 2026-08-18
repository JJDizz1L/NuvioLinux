package com.nuviolinux.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMillis: Long = 0L,
    fillFrame: Boolean = false,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
)
