package com.nuviolinux.app.features.discord

import androidx.compose.runtime.Composable

/**
 * Reports what the player is currently showing so the desktop build can
 * publish it as a Discord Rich Presence. No-op on mobile (Discord IPC is a
 * desktop-only local socket/pipe protocol).
 */
@Composable
expect fun DiscordPlaybackPresenceEffect(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
)

/**
 * Reports the current browse context (screen label, optional detail title and
 * artwork) so the desktop build can publish "Browsing …" presences. Pass
 * nulls while the player is on screen — playback presence takes precedence.
 */
@Composable
expect fun DiscordBrowsePresenceEffect(
    details: String?,
    state: String?,
    largeImage: String?,
)
