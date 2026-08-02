package com.nuviolinux.app.features.discord

data class DiscordSettings(
    val enabled: Boolean = false,
    val hideTitle: Boolean = false,
    val showWhenPaused: Boolean = true,
    val showWhenBrowsing: Boolean = true,
    val showPoster: Boolean = true,
    val showTimestamp: Boolean = true,
)
