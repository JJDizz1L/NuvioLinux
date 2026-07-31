package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * Abstraction over the platform surface the native player renders into.
 * On macOS/Windows this is the AWT [NativePlayerHost]; on Linux the video is
 * rendered into memory and drawn by Compose ([ComposeRenderSurfaceHost]).
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

internal class NativePlayerHost : Canvas(), NativePlayerSurfaceHost {
    private val log = Logger.withTag("NativePlayerHost")
    override var onPeerReady: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null
    private var controlsVisible = true
    private var cursorVisible = true

    private companion object {
        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }

    init {
        background = Color.BLACK
        ignoreRepaint = false
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                noteCursorActivity()
            }

            override fun mouseDragged(event: MouseEvent) {
                noteCursorActivity()
            }
        })
    }

    override fun isDisplayable(): Boolean = super.isDisplayable()

    override fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        setCursorVisible(visible)
    }

    override fun noteCursorActivity() {
        onCursorActivity?.invoke()
    }

    override fun resetCursorVisibility() {
        controlsVisible = true
        setCursorVisible(true)
    }

    override fun requestFocusInWindow(): Boolean = super.requestFocusInWindow()

    override fun windowAncestor(): Window? = SwingUtilities.getWindowAncestor(this)

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        log.d { "paint — size=${width}x$height" }
    }

    override fun addNotify() {
        super.addNotify()
        log.d { "addNotify — displayable=true, peer ready" }
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        log.d { "removeNotify — peer removed" }
        onPeerReady = null
        resetCursorVisibility()
        super.removeNotify()
    }
}
