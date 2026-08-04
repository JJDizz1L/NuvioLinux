package com.nuviolinux.app.core.display

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.WindowState
import co.touchlab.kermit.Logger
import java.awt.Component
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.Window

/**
 * Window geometry diagnostics for the XWayland/X11 sizing investigation
 * (issue: content doesn't fill the window bounds under niri/KDE).
 *
 * Logs the AWT window bounds vs. the Compose [WindowState] size whenever the
 * window is resized, so the desync between the compositor-assigned window and
 * the rendered scene can be measured. Gated behind NUVIO_WINDOW_DEBUG=1.
 */
internal object WindowDiagnostics {
    private val log = Logger.withTag("WindowDiagnostics")

    private val enabled: Boolean by lazy {
        System.getenv("NUVIO_WINDOW_DEBUG") == "1"
    }

    /** Installs a component listener logging geometry on every resize. */
    fun install(window: Window, windowState: WindowState): () -> Unit {
        if (!enabled) return {}
        log.i { "diagnostics enabled (NUVIO_WINDOW_DEBUG=1), display server = ${DisplayServerDetector.detect()}" }

        val listener = object : ComponentListener {
            override fun componentResized(e: ComponentEvent) {
                logBounds(window, windowState, "resized")
            }

            override fun componentMoved(e: ComponentEvent) {
                logBounds(window, windowState, "moved")
            }

            override fun componentShown(e: ComponentEvent) {
                logBounds(window, windowState, "shown")
            }

            override fun componentHidden(e: ComponentEvent) {
                log.i { "componentHidden" }
            }
        }
        window.addComponentListener(listener)
        return { window.removeComponentListener(listener) }
    }

    private fun logBounds(window: Window, windowState: WindowState, reason: String) {
        val bounds = window.bounds
        val stateSize = windowState.size
        val statePosition = windowState.position
        log.i {
            "window bounds=$bounds (${bounds.width}x${bounds.height}px at ${bounds.x},${bounds.y})" +
                " | compose size=${stateSize.width}x${stateSize.height}dp" +
                " | compose position=${if (statePosition.isSpecified) "${statePosition.x.value},${statePosition.y.value}" else "unspecified"}" +
                " | scene=${sceneSize(window)}px | reason=$reason"
        }
    }

    private fun sceneSize(window: Window): IntSize {
        val component = window as? Component ?: return IntSize.Zero
        return IntSize(component.width, component.height)
    }
}
