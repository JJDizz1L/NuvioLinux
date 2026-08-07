package com.nuviolinux.app.features.player.desktop

import com.nuviolinux.app.core.storage.DesktopStorage

/**
 * Persists desktop window mode/geometry across launches:
 * - whether the app was last closed in fullscreen mode
 * - whether the app was last closed maximized
 * - the last windowed (non-fullscreen) position and size
 *
 * This is intentionally a global (non profile-scoped) preference: it reflects
 * the state of the OS window itself rather than per-user app data.
 */
internal data class DesktopWindowGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal object DesktopWindowModeStorage {
    private const val WasFullscreenKey = "was_fullscreen"
    private const val WasMaximizedKey = "was_maximized"
    private const val WindowXKey = "window_x"
    private const val WindowYKey = "window_y"
    private const val WindowWidthKey = "window_width"
    private const val WindowHeightKey = "window_height"
    private val store = DesktopStorage.stateStore("window_state")

    fun loadWasFullscreen(): Boolean =
        store.getBoolean(WasFullscreenKey) ?: false

    fun saveWasFullscreen(fullscreen: Boolean) {
        store.putBoolean(WasFullscreenKey, fullscreen)
    }

    fun loadWasMaximized(): Boolean? =
        store.getBoolean(WasMaximizedKey)

    fun saveWasMaximized(maximized: Boolean) {
        store.putBoolean(WasMaximizedKey, maximized)
    }

    fun loadWindowedGeometry(): DesktopWindowGeometry? {
        val x = store.getFloat(WindowXKey) ?: return null
        val y = store.getFloat(WindowYKey) ?: return null
        val width = store.getFloat(WindowWidthKey) ?: return null
        val height = store.getFloat(WindowHeightKey) ?: return null
        return DesktopWindowGeometry(x = x, y = y, width = width, height = height)
    }

    fun saveWindowedGeometry(geometry: DesktopWindowGeometry) {
        store.putFloat(WindowXKey, geometry.x)
        store.putFloat(WindowYKey, geometry.y)
        store.putFloat(WindowWidthKey, geometry.width)
        store.putFloat(WindowHeightKey, geometry.height)
    }
}
