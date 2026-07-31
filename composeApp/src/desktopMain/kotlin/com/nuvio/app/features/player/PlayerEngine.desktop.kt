package com.nuvio.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.features.player.desktop.ComposeRenderSurfaceHost
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.desktopFullscreenChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    if (DesktopHostOs.current == DesktopHostOs.MACOS || DesktopHostOs.current == DesktopHostOs.WINDOWS || DesktopHostOs.current == DesktopHostOs.LINUX) {
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            initialPositionMs = initialPositionMs ?: 0L,
            initialPositionRequestKey = initialPositionRequestKey,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
            renderToMemory = DesktopHostOs.current == DesktopHostOs.LINUX,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        initialPositionRequestKey = initialPositionRequestKey,
        onInitialPositionHandled = onInitialPositionHandled,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    renderToMemory: Boolean,
) {
    val log = remember { Logger.withTag("NativePlayerSurface") }
    val platformDensity = LocalNuvioPlatformDensity.current
    val host = remember { if (renderToMemory) ComposeRenderSurfaceHost() else NativePlayerHost() }
    val controller = remember(host) { NativePlayerController(host, renderToMemory) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    log.d { "composed — sourceUrl=${sourceUrl.take(80)} renderToMemory=$renderToMemory" }
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestOnError = rememberUpdatedState(onError)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val streamCacheSize = playerSettings.streamCacheSize
    val streamCacheOnDisk = playerSettings.streamCacheOnDisk
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled

    LaunchedEffect(controller, sourceUrl, playbackHeaders) {
        onControllerReady(controller)
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    LaunchedEffect(
        controller,
        sourceUrl,
        playbackHeaders,
        decoderPriority,
        streamCacheSize,
        streamCacheOnDisk,
        nvidiaRtxSuperResolutionEnabled,
        initialPositionMs,
        initialPositionRequestKey,
    ) {
        log.d { "calling controller.attach" }
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            decoderPriority = decoderPriority,
            streamCacheBytes = streamCacheSize.bytes,
            streamCacheOnDisk = streamCacheOnDisk,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            onError = { message -> latestOnError.value(message) },
        )
        initialPositionRequestKey?.let { key ->
            latestOnInitialPositionHandled.value(key, initialPositionMs > 0L)
        }
        onControllerReady(controller)
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, playerControlsState) {
        controller.updateControls(playerControlsState)
    }

    LaunchedEffect(controller) {
        desktopFullscreenChanges.drop(1).collect {
            controller.onDesktopFullscreenChanged()
        }
    }

    LaunchedEffect(controller) {
        // The UI thread is the mpv render thread; mpv forbids other libmpv API
        // calls from it. Poll the snapshot off the render thread instead.
        while (true) {
            val snapshot = withContext(Dispatchers.IO) { controller.snapshot() }
            onSnapshot(snapshot)
            delay(500L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (renderToMemory) {
            ComposeVideoSurface(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CompositionLocalProvider(LocalDensity provides platformDensity) {
                SwingPanel(
                    factory = { host as NativePlayerHost },
                    modifier = Modifier.fillMaxSize(),
                    background = Color.Black,
                )
            }
        }
    }
}

/**
 * Linux video surface: pulls frames from the native render API into memory and
 * draws them as part of the Compose scene, so all overlay UI (controls, modals,
 * skip prompts) renders above the video and receives input normally.
 */
@Composable
private fun ComposeVideoSurface(
    controller: NativePlayerController,
    modifier: Modifier,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var frameImage by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(controller) {
        val log = Logger.withTag("ComposeVideoSurface")
        val directBuffers = Array(2) { java.nio.ByteBuffer.allocateDirect(0) }
        val pixelRows = Array(3) { ByteArray(0) }
        /* Fixed bitmap pool: allocating a fresh skia Bitmap per frame churns
         * native objects through Cleaners and grows the allocator watermark.
         * Reuse one bitmap per pixel buffer instead. */
        val bitmaps = Array(3) { Bitmap() }
        var directIndex = 0
        var rowIndex = 0
        var lastWidth = 0
        var lastHeight = 0

        while (coroutineContext.isActive) {
            withFrameNanos { }
            val size = surfaceSize
            if (size.width <= 0 || size.height <= 0) continue
            val needed = size.width * size.height * 4
            if (lastWidth != size.width || lastHeight != size.height) {
                lastWidth = size.width
                lastHeight = size.height
                for (i in directBuffers.indices) {
                    directBuffers[i] = java.nio.ByteBuffer.allocateDirect(needed)
                }
                for (i in pixelRows.indices) {
                    pixelRows[i] = ByteArray(needed)
                }
                log.d { "resized surface to ${size.width}x${size.height}, buffer=${needed} bytes" }
            }
            val buffer = directBuffers[directIndex]
            directIndex = (directIndex + 1) % directBuffers.size
            buffer.rewind()
            if (!controller.renderFrame(size.width, size.height, buffer)) continue
            buffer.rewind()
            val pixels = pixelRows[rowIndex]
            val bitmap = bitmaps[rowIndex]
            rowIndex = (rowIndex + 1) % pixelRows.size
            buffer.get(pixels, 0, needed)
            if (bitmap.installPixels(
                    ImageInfo(size.width, size.height, ColorType.RGB_888X, ColorAlphaType.OPAQUE),
                    pixels,
                    size.width * 4,
                )
            ) {
                frameImage = bitmap.asComposeImageBitmap()
            } else {
                log.w { "installPixels failed for ${size.width}x${size.height}" }
            }
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { surfaceSize = it }
            .pointerInput(controller) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                            controller.reportCursorActivity()
                        }
                    }
                }
            },
    ) {
        frameImage?.let { image ->
            drawImage(
                image = image,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    initialPositionRequestKey: String?,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    LaunchedEffect(initialPositionRequestKey) {
        initialPositionRequestKey?.let { key -> onInitialPositionHandled(key, false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun retry() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}
