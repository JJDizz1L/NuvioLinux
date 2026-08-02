package com.nuviolinux.app.features.player.desktop

import java.awt.Window

/**
 * Abstraction over the platform surface the native player renders into.
 * On Linux the video is rendered into memory and drawn by Compose
 * ([ComposeRenderSurfaceHost]); there is no AWT surface.
 */
internal interface NativePlayerSurfaceHost {
    fun isDisplayable(): Boolean
    var onPeerReady: (() -> Unit)?
    var onCursorActivity: (() -> Unit)?
    fun setControlsVisible(visible: Boolean)
    fun resetCursorVisibility()
    fun requestFocusInWindow(): Boolean
    fun noteCursorActivity()
    fun windowAncestor(): Window?
}

/** Linux: no AWT surface; video frames are pulled into memory by the controller. */
internal class ComposeRenderSurfaceHost : NativePlayerSurfaceHost {
    override fun isDisplayable(): Boolean = true
    override var onPeerReady: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null
    override fun setControlsVisible(visible: Boolean) = Unit
    override fun resetCursorVisibility() = Unit
    override fun requestFocusInWindow(): Boolean = false
    override fun noteCursorActivity() = Unit
    override fun windowAncestor(): Window? = null
}
