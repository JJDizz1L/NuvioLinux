package com.nuviolinux.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import com.nuviolinux.app.features.player.desktop.ComposeRenderSurfaceHost
import com.nuviolinux.app.features.player.desktop.NativePlayerController
import com.nuviolinux.app.features.player.desktop.desktopFullscreenChanges
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
    externalSubtitles: List<com.nuviolinux.app.features.streams.StreamSubtitle>,
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
) {
    val log = remember { Logger.withTag("NativePlayerSurface") }
    val host = remember { ComposeRenderSurfaceHost() }
    val controller = remember(host) { NativePlayerController(host) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    log.d { "composed — sourceUrl=${sourceUrl.take(80)}" }
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

    val window = findPlayerWindow()
    /* AWT-driven surface size (issue #7): Compose's own layout size lags one
     * frame behind the compositor-assigned window bounds on XWayland (the
     * `moved` event carries the new bounds while `compose size` still reports
     * the old one until the next `resized` event). Drive the video render
     * buffer straight from the AWT component resize so the frame always
     * matches the actual window. */
    var windowPixelSize by remember { mutableStateOf<IntSize?>(null) }
    DisposableEffect(host, window) {
        val sizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val component = e.component
                if (component.width > 0 && component.height > 0) {
                    windowPixelSize = IntSize(component.width, component.height)
                }
            }
        }
        window?.addComponentListener(sizeListener)
        onDispose {
            window?.removeComponentListener(sizeListener)
        }
    }

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
            onError = { message -> latestOnError.value(message) },
        )
        // Always report the initial position as unhandled so the runtime's
        // post-load backstop seek runs on desktop. The native bridge applies
        // the position on file-loaded, but a superseded/racing attach must not
        // leave playback stuck at 0 with no correction.
        initialPositionRequestKey?.let { key ->
            latestOnInitialPositionHandled.value(key, false)
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
        ComposeVideoSurface(
            controller = controller,
            awtWindowSize = windowPixelSize,
            modifier = Modifier.fillMaxSize(),
        )
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
    awtWindowSize: IntSize?,
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
            val size = awtWindowSize ?: surfaceSize
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

/** Locates the app's top-level window (the only ownerless window). */
private fun findPlayerWindow(): java.awt.Window? =
    java.awt.Window.getOwnerlessWindows()
        .firstOrNull { it.isVisible && it.isDisplayable && it.isShowing }
        ?: java.awt.Window.getWindows()
            .firstOrNull { it.isVisible && it.isDisplayable && it.isShowing }
