package com.nuviolinux.app.features.details.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.nuviolinux.app.features.player.PlayerEngineController
import com.nuviolinux.app.features.player.PlatformPlayerSurface
import com.nuviolinux.app.features.player.PlayerPlaybackSnapshot
import com.nuviolinux.app.features.player.PlayerResizeMode
import com.nuviolinux.app.features.trailer.TrailerExtractionPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val TrailerFillFrameScale = 1.35f

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMillis: Long,
    fillFrame: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    key(sourceUrl, sourceAudioUrl, startPositionMillis) {
        DesktopTrailerPlayerSession(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            playWhenReady = playWhenReady,
            muted = muted,
            startPositionMillis = startPositionMillis,
            fillFrame = fillFrame,
            modifier = modifier,
            onReady = onReady,
            onEnded = onEnded,
            onError = onError,
        )
    }
}

@Composable
private fun DesktopTrailerPlayerSession(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMillis: Long,
    fillFrame: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    var mediaReady by remember { mutableStateOf(false) }
    var terminalReported by remember { mutableStateOf(false) }
    var lastSnapshot by remember { mutableStateOf<PlayerPlaybackSnapshot?>(null) }
    var trailerController by remember { mutableStateOf<PlayerEngineController?>(null) }

    LaunchedEffect(sourceUrl, sourceAudioUrl, startPositionMillis) {
        mediaReady = false
        terminalReported = false
        lastSnapshot = null
        TrailerExtractionPlatform.diagnostic(
            "mpv trailer open ${TrailerExtractionPlatform.describeUrl(sourceUrl)} " +
                "separateAudio=${!sourceAudioUrl.isNullOrBlank()} startMs=$startPositionMillis",
        )
        val ready = withTimeoutOrNull(15_000L) {
            snapshotFlow { lastSnapshot }
                .first { it != null && !it.isLoading && it.durationMs > 0L }
        }
        if (ready == null) {
            if (!terminalReported) {
                terminalReported = true
                TrailerExtractionPlatform.diagnostic("blocked stage=mpv_open reason=timeout")
                latestOnError.value()
            }
            return@LaunchedEffect
        }
        mediaReady = true
        TrailerExtractionPlatform.diagnostic("mpv trailer ready playing=$playWhenReady")
        latestOnReady.value()
    }

    LaunchedEffect(lastSnapshot, terminalReported) {
        val snap = lastSnapshot ?: return@LaunchedEffect
        if (terminalReported) return@LaunchedEffect
        if (snap.isEnded) {
            terminalReported = true
            mediaReady = false
            TrailerExtractionPlatform.diagnostic("mpv trailer ended")
            latestOnEnded.value()
        }
    }

    /* Honor the caller's mute preference (hover "Play with Sound" off, hero
     * audio toggle) once the player handle exists, and re-apply on toggle.
     * setMuted is a no-op until the native handle exists, so poll for it:
     * playback now starts as soon as the source is passed (playWhenReady is
     * no longer gated on mediaReady — mpv never reports duration for a paused
     * DVR-HLS stream), and a muted trailer must not leak audio while opening. */
    LaunchedEffect(trailerController, muted, terminalReported) {
        val controller = trailerController ?: return@LaunchedEffect
        if (terminalReported) return@LaunchedEffect
        while (true) {
            if (controller.currentVolume() != null) {
                controller.setMuted(muted)
                break
            }
            delay(50)
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        PlatformPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (fillFrame) {
                        scaleX = TrailerFillFrameScale
                        scaleY = TrailerFillFrameScale
                    }
                },
            /* Always open playing so mpv reports duration for the DVR-HLS
             * stream (paused opens never report it → ready-timeout); apply
             * the caller's pause preference once ready via the surface's
             * play/pause effect (muting handles audio during the open). */
            playWhenReady = if (mediaReady) playWhenReady else true,
            initialPositionMs = startPositionMillis.takeIf { it > 0L },
            initialPositionRequestKey = "trailer:$sourceUrl",
            resizeMode = if (fillFrame) PlayerResizeMode.Fill else PlayerResizeMode.Fit,
            useNativeController = false,
            onControllerReady = { controller -> trailerController = controller },
            onSnapshot = { snap -> lastSnapshot = snap },
            onError = { error ->
                if (!terminalReported) {
                    terminalReported = true
                    mediaReady = false
                    TrailerExtractionPlatform.diagnostic(
                        "blocked stage=mpv error=${error.orEmpty()}",
                    )
                    latestOnError.value()
                }
            },
        )
    }
}