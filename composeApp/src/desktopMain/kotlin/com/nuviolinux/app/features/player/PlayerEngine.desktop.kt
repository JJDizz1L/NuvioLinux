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
import com.nuviolinux.app.core.power.ScreensaverInhibit
import com.nuviolinux.app.features.player.desktop.ComposeRenderSurfaceHost
import com.nuviolinux.app.features.player.desktop.NativePlayerController
import com.nuviolinux.app.features.player.desktop.desktopFullscreenChanges
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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
        sourceAudioUrl = sourceAudioUrl,
        sourceHeaders = sourceHeaders,
        modifier = modifier,
        playWhenReady = playWhenReady,
        resizeMode = resizeMode,
        initialPositionMs = initialPositionMs ?: 0L,
        initialPositionRequestKey = initialPositionRequestKey,
        playerControlsState = playerControlsState,
        onPlayerControlsEvent = onPlayerControlsEvent,
        onInitialPositionHandled = onInitialPositionHandled,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
        onError = onError,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    playerControlsState: PlayerControlsState,
    onPlayerControlsEvent: (String, Double) -> Boolean,
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
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
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
        if (window != null) host.attachWindow(window)
        val sizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val component = e.component
                if (component.width > 0 && component.height > 0) {
                    windowPixelSize = IntSize(component.width, component.height)
                }
            }
        }
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) = host.onWindowFocusChanged(true)
            override fun windowLostFocus(e: WindowEvent) = host.onWindowFocusChanged(false)
        }
        window?.addComponentListener(sizeListener)
        window?.addWindowFocusListener(focusListener)
        onDispose {
            window?.removeComponentListener(sizeListener)
            window?.removeWindowFocusListener(focusListener)
            host.onWindowFocusChanged(false)
        }
    }

    LaunchedEffect(controller, sourceUrl, playbackHeaders) {
        onControllerReady(controller)
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
        )
    }

    DisposableEffect(controller, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    LaunchedEffect(
        controller,
        sourceUrl,
        sourceAudioUrl,
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
            sourceAudioUrl = sourceAudioUrl,
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
            // Keep the desktop awake (screen blanking/suspend) while media
            // plays; released on pause/EOF and when the player is disposed.
            ScreensaverInhibit.setActive(snapshot.isPlaying)
            delay(500L)
        }
    }

    DisposableEffect(controller) {
        onDispose { ScreensaverInhibit.release() }
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
        /* Fixed pool of 3 render slots: one is being filled by the producer,
         * one holds the newest completed frame waiting for the next Compose
         * frame, and one holds the frame currently on screen. Rendering (the
         * blocking renderFrame JNI + glReadPixels + pixel copy) runs on a
         * background dispatcher so a 4K readback can never stall the UI
         * thread, and Compose only draws the newest completed frame. */
        val slots = Array(3) { RenderSlot() }
        val slotLock = Any()
        val free = ArrayDeque(List(slots.size) { it })
        val newestSlot = AtomicInteger(-1)
        var drawingSlot = -1
        var lastWidth = 0
        var lastHeight = 0
        var cadenceStartNs = 0L
        var cadenceFrames = 0

        /* Allocate a slot's buffers for the current size if they don't match.
         * Must be called with slotLock held. Deliberately does NOT touch other
         * slots: a slot that is published or currently on screen keeps its
         * valid bitmap until the consumer recycles it. Blanking every slot on
         * resize (the old resizeSlots) left the consumer drawing a fresh empty
         * Bitmap with no pixel data -> Image::makeFromBitmap crash.
         *
         * Buffers are grow-only: as long as the new frame fits the existing
         * capacity they are reused (installPixels rebinds size per frame), so
         * shrinking a window or moving between surfaces doesn't churn native
         * memory through Cleaners/GC. */
        fun ensureSlotSize(slot: RenderSlot, width: Int, height: Int, needed: Int) {
            if (slot.width == width && slot.height == height) return
            if (needed > slot.capacity) {
                val capacity = maxOf(needed, slot.capacity * 2)
                slot.directBuffer = java.nio.ByteBuffer.allocateDirect(capacity)
                slot.pixels = ByteArray(capacity)
                slot.bitmap = Bitmap()
                slot.capacity = capacity
                log.d { "resized slot to ${width}x${height}, buffer=$capacity bytes" }
            }
            slot.width = width
            slot.height = height
        }

        /* Producer: renders into a free slot and publishes the newest
         * completed frame. renderFrame returns true exactly when mpv signals a
         * new frame, so the produce cadence tracks the video FPS; between
         * frames it polls cheaply. */
        launch(Dispatchers.Default) {
            while (coroutineContext.isActive) {
                val size = awtWindowSize ?: surfaceSize
                if (size.width <= 0 || size.height <= 0) {
                    delay(16L)
                    continue
                }
                val needed = size.width * size.height * 4
                if (lastWidth != size.width || lastHeight != size.height) {
                    lastWidth = size.width
                    lastHeight = size.height
                }

                /* Wait for the consumer to pick up the previous frame before
                 * producing another — the pool only has one spare slot. */
                if (newestSlot.get() != -1) {
                    delay(1L)
                    continue
                }
                val index = synchronized(slotLock) { free.removeFirstOrNull() }
                    ?: run { delay(1L); continue }
                val slot = slots[index]
                synchronized(slotLock) { ensureSlotSize(slot, size.width, size.height, needed) }
                slot.directBuffer.rewind()
                val rendered = controller.renderFrame(size.width, size.height, slot.directBuffer)
                if (!rendered) {
                    synchronized(slotLock) { free.addLast(index) }
                    /* No new frame yet; poll cheaply instead of busy-spinning. */
                    delay(1L)
                    continue
                }
                slot.directBuffer.rewind()
                slot.directBuffer.get(slot.pixels, 0, needed)
                if (!slot.bitmap.installPixels(
                        ImageInfo(size.width, size.height, ColorType.RGB_888X, ColorAlphaType.OPAQUE),
                        slot.pixels,
                        size.width * 4,
                    )
                ) {
                    log.w { "installPixels failed for ${size.width}x${size.height}" }
                    synchronized(slotLock) { free.addLast(index) }
                    continue
                }
                /* Publish the filled slot. The producer is the only writer and
                 * only renders while newestSlot == -1, so the CAS always
                 * succeeds here; the bitmap re-store keeps the published slot
                 * filled even if a resize swapped the slot fields mid-render. */
                if (newestSlot.compareAndSet(-1, index)) {
                    synchronized(slotLock) { slots[index].bitmap = slot.bitmap }
                    /* 1 Hz cadence line so a reporter can confirm the pump
                     * produces at the video FPS (not the Compose frame rate). */
                    val nowNs = System.nanoTime()
                    if (cadenceStartNs == 0L) cadenceStartNs = nowNs
                    cadenceFrames++
                    val cadenceMs = (nowNs - cadenceStartNs) / 1_000_000L
                    if (cadenceMs >= 1000L) {
                        log.d {
                            "render cadence: $cadenceFrames frames in ${cadenceMs}ms (" +
                                "%.1f fps".format(cadenceFrames * 1000.0 / cadenceMs) + ")"
                        }
                        cadenceStartNs = nowNs
                        cadenceFrames = 0
                    }
                } else {
                    synchronized(slotLock) { free.addLast(index) }
                }
            }
        }

        /* Consumer: on every Compose frame, draw the newest completed frame. */
        while (coroutineContext.isActive) {
            withFrameNanos { }
            val index = newestSlot.get()
            if (index < 0) continue
            if (newestSlot.compareAndSet(index, -1)) {
                /* The slot drawn in the previous frame has finished drawing,
                 * so it can be recycled. */
                if (drawingSlot >= 0) {
                    synchronized(slotLock) { free.addLast(drawingSlot) }
                }
                drawingSlot = index
                frameImage = synchronized(slotLock) { slots[index].bitmap.asComposeImageBitmap() }
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

/**
 * One reusable video frame buffer. Allocating a fresh skia Bitmap per frame
 * churns native objects through Cleaners and grows the allocator watermark, so
 * the frame pump reuses a fixed pool of these instead.
 */
private class RenderSlot {
    var directBuffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(0)
    var pixels: ByteArray = ByteArray(0)
    var bitmap: Bitmap = Bitmap()
    var capacity: Int = 0
    var width: Int = 0
    var height: Int = 0
}
