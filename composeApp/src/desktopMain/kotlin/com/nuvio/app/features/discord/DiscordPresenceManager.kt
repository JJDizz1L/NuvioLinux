package com.nuvio.app.features.discord

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** User-configurable presence behavior (mirrors the Discord settings page). */
internal data class DiscordPresenceConfig(
    val enabled: Boolean = false,
    val hideTitle: Boolean = false,
    val showWhenPaused: Boolean = true,
    val showWhenBrowsing: Boolean = true,
    val showPoster: Boolean = true,
    val showTimestamp: Boolean = true,
)

/** What is currently being played. */
internal data class DiscordPlaybackPresence(
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val paused: Boolean = false,
    val positionSec: Long = 0L,
    val durationSec: Long = 0L,
)

/** What the user is browsing while nothing plays. */
internal data class DiscordBrowsePresence(
    val details: String? = null,
    val state: String? = null,
    val largeImage: String? = null,
    val largeText: String? = null,
)

private data class ComputedPresence(
    val activity: String?,
    val key: String,
    val startTs: Long?,
)

/**
 * True when two playback presences describe the same content. Position is
 * excluded — it changes continuously during playback and must not count as a
 * content change (it only feeds timestamp computation at send time).
 */
private fun DiscordPlaybackPresence?.sameContentAs(other: DiscordPlaybackPresence?): Boolean {
    if (this == null || other == null) return this == other
    return title == other.title &&
        subtitle == other.subtitle &&
        posterUrl == other.posterUrl &&
        paused == other.paused &&
        durationSec == other.durationSec
}

/**
 * Single source of truth for the Discord presence. The UI feeds it intent via
 * [setPlaybackPresence]/[setBrowsePresence]/[configure]; it debounces updates,
 * deduplicates by content key, detects seeks, and drives the [DiscordRpcClient]
 * with reconnect backoff — mirroring Harbor's presence pipeline.
 */
internal object DiscordPresenceManager {
    private const val DEBOUNCE_MS = 800L
    private const val MAX_BACKOFF_MS = 60_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nuvio-discord-rpc").apply { isDaemon = true }
    }

    private val client = DiscordRpcClient().apply {
        onConnectionLost = { schedule() }
    }
    private var pendingFlush: ScheduledFuture<*>? = null

    private var config = DiscordPresenceConfig()
    private var playback: DiscordPlaybackPresence? = null
    private var browse: DiscordBrowsePresence? = null

    private var lastEnabledSent: Boolean? = null
    private var lastKey = ""
    private var lastStartTs: Long? = null
    private var dirty = false
    private var backoffMs = 1_000L

    fun configure(next: DiscordPresenceConfig) {
        synchronized(this) {
            if (config != next) {
                config = next
                lastKey = ""
                dirty = true
            }
        }
        schedule()
    }

    fun setPlaybackPresence(presence: DiscordPlaybackPresence?) {
        synchronized(this) {
            val contentChanged = !playback.sameContentAs(presence)
            // Always keep the latest position — the next flush needs it for
            // fresh timestamps and seek detection.
            playback = presence
            if (contentChanged) {
                lastKey = ""
                dirty = true
            }
        }
        schedule()
    }

    fun setBrowsePresence(presence: DiscordBrowsePresence?) {
        synchronized(this) {
            if (browse != presence) {
                browse = presence
                lastKey = ""
                dirty = true
            }
        }
        schedule()
    }

    private fun schedule() {
        synchronized(this) {
            // Coalesce instead of restarting the debounce: position updates
            // arrive every ~500 ms while playing, and cancelling the pending
            // flush here would starve it indefinitely (it would never fire
            // while the position keeps changing). The in-flight flush picks
            // up the latest state and sends only when content changed or a
            // seek was detected.
            if (pendingFlush == null) {
                pendingFlush = executor.schedule(::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flush() {
        val currentConfig: DiscordPresenceConfig
        val currentPlayback: DiscordPlaybackPresence?
        val currentBrowse: DiscordBrowsePresence?
        var mustSend: Boolean
        synchronized(this) {
            pendingFlush = null
            currentConfig = config
            currentPlayback = playback
            currentBrowse = browse
            mustSend = dirty
        }

        if (lastEnabledSent != currentConfig.enabled) {
            lastEnabledSent = currentConfig.enabled
            lastKey = ""
            lastStartTs = null
            if (!currentConfig.enabled) {
                client.disconnect()
                return
            }
        }
        if (!currentConfig.enabled) return

        val computed = computePresence(currentConfig, currentPlayback, currentBrowse)
        val startTs = computed?.startTs
        val previousStartTs = lastStartTs
        val seeked = startTs != null && previousStartTs != null && abs(startTs - previousStartTs) > 4
        val keyChanged = computed?.key != lastKey
        if (!mustSend && !keyChanged && !seeked) return

        try {
            client.sendActivity(computed?.activity)
            synchronized(this) {
                dirty = false
                lastKey = computed?.key ?: ""
                lastStartTs = startTs
                backoffMs = 1_000L
            }
        } catch (_: IOException) {
            client.disconnect()
            synchronized(this) {
                dirty = true
                lastKey = ""
                lastStartTs = null
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
            // Retry later so a Discord restart self-heals without user action.
            synchronized(this) {
                pendingFlush?.cancel(false)
                pendingFlush = executor.schedule(::flush, backoffMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun computePresence(
        config: DiscordPresenceConfig,
        playback: DiscordPlaybackPresence?,
        browse: DiscordBrowsePresence?,
    ): ComputedPresence? {
        if (playback != null && !(playback.paused && !config.showWhenPaused)) {
            if (config.hideTitle) {
                return ComputedPresence(
                    activity = buildActivity(
                        details = "Watching something",
                        state = if (playback.paused) "Paused" else null,
                        largeImage = null,
                        largeText = null,
                        start = null,
                        end = null,
                    ),
                    key = "hide:${playback.paused}",
                    startTs = null,
                )
            }
            val nowSec = System.currentTimeMillis() / 1000
            val remaining = playback.durationSec - playback.positionSec
            val live = !playback.paused && playback.durationSec > 0 && remaining > 0
            val state = if (playback.paused) {
                "Paused"
            } else {
                playback.subtitle
            }
            val start = if (live && config.showTimestamp) nowSec - playback.positionSec else null
            val end = if (live && config.showTimestamp) nowSec + remaining else null
            return ComputedPresence(
                activity = buildActivity(
                    details = playback.title,
                    state = state,
                    largeImage = if (config.showPoster) playback.posterUrl else null,
                    largeText = playback.title,
                    start = start,
                    end = end,
                ),
                key = buildString {
                    append("play:").append(playback.title)
                    append('|').append(state ?: "")
                    append('|').append(playback.paused)
                    append('|').append(if (config.showPoster) playback.posterUrl else "")
                    append('|').append(if (live && config.showTimestamp) "ts" else "nots")
                },
                startTs = start,
            )
        }
        if (browse != null && config.showWhenBrowsing) {
            if (config.hideTitle) {
                return ComputedPresence(
                    activity = buildActivity(details = "Browsing Nuvio", state = null, largeImage = null, largeText = null, start = null, end = null),
                    key = "browse:hide",
                    startTs = null,
                )
            }
            return ComputedPresence(
                activity = buildActivity(
                    details = browse.details ?: "Browsing Nuvio",
                    state = browse.state,
                    largeImage = if (config.showPoster) browse.largeImage else null,
                    largeText = browse.largeText ?: browse.details,
                    start = null,
                    end = null,
                ),
                key = "browse:${browse.details ?: ""}|${browse.state ?: ""}|${browse.largeImage ?: ""}",
                startTs = null,
            )
        }
        return null
    }

    private fun buildActivity(
        details: String,
        state: String?,
        largeImage: String?,
        largeText: String?,
        start: Long?,
        end: Long?,
    ): String {
        val activity = buildJsonObject {
            clean(state)?.let { put("state", JsonPrimitive(it)) }
            put("details", JsonPrimitive(clean(details) ?: "Watching Nuvio"))
            val timestamps = buildJsonObject {
                if (start != null) put("start", JsonPrimitive(start))
                if (end != null) put("end", JsonPrimitive(end))
            }
            if (timestamps.isNotEmpty()) put("timestamps", timestamps)
            val assets = buildJsonObject {
                safeImageUrl(largeImage)?.let { put("large_image", JsonPrimitive(it)) }
                clean(largeText)?.let { put("large_text", JsonPrimitive(it)) }
            }
            if (assets.isNotEmpty()) put("assets", assets)
            put("instance", JsonPrimitive(true))
        }
        return activity.toString()
    }

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    private fun safeImageUrl(value: String?): String? {
        val url = clean(value) ?: return null
        return if (url.startsWith("https://") && url.length <= 256) url else null
    }
}
