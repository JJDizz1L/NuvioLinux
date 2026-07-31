package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage

internal class NativePlayerHost : Canvas() {
    private val log = Logger.withTag("NativePlayerHost")
    var onPeerReady: (() -> Unit)? = null
    var onCursorActivity: (() -> Unit)? = null
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

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        setCursorVisible(visible)
    }

    fun noteCursorActivity() {
        onCursorActivity?.invoke()
    }

    fun resetCursorVisibility() {
        controlsVisible = true
        setCursorVisible(true)
    }

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
