package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
actual fun DiscordPlaybackPresenceEffect(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
) {
    DiscordSettingsRepository.ensureLoaded()
    val settings by DiscordSettingsRepository.uiState.collectAsState()

    LaunchedEffect(settings, title, subtitle, posterUrl, isPlaying, positionMs, durationMs) {
        DiscordPresenceManager.configure(
            DiscordPresenceConfig(
                enabled = settings.enabled,
                hideTitle = settings.hideTitle,
                showWhenPaused = settings.showWhenPaused,
                showWhenBrowsing = settings.showWhenBrowsing,
                showPoster = settings.showPoster,
                showTimestamp = settings.showTimestamp,
            )
        )
        val hasStream = isPlaying || durationMs > 0
        DiscordPresenceManager.setPlaybackPresence(
            if (hasStream) {
                DiscordPlaybackPresence(
                    title = title,
                    subtitle = subtitle,
                    posterUrl = posterUrl,
                    paused = !isPlaying,
                    positionSec = positionMs / 1000,
                    durationSec = durationMs / 1000,
                )
            } else {
                null
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { DiscordPresenceManager.setPlaybackPresence(null) }
    }
}

@Composable
actual fun DiscordBrowsePresenceEffect(
    details: String?,
    state: String?,
    largeImage: String?,
) {
    DiscordSettingsRepository.ensureLoaded()
    val settings by DiscordSettingsRepository.uiState.collectAsState()

    LaunchedEffect(settings, details, state, largeImage) {
        DiscordPresenceManager.configure(
            DiscordPresenceConfig(
                enabled = settings.enabled,
                hideTitle = settings.hideTitle,
                showWhenPaused = settings.showWhenPaused,
                showWhenBrowsing = settings.showWhenBrowsing,
                showPoster = settings.showPoster,
                showTimestamp = settings.showTimestamp,
            )
        )
        DiscordPresenceManager.setBrowsePresence(
            if (details != null) {
                DiscordBrowsePresence(
                    details = details,
                    state = state,
                    largeImage = largeImage,
                )
            } else {
                null
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { DiscordPresenceManager.setBrowsePresence(null) }
    }
}
