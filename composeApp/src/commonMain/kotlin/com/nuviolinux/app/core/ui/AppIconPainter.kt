package com.nuviolinux.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

enum class AppIconResource {
    PlayerPlay,
    PlayerPause,
    PlayerAspectRatio,
    PlayerSubtitles,
    PlayerAudioFilled,
    LibraryAddPlus,
}

@Composable
expect fun appIconPainter(icon: AppIconResource): Painter
