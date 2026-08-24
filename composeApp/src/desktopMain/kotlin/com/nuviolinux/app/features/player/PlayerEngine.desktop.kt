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
import androidx.compose.ui.unit.IntOffset
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
    cursorControlEnabled: Boolean,
) {
    NativePlayerSurface(
        cursorControlEnabled = cursorControlEnabled,
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
    cursorControlEnabled: Boolean,
) {
    val log = remember { Logger.withTag("NativePlayerSurface") }
    val host = remember { ComposeRenderSurfaceHost() }
    /* Set during composition so it precedes attachWindow's first apply. */
    host.cursorControlEnabled = cursorControlEnabled
    val controller = remember(host) { NativePlayerController(host) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    log.d { "composed — sourceUrl=${sourceUrl.take(80)}" }
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestOnError = rememberUpdatedState(onError)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val forceSoftwareRenderer = playerSettings.forceSoftwareRenderer
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
        forceSoftwareRenderer,
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
            forceSoftwareRenderer = forceSoftwareRenderer,
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
    var frameSrcWidth by remember { mutableStateOf(0) }
    var frameSrcHeight by remember { mutableStateOf(0) }

    /* The pump's LaunchedEffect never restarts (keyed on the stable
     * controller), so a plain parameter capture would freeze the first
     * composition's value forever. State holder keeps resizes flowing. */
    val awtWindowSizeState = rememberUpdatedState(awtWindowSize)

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
        var prodSkips = 0

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
        /* Allocate a slot's bitmap for the current size if they don't match.
         * Must be called with slotLock held. Deliberately does NOT touch other
         * slots: a slot that is published or currently on screen keeps its
         * valid bitmap until the consumer recycles it. Blanking every slot on
         * resize (the old resizeSlots) left the consumer drawing a fresh empty
         * Bitmap with no pixel data -> Image::makeFromBitmap crash.
         *
         * The bitmap is grow-only per axis (1.5x geometric): frames smaller
         * than the capacity render into it stride-aligned (GL_PACK_ROW_LENGTH /
         * SW_STRIDE) and are drawn as a srcSize sub-rect, so window shrinks and
         * small popups don't churn frame-sized allocations. */
        fun ensureSlotSize(slot: RenderSlot, width: Int, height: Int) {
            if (slot.width == width && slot.height == height && slot.hasPixels) return
            if (!slot.hasPixels || width > slot.capacityWidth || height > slot.capacityHeight) {
                val newW = maxOf(width, slot.capacityWidth + slot.capacityWidth / 2)
                val newH = maxOf(height, slot.capacityHeight + slot.capacityHeight / 2)
                val next = Bitmap()
                val info = ImageInfo(newW, newH, ColorType.RGB_888X, ColorAlphaType.OPAQUE)
                check(next.allocPixels(info)) { "Could not allocate ${newW}x${newH} video slot" }
                checkNotNull(next.peekPixels()) { "Skia did not expose video slot pixels" }
                /* The replaced bitmap is intentionally not closed here: the
                 * consumer may still hold an asComposeImageBitmap wrapper of it
                 * from a previous published frame — GC finalizes it, exactly
                 * like the pre-existing code path did. */
                slot.bitmap = next
                slot.capacityWidth = newW
                slot.capacityHeight = newH
                slot.hasPixels = true
                log.d { "resized slot to ${width}x${height} (capacity ${newW}x${newH})" }
            }
            slot.width = width
            slot.height = height
        }

        /* Producer: renders into a free slot and publishes the newest
         * completed frame. Event-driven (phase 2): blocks in waitFrame until
         * mpv's render update callback signals a frame, so the wake cadence IS
         * the video FPS — no 1ms polling between frames. A window resize must
         * not wait (paused video re-renders at the new geometry on the next
         * render call), so size changes bypass the block. */
        /* Telemetry (renderStats JNI burst + 1 Hz log writes on BOTH the
         * producer and the UI-thread consumer) is OFF by default: the log
         * write goes through the console pipe and can stall the writing
         * thread for several ms — a visible hitch every second. Enable with
         * NUVIO_TELEMETRY=1 for diagnostics. */
        val telemetryOn = System.getenv("NUVIO_TELEMETRY") == "1"
        launch(Dispatchers.Default) {
            /* NUVIO_PUMP_POLL=1 restores the pre-phase-2 pump verbatim (1ms
             * polling, no callback gating) for A/B judder comparison. */
            val pollPump = System.getenv("NUVIO_PUMP_POLL") == "1"
            if (pollPump) log.i { "frame pump: legacy 1ms polling mode (NUVIO_PUMP_POLL)" }
            var seenFrameSeq = 0L
            while (coroutineContext.isActive) {
                val size = awtWindowSizeState.value ?: surfaceSize
                if (size.width <= 0 || size.height <= 0) {
                    delay(16L)
                    continue
                }
                val sizeChanged = size.width != lastWidth || size.height != lastHeight

                /* Wait for the consumer to pick up the previous frame before
                 * producing another — the pool only has one spare slot. When
                 * it frees, fall through and produce IMMEDIATELY: the pending
                 * latch may still hold the signal of a frame mpv delivered
                 * while we were busy, and advancing past that signal without
                 * rendering would skip the frame on screen (measured: ~1.5%
                 * published-frame deficit = visible micro-judder on pans). */
                var produceNow = false
                if (newestSlot.get() != -1 && !pollPump) {
                    seenFrameSeq = controller.waitFrame(seenFrameSeq, 8)
                    if (newestSlot.get() != -1) continue
                    produceNow = true // slot just freed — render pending frame NOW
                }

                if (!sizeChanged && !produceNow) {
                    if (pollPump) {
                        /* Legacy pump: 1ms cadence, no callback gating — every
                         * free slot attempts production each pass. */
                        delay(1L)
                    } else {
                        /* Bounded-wait pull: sleep up to 20ms, waking EARLY when
                         * mpv's update callback signals a frame. Renders happen
                         * every pass — mpv's callback rate follows consumption
                         * rate (instrumented: pure event-blocking self-starves to
                         * ~10 signals/s; pulling keeps it locked to video FPS).
                         * Net wakeups ≈ 50-70/s vs ~1000/s under 1ms polling. */
                        controller.waitFrame(seenFrameSeq, 20)
                    }
                }
                lastWidth = size.width
                lastHeight = size.height

                val index = synchronized(slotLock) { free.removeFirstOrNull() }
                    ?: run { delay(1L); continue }
                val slot = slots[index]
                synchronized(slotLock) { ensureSlotSize(slot, size.width, size.height) }
                /* Re-peek every frame (cheap): the Pixmap's address is only
                 * guaranteed for the bitmap's current allocation, and this is
                 * what keeps the direct-write contract explicit. */
                val pixmap = synchronized(slotLock) { slot.bitmap.peekPixels() }
                if (pixmap == null) {
                    synchronized(slotLock) { free.addLast(index) }
                    delay(1L)
                    continue
                }
                val rendered = controller.renderFrameInto(
                    size.width, size.height, pixmap.addr, pixmap.rowBytes,
                )
                if (!rendered) {
                    prodSkips++
                    synchronized(slotLock) { free.addLast(index) }
                    /* No new frame pending (signal already consumed by an
                     * earlier produce). Sleep briefly rather than spin; the
                     * next callback signal re-drives us anyway. */
                    delay(1L)
                    continue
                }
                slot.posMs = controller.positionMs()
                /* Bump skia's generation id so the next asComposeImageBitmap/
                 * draw sees the fresh pixels mpv just wrote behind its back.
                 * Happens BEFORE publish — slot access is serialized by the
                 * pool: the consumer touches a slot only after the CAS below. */
                slot.bitmap.notifyPixelsChanged()
                /* Publish the filled slot. The producer is the only writer and
                 * only renders while newestSlot == -1, so the CAS always
                 * succeeds here; the bitmap re-store keeps the published slot
                 * filled even if a resize swapped the slot fields mid-render. */
                if (newestSlot.compareAndSet(-1, index)) {
                    synchronized(slotLock) { slots[index].bitmap = slot.bitmap }
                    slots[index].publishedAtNs = System.nanoTime()
                    /* 1 Hz cadence line so a reporter can confirm the pump
                     * produces at the video FPS (not the Compose frame rate). */
                    val nowNs = System.nanoTime()
                    if (cadenceStartNs == 0L) cadenceStartNs = nowNs
                    cadenceFrames++
                    val cadenceMs = (nowNs - cadenceStartNs) / 1_000_000L
                    if (cadenceMs >= 1000L) {
                        /* Telemetry snapshot (atomic C++ caches): distinguishes
                         * 'decode too slow' (decoder drops) from 'presentation
                         * jitter' (mistimed/delayed frames) from 'source too
                         * slow' (bitrate vs cache growth). Zeros before media
                         * loads. GATED: the JNI burst + log write cost several
                         * ms on this thread every second — a visible hitch. */
                        if (!telemetryOn) {
                            cadenceStartNs = nowNs
                            cadenceFrames = 0
                            prodSkips = 0
                        } else {
                        val stats = controller.renderStats()
                        val mbps = stats.videoBitrateBitsPerSec / 1_000_000.0
                        log.d {
                            "render cadence: $cadenceFrames frames in ${cadenceMs}ms (" +
                                "%.1f fps".format(cadenceFrames * 1000.0 / cadenceMs) +
                                ") [hwdec=${stats.hwdecCurrent.ifBlank { "none" }}" +
                                " mpv-fps=${"%.1f".format(stats.estimatedVfFps)}" +
                                " bitrate=${"%.1f".format(mbps)}Mbps" +
                                " decDrops=${stats.decoderFrameDropCount}" +
                                " mistimed=${stats.mistimedFrameCount}" +
                                " delayed=${stats.voDelayedFrameCount}" +
                                " skipped=$prodSkips]"
                        }
                        cadenceStartNs = nowNs
                        cadenceFrames = 0
                        prodSkips = 0
                        }
                    }
                } else {
                    synchronized(slotLock) { free.addLast(index) }
                }
            }
        }

        /* Consumer: on every Compose frame, draw the newest completed frame.
         * Telemetry (NUVIO_MPV_DEBUG): per-second tick interval stats +
         * distinct-frame counts + published-age. This is the only window into
         * presentation timing — producer cadence can be perfect while the
         * consumer ticks irregularly (XWayland/GLX swap pacing), which reads
         * as 'smooth but jittery'. */
        var lastTickNs = 0L
        /* Judder metric: intervals between CONSECUTIVE new-content frames —
         * the actual displayed durations. 24fps@120Hz ideal reads ~41.7ms;
         * the fixed-120Hz 5-5-5-6 cadence shows max≈50ms; producer lateness
         * shows scattered outliers (>55ms = a missed beat). */
        var lastNewFrameNs = 0L
        var nfMinMs = Double.MAX_VALUE
        var nfMaxMs = 0.0
        var nfSumMs = 0.0
        var nfCount = 0
        var nfOver55 = 0
        /* Displayed-position deltas: Δ≈42ms normal; Δ<15ms = REPEATED frame;
         * Δ>65ms = SKIPPED frame. rep/jump counts are the judder signature
         * that per-second frame counts cannot see. */
        var lastPosMs = -1L
        var repCount = 0
        var jumpCount = 0
        var tickCount = 0
        var tickMinMs = Double.MAX_VALUE
        var tickMaxMs = 0.0
        var tickSumMs = 0.0
        var distinctFrames = 0
        var staleTicks = 0
        var ageSumMs = 0.0
        var ageMaxMs = 0.0
        var ageCount = 0
        var consumerStatsWindowNs = 0L
        val consumerLog = Logger.withTag("ComposeVideoSurface")
        while (coroutineContext.isActive) {
            val frameNs = withFrameNanos { it }
            if (consumerStatsWindowNs == 0L) consumerStatsWindowNs = frameNs
            if (lastTickNs != 0L) {
                val deltaMs = (frameNs - lastTickNs) / 1_000_000.0
                tickSumMs += deltaMs
                if (deltaMs < tickMinMs) tickMinMs = deltaMs
                if (deltaMs > tickMaxMs) tickMaxMs = deltaMs
            }
            lastTickNs = frameNs
            tickCount++

            val index = newestSlot.get()
            if (index < 0) {
                staleTicks++
            } else if (newestSlot.compareAndSet(index, -1)) {
                /* The slot drawn in the previous frame has finished drawing,
                 * so it can be recycled. */
                if (drawingSlot >= 0) {
                    synchronized(slotLock) { free.addLast(drawingSlot) }
                }
                drawingSlot = index
                distinctFrames++
                if (lastNewFrameNs != 0L) {
                    val deltaMs = (frameNs - lastNewFrameNs) / 1_000_000.0
                    nfSumMs += deltaMs
                    if (deltaMs < nfMinMs) nfMinMs = deltaMs
                    if (deltaMs > nfMaxMs) nfMaxMs = deltaMs
                    if (deltaMs > 55.0) nfOver55++
                    nfCount++
                }
                lastNewFrameNs = frameNs
                synchronized(slotLock) {
                    val pos = slots[index].posMs
                    if (lastPosMs >= 0 && pos > lastPosMs) {
                        val posDelta = pos - lastPosMs
                        if (posDelta < 15) repCount++          /* repeated frame */
                        else if (posDelta > 65) jumpCount++    /* skipped frame */
                    }
                    if (pos >= 0) lastPosMs = pos
                    frameImage = slots[index].bitmap.asComposeImageBitmap()
                    /* Frames render into a capacity-sized bitmap; draw only
                     * the w×h sub-rect mpv actually filled this cycle. */
                    frameSrcWidth = slots[index].width
                    frameSrcHeight = slots[index].height
                }
                val ageMs = (frameNs - slots[index].publishedAtNs) / 1_000_000.0
                ageSumMs += ageMs
                ageCount++
                if (ageMs > ageMaxMs) ageMaxMs = ageMs

                val windowMs = (frameNs - consumerStatsWindowNs) / 1_000_000.0
                if (windowMs >= 1000.0) {
                    /* GATED (see telemetryOn): this write runs on the UI
                     * thread — a blocking console write here misses the next
                     * vsync deadline, i.e. a visible hitch every second. */
                    if (telemetryOn) consumerLog.d {
                        val tickLine = if (tickCount > 1)
                            "%.2f/%.2f/%.2f".format(
                                tickSumMs / (tickCount - 1), tickMinMs, tickMaxMs)
                        else "n/a"
                        val ageLine = if (ageCount > 0)
                            "%.1f/%.1f".format(ageSumMs / ageCount, ageMaxMs)
                        else "n/a"
                        val nfLine = if (nfCount > 0)
                            "min %.1f avg %.1f max %.1f over55=%d/%d"
                                .format(nfMinMs, nfSumMs / nfCount, nfMaxMs, nfOver55, nfCount)
                        else "n/a"
                        "consumer stats: $tickCount ticks ($tickLine), " +
                            "$distinctFrames new frames, $staleTicks stale ticks, " +
                            "age $ageLine | newFrameΔ ms: $nfLine " +
                            "posΔ: rep=$repCount jump=$jumpCount"
                    }
                    lastTickNs = frameNs
                    tickCount = 0; tickMinMs = Double.MAX_VALUE; tickMaxMs = 0.0; tickSumMs = 0.0
                    distinctFrames = 0; staleTicks = 0
                    ageSumMs = 0.0; ageMaxMs = 0.0; ageCount = 0
                    lastNewFrameNs = 0L
                    nfMinMs = Double.MAX_VALUE; nfMaxMs = 0.0; nfSumMs = 0.0; nfCount = 0; nfOver55 = 0
                    lastPosMs = -1L; repCount = 0; jumpCount = 0
                    consumerStatsWindowNs = frameNs
                }
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
            if (frameSrcWidth > 0 && frameSrcHeight > 0) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frameSrcWidth, frameSrcHeight),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
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
    /* Skia-owned pixel memory mpv writes into directly (peekPixels address).
     * The bitmap is allocated at CAPACITY dimensions and frames smaller than
     * that are drawn as a sub-rect (srcSize) — the grow-only reuse the old
     * flat byte buffer had, without the second copy its installPixels(byte[])
     * path required. */
    var bitmap: Bitmap = Bitmap()
    var capacityWidth: Int = 0
    var capacityHeight: Int = 0
    var hasPixels: Boolean = false
    var width: Int = 0
    var height: Int = 0
    /** Media position (ms) of the frame mpv rendered into this slot — lets the
     *  consumer detect displayed-frame REPEATS (Δ≈0) and SKIPS (Δ≈2×period),
     *  the judder signature that frame-count telemetry cannot see. */
    var posMs: Long = -1L
    /** System.nanoTime when the producer published this slot (consumer-side
     *  staleness telemetry). */
    @Volatile
    var publishedAtNs: Long = 0L
}
