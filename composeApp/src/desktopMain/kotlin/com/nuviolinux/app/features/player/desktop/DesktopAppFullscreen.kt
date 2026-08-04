package com.nuviolinux.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private object DesktopAppFullscreen {
    private var toggleHandler: ((Window?) -> Unit)? = null
    private var fullscreenStateProvider: ((Window?) -> Boolean)? = null
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun setToggleHandler(
        handler: ((Window?) -> Unit)?,
        isFullscreen: (Window?) -> Boolean,
    ): () -> Unit {
        toggleHandler = handler
        fullscreenStateProvider = isFullscreen
        notifyChanged()
        return {
            if (toggleHandler === handler) {
                toggleHandler = null
                fullscreenStateProvider = null
                notifyChanged()
            }
        }
    }

    fun toggle(window: Window? = null) {
        val handler = toggleHandler ?: return
        if (SwingUtilities.isEventDispatchThread()) {
            handler(window)
            notifyChanged()
        } else {
            SwingUtilities.invokeLater {
                handler(window)
                notifyChanged()
            }
        }
    }

    fun isFullscreen(window: Window? = null): Boolean =
        fullscreenStateProvider?.invoke(window) == true

    private fun notifyChanged() {
        _changes.value += 1
    }
}

internal fun registerDesktopAppFullscreenToggle(
    handler: (Window?) -> Unit,
    isFullscreen: (Window?) -> Boolean,
): () -> Unit =
    DesktopAppFullscreen.setToggleHandler(handler, isFullscreen)

internal fun toggleDesktopAppFullscreen(window: Window? = null) {
    DesktopAppFullscreen.toggle(window)
}

internal fun isDesktopAppFullscreen(window: Window? = null): Boolean =
    DesktopAppFullscreen.isFullscreen(window)

internal val desktopFullscreenChanges: StateFlow<Int>
    get() = DesktopAppFullscreen.changes

internal class DesktopAppFullscreenController {
    /* Authoritative app-side fullscreen state. The Compose WindowState
     * placement getter is not reliable on Linux: entering fullscreen from a
     * maximized window leaves AWT MAXIMIZED_BOTH set, so the getter keeps
     * reporting Maximized (or the fullscreen adapter desyncs) and a toggle
     * that reads it back never sees Fullscreen again — the window gets
     * stuck. All toggle decisions use this flag instead. */
    private var userFullscreen = false
    private var previousPlacement = WindowPlacement.Floating
    private var wasMaximizedBeforeFullscreen = false

    fun toggle(window: Window, windowState: WindowState) {
        if (userFullscreen) {
            exitFullscreen(window, windowState)
        } else {
            enterFullscreen(window, windowState)
        }
    }

    fun dispose(window: Window) = Unit

    /**
     * Applies a fullscreen state restored from a previous session, before the
     * window has been interacted with. Only acts when [fullscreen] is true;
     * windowed is already the default state for a freshly created window.
     */
    fun applyRestoredFullscreenState(window: Window, windowState: WindowState, fullscreen: Boolean) {
        if (!fullscreen) return
        previousPlacement = windowState.placement
            .takeUnless { it == WindowPlacement.Fullscreen }
            ?: WindowPlacement.Floating
        wasMaximizedBeforeFullscreen = isMaximized(window)
        clearMaximized(window)
        userFullscreen = true
        windowState.placement = WindowPlacement.Fullscreen
    }

    fun isFullscreen(window: Window, windowState: WindowState): Boolean =
        userFullscreen || windowState.placement == WindowPlacement.Fullscreen

    private fun enterFullscreen(window: Window, windowState: WindowState) {
        previousPlacement = windowState.placement
            .takeUnless { it == WindowPlacement.Fullscreen }
            ?: WindowPlacement.Floating
        wasMaximizedBeforeFullscreen = isMaximized(window)
        /* Compose's Fullscreen setter does not clear AWT MAXIMIZED_BOTH. A
         * maximized-then-fullscreen window keeps the maximized flag on KDE,
         * which corrupts placement read-back and can wedge the WM state.
         * Clear it so the transition happens cleanly. */
        clearMaximized(window)
        userFullscreen = true
        windowState.placement = WindowPlacement.Fullscreen
    }

    private fun exitFullscreen(window: Window, windowState: WindowState) {
        userFullscreen = false
        windowState.placement = previousPlacement
        /* Re-apply maximized only after the fullscreen removal has reached
         * the WM. Compose applies placement changes on the next frame, so an
         * immediate extendedState write can reach the X server BEFORE the
         * fullscreen removal and wedge the window on KDE. */
        if (wasMaximizedBeforeFullscreen) {
            SwingUtilities.invokeLater {
                if (!userFullscreen) setMaximized(window, true)
            }
        }
    }

    private fun isMaximized(window: Window): Boolean {
        val frame = window as? Frame ?: return false
        return (frame.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
    }

    private fun setMaximized(window: Window, value: Boolean) {
        val frame = window as? Frame ?: return
        frame.extendedState = if (value) {
            frame.extendedState or Frame.MAXIMIZED_BOTH
        } else {
            frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
        }
    }

    private fun clearMaximized(window: Window) = setMaximized(window, false)
}

internal fun installDesktopAppFullscreenShortcuts(window: Window): () -> Unit {
    val dispatcher = KeyEventDispatcher { event ->
        if (!event.isDesktopAppFullscreenShortcut()) return@KeyEventDispatcher false
        toggleDesktopAppFullscreen(window)
        true
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}

private fun KeyEvent.isDesktopAppFullscreenShortcut(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    return keyCode == KeyEvent.VK_F11
}
