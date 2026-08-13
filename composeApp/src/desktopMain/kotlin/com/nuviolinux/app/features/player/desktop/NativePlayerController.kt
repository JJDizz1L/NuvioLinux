package com.nuviolinux.app.features.player.desktop

import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import com.nuviolinux.app.features.player.AudioTrack
import com.nuviolinux.app.features.player.PlayerControlsState
import com.nuviolinux.app.features.player.PlayerEngineController
import com.nuviolinux.app.features.player.PlayerPlaybackSnapshot
import com.nuviolinux.app.features.player.PlayerResizeMode
import com.nuviolinux.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuviolinux.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuviolinux.app.features.player.SubtitleStyleState
import com.nuviolinux.app.features.player.SubtitleTrack
import com.nuviolinux.app.features.player.inferForcedSubtitleTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

internal class NativePlayerController(
    private val host: NativePlayerSurfaceHost,
) : PlayerEngineController {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val log = Logger.withTag("NativePlayerControls")

        /** Cap on waiting for the previous player's teardown so a hung one cannot block playback. */
        const val TEARDOWN_WAIT_MS = 5_000L

        @Volatile
        var rememberedVolumeLevel: Float = 1f
    }

    @Volatile
    private var handle: Long = 0L

    /** Native teardown of the previous player, if one is still running. */
    @Volatile
    private var disposeInFlight: Thread? = null
    private var pendingSource: PendingSource? = null
    private var controlsState = PlayerControlsState()
    private var pendingSubtitleDelayMs: Int? = null
    private var pendingSubtitleStyle: SubtitleStyleState? = null
    private var pendingUseLibass: Boolean = false
    private var onEvent: (String, Double) -> Boolean = { _, _ -> false }

    fun attach(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        decoderPriority: Int,
        streamCacheBytes: Long,
        streamCacheOnDisk: Boolean,
        onError: (String?) -> Unit,
    ) {
        val pending = PendingSource(
            sourceUrl = sourceUrl,
            headerLines = sourceHeaders.toHeaderLines(),
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs.coerceAtLeast(0L),
            decoderPriority = decoderPriority,
            streamCacheBytes = streamCacheBytes,
            streamCacheOnDisk = streamCacheOnDisk,
            onError = onError,
        )
        pendingSource = pending
        log.d {
            "attach requested source=${sourceUrl.toPlaybackLogKey()} headers=${sourceHeaders.size} " +
                "playWhenReady=$playWhenReady initialPositionMs=$initialPositionMs decoderPriority=$decoderPriority"
        }
        attachPending()
    }

    private fun attachPending() {
        val pending = pendingSource ?: run {
            log.d { "attachPending — pendingSource is null, skipping" }
            return
        }
        log.d { "attachPending — disposing previous handle" }
        disposePlayerHandle()
        val teardown = disposeInFlight
        if (teardown == null || !teardown.isAlive) {
            log.d { "attachPending — no teardown in flight, calling createPlayer directly" }
            createPlayer(pending)
            return
        }
        Thread({
            runCatching { teardown.join(TEARDOWN_WAIT_MS) }
            if (pendingSource === pending) {
                createPlayer(pending)
            }
        }, "nuvio-player-attach").apply {
            isDaemon = true
            start()
        }
    }

    private fun createPlayer(pending: PendingSource) {
        val resolvedSource = if (pending.sourceUrl.startsWith("file:", ignoreCase = true)) {
            runCatching { java.io.File(java.net.URI(pending.sourceUrl)).absolutePath }.getOrElse {
                val stripped = pending.sourceUrl.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "")
                runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
            }
        } else {
            pending.sourceUrl
        }

        Thread({
            log.d { "createPlayer — background thread starting NativePlayerBridge.create" }
            runCatching {
                log.d { "createPlayer — calling NativePlayerBridge.create" }
                NativePlayerBridge.create(
                    hostViewPtr = 0L,
                    sourceUrl = resolvedSource,
                    headerLines = pending.headerLines.toTypedArray(),
                    playWhenReady = pending.playWhenReady,
                    initialPositionMs = pending.initialPositionMs,
                    decoderPriority = pending.decoderPriority,
                    streamCacheBytes = pending.streamCacheBytes,
                    streamCacheOnDisk = pending.streamCacheOnDisk,
                ).also { handle ->
                    log.d { "createPlayer — NativePlayerBridge.create returned handle=0x${handle.toString(16)}" }
                    if (handle == 0L) error("Native player did not return a handle.")
                }
            }.onSuccess { created ->
                if (pendingSource !== pending) {
                    // Superseded while we were initialising; drop it rather than leak it.
                    Thread({ runCatching { NativePlayerBridge.dispose(created) } }, "nuvio-player-dispose")
                        .apply { isDaemon = true }.start()
                    return@onSuccess
                }
                handle = created
                log.d {
                    "attach created handle=$created source=${resolvedSource.toPlaybackLogKey()} " +
                        "initialPositionMs=${pending.initialPositionMs}"
                }
                applyRememberedVolume()
                updateControls(controlsState)
                applyPendingSubtitleSettings()
            }.onFailure { error ->
                log.w(error) { "attach failed source=${pending.sourceUrl.toPlaybackLogKey()}" }
                pending.onError(error.message)
            }
        }, "nuvio-player-create").apply {
            isDaemon = true
            start()
        }
    }

    fun setControlCallbacks(
        onEvent: (String, Double) -> Boolean,
    ) {
        this.onEvent = onEvent
        log.d { "control callbacks attached handle=$handle" }
        host.onCursorActivity = {
            this.onEvent("cursorActivity", 0.0)
        }
    }

    fun updateControls(state: PlayerControlsState) {
        host.setControlsVisible(state.controlsVisible)
        val currentHandle = handle
        val current = currentHandle.takeIf { it != 0L } ?: run {
            controlsState = state
            return
        }
        val stateWithVolume = if (state.volumeLevel == null) {
            state.copy(volumeLevel = NativePlayerBridge.volume(current).coerceIn(0f, 1f))
        } else {
            state
        }
        controlsState = stateWithVolume
    }

    fun onDesktopFullscreenChanged() {
        updateControls(controlsState)
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.setResizeMode(
                handle = current,
                mode = when (mode) {
                    PlayerResizeMode.Fit -> 0
                    PlayerResizeMode.Fill -> 1
                    PlayerResizeMode.Zoom -> 2
                    PlayerResizeMode.Stretch -> 3
                },
            )
        }
    }

    private fun setFallbackVolume(level: Float) {
        val current = handle
        if (current != 0L) {
            val nextLevel = level.coerceIn(0f, 1f)
            rememberedVolumeLevel = nextLevel
            NativePlayerBridge.setVolume(current, nextLevel)
            controlsState = controlsState.copy(volumeLevel = nextLevel)
            updateControls(controlsState)
        }
    }

    private fun applyRememberedVolume() {
        val current = handle
        if (current == 0L) return
        val level = rememberedVolumeLevel.coerceIn(0f, 1f)
        NativePlayerBridge.setVolume(current, level)
        controlsState = controlsState.copy(volumeLevel = level)
        log.d { "applied remembered volume level=$level handle=$current" }
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        return runCatching {
            val isLoading = NativePlayerBridge.isLoading(current)
            val isEnded = NativePlayerBridge.isEnded(current)
            PlayerPlaybackSnapshot(
                isLoading = isLoading,
                isPlaying = !NativePlayerBridge.isPaused(current) && !isLoading && !isEnded,
                isEnded = isEnded,
                durationMs = NativePlayerBridge.durationMs(current),
                positionMs = NativePlayerBridge.positionMs(current),
                bufferedPositionMs = NativePlayerBridge.bufferedPositionMs(current),
                playbackSpeed = NativePlayerBridge.speed(current),
                volumeLevel = NativePlayerBridge.volume(current).coerceIn(0f, 1f),
            )
        }.getOrDefault(PlayerPlaybackSnapshot(isLoading = true))
    }

    override fun currentVolume(): Float? =
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.volume(it).coerceIn(0f, 1f) }

    override fun setVolume(level: Float) {
        setFallbackVolume(level)
    }

    override fun setMuted(muted: Boolean) {
        setFallbackVolume(if (muted) 0f else (rememberedVolumeLevel.takeIf { it > 0f } ?: 1f))
    }

    /** Renders the latest video frame into [buffer] (RGB0, stride = width * 4). */
    fun renderFrame(width: Int, height: Int, buffer: java.nio.ByteBuffer): Boolean {
        val current = handle
        if (current == 0L) return false
        return runCatching { NativePlayerBridge.renderFrame(current, width, height, buffer) }
            .getOrElse { error ->
                if (error !is NoClassDefFoundError) {
                    log.w(error) { "renderFrame JNI failed handle=$current" }
                }
                false
            }
    }

    /** Reports mouse activity over the Compose video surface (reveals controls). */
    fun reportCursorActivity() {
        onEvent("cursorActivity", 0.0)
    }

    fun dispose() {
        host.resetCursorVisibility()
        disposePlayerHandle()
    }

    private fun disposePlayerHandle() {
        val current = handle
        handle = 0L
        if (current == 0L) return
        // Native shutdown blocks: it joins the player's render/event threads. Tear
        // down off the calling thread and track it so the next attach can wait for
        // it rather than racing it.
        disposeInFlight = Thread({ runCatching { NativePlayerBridge.dispose(current) } }, "nuvio-player-dispose").apply {
            isDaemon = true
            start()
        }
    }

    override fun play() {
        log.d { "play handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, false) }
    }

    override fun pause() {
        log.d { "pause handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, true) }
    }

    override fun seekTo(positionMs: Long) {
        log.d { "seekTo positionMs=$positionMs handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.seekTo(it, positionMs) }
    }

    override fun seekBy(offsetMs: Long) {
        log.d { "seekBy offsetMs=$offsetMs handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.seekBy(it, offsetMs) }
    }

    override fun retry() {
        val pending = pendingSource ?: return
        attach(
            sourceUrl = pending.sourceUrl,
            sourceHeaders = pending.headerLines.toHeaderMap(),
            playWhenReady = pending.playWhenReady,
            initialPositionMs = pending.initialPositionMs,
            decoderPriority = pending.decoderPriority,
            streamCacheBytes = pending.streamCacheBytes,
            streamCacheOnDisk = pending.streamCacheOnDisk,
            onError = pending.onError,
        )
    }

    override fun setPlaybackSpeed(speed: Float) {
        log.d { "setPlaybackSpeed speed=$speed handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setSpeed(it, speed) }
    }

    override fun getAudioTracks(): List<AudioTrack> =
        decodeTracks { NativePlayerBridge.audioTracksJson(it) }.map { track ->
            AudioTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
            )
        }

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }.map { track ->
            SubtitleTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
                isForced = track.forced || inferForcedSubtitleTrack(
                    label = track.label,
                    language = track.language,
                    trackId = track.id,
                ),
            )
        }

    override fun selectAudioTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val tracks = decodeTracks { NativePlayerBridge.audioTracksJson(it) }
        val trackId = resolveTrackId(index, tracks) ?: run {
            log.w { "selectAudioTrack missing track index=$index count=${tracks.size} handle=$current" }
            return
        }
        log.d { "selectAudioTrack index=$index trackId=$trackId count=${tracks.size} handle=$current" }
        NativePlayerBridge.selectAudioTrack(current, trackId)
    }

    override fun selectSubtitleTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        if (index < 0) {
            log.d { "selectSubtitleTrack off handle=$current" }
            NativePlayerBridge.selectSubtitleTrack(current, -1)
            return
        }
        val tracks = decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }
        val trackId = resolveTrackId(index, tracks) ?: run {
            log.w { "selectSubtitleTrack missing track index=$index count=${tracks.size} handle=$current" }
            return
        }
        log.d { "selectSubtitleTrack index=$index trackId=$trackId count=${tracks.size} handle=$current" }
        NativePlayerBridge.selectSubtitleTrack(current, trackId)
        applyPendingSubtitleSettings()
    }

    override fun setSubtitleUri(url: String) {
        log.d { "setSubtitleUri ${url.toPlaybackLogKey()} handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.addSubtitleUrl(it, url) }
    }

    override fun clearExternalSubtitle() {
        log.d { "clearExternalSubtitle handle=$handle" }
        handle.takeIf { it != 0L }?.let(NativePlayerBridge::clearExternalSubtitles)
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val trackId = if (trackIndex < 0) {
            -1
        } else {
            val tracks = decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }
            resolveTrackId(trackIndex, tracks) ?: run {
                log.w { "clearExternalSubtitleAndSelect missing track index=$trackIndex count=${tracks.size} handle=$current" }
                return
            }
        }
        log.d { "clearExternalSubtitleAndSelect trackIndex=$trackIndex trackId=$trackId handle=$current" }
        NativePlayerBridge.clearExternalSubtitlesAndSelect(current, trackId)
        applyPendingSubtitleSettings()
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        pendingSubtitleDelayMs = clamped
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.setSubtitleDelayMs(current, clamped)
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyleState, useLibass: Boolean) {
        pendingSubtitleStyle = style
        pendingUseLibass = useLibass
        handle.takeIf { it != 0L }?.let { current ->
            applySubtitleStyle(current, style, useLibass)
        }
    }

    private fun applyPendingSubtitleSettings() {
        val current = handle.takeIf { it != 0L } ?: return
        pendingSubtitleDelayMs?.let { delayMs ->
            NativePlayerBridge.setSubtitleDelayMs(current, delayMs)
        }
        pendingSubtitleStyle?.let { style ->
            applySubtitleStyle(current, style, pendingUseLibass)
        }
    }

    private fun applySubtitleStyle(handle: Long, style: SubtitleStyleState, useLibass: Boolean) {
        NativePlayerBridge.applySubtitleStyle(
            handle = handle,
            textColor = style.textColor.toMpvColorString(),
            backgroundColor = style.backgroundColor.toMpvColorString(),
            outlineColor = style.outlineColor.toMpvColorString(),
            outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
            bold = style.bold,
            fontSize = style.toMpvSubtitleFontSize(),
            subPos = style.toMpvSubtitlePosition(),
            useLibass = useLibass,
        )
    }

    private fun decodeTracks(readJson: (Long) -> String): List<NativeMpvTrack> {
        val current = handle.takeIf { it != 0L } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<NativeMpvTrack>>(readJson(current))
        }.getOrDefault(emptyList())
    }
}

private fun String.toPlaybackLogKey(): String {
    val scheme = substringBefore(':', missingDelimiterValue = "unknown")
        .takeIf { it.isNotBlank() }
        ?: "unknown"
    return "scheme=$scheme length=$length hash=${hashCode()}"
}

@Serializable
private data class NativeMpvTrack(
    val index: Int = 0,
    val id: String = "",
    val label: String = "",
    val language: String = "",
    val selected: Boolean = false,
    val forced: Boolean = false,
)

private fun resolveTrackId(index: Int, tracks: List<NativeMpvTrack>): Int? =
    tracks.firstNotNullOfOrNull { track ->
        if (track.index == index) {
            track.id.toIntOrNull()
        } else {
            null
        }
    } ?: tracks.getOrNull(index)?.id?.toIntOrNull()

private fun Color.toMpvColorString(): String {
    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
    val redInt = (red * 255f).toInt().coerceIn(0, 255)
    val greenInt = (green * 255f).toInt().coerceIn(0, 255)
    val blueInt = (blue * 255f).toInt().coerceIn(0, 255)
    return buildString {
        append('#')
        append(alphaInt.toHexByte())
        append(redInt.toHexByte())
        append(greenInt.toHexByte())
        append(blueInt.toHexByte())
    }
}

private fun SubtitleStyleState.toMpvSubtitlePosition(): Int =
    (100 - (bottomOffset / 2)).coerceIn(0, 150)

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Float =
    (fontSizeSp * 3f).coerceIn(18f, 96f)

private fun Int.toHexByte(): String {
    val digits = "0123456789ABCDEF"
    val value = coerceIn(0, 255)
    return buildString {
        append(digits[value / 16])
        append(digits[value % 16])
    }
}

private data class PendingSource(
    val sourceUrl: String,
    val headerLines: List<String>,
    val playWhenReady: Boolean,
    val initialPositionMs: Long,
    val decoderPriority: Int,
    val streamCacheBytes: Long,
    val streamCacheOnDisk: Boolean,
    val onError: (String?) -> Unit,
)

private fun Map<String, String>.toHeaderLines(): List<String> =
    entries.mapNotNull { (key, value) ->
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        if (cleanKey.isBlank() || cleanValue.isBlank()) {
            null
        } else {
            "$cleanKey: $cleanValue"
        }
    }

private fun List<String>.toHeaderMap(): Map<String, String> =
    mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()
