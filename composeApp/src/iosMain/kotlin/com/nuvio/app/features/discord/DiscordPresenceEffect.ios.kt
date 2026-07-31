package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable

@Composable
actual fun DiscordPlaybackPresenceEffect(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
) = Unit

@Composable
actual fun DiscordBrowsePresenceEffect(
    details: String?,
    state: String?,
    largeImage: String?,
) = Unit
