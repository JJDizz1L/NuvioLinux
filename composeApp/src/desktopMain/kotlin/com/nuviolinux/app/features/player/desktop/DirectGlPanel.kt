package com.nuviolinux.app.features.player.desktop

import com.jogamp.opengl.GL
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLJPanel
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct-mode video surface: a JOGL [GLJPanel] whose offscreen hardware FBO
 * receives mpv's render (libmpv render API), composited into the Swing/AWT
 * paint path — the video never passes through skia.
 *
 * Design notes (mirrors the technique proven by Cove on this exact stack):
 * - GLJPanel is LIGHTWEIGHT and z-orders correctly inside Compose Desktop's
 *   SwingPanel. GLCanvas is heavyweight and would float above everything.
 * - [setSkipGLOrientationVerticalFlip] handles FBO orientation; mpv's output
 *   lands right-side-up without an extra pass.
 * - Rendering is FLOW-PACED: [requestRender] is invoked from mpv's render
 *   update callback (via the player's frame pump thread) and coalesced onto
 *   the EDT — presentation is paced by mpv's frame timing, not a poll.
 * - mpv_render_context_render leaves framebuffer 0 bound; GLJPanel's
 *   composition pass needs its own FBO rebound when the listener exits.
 * - The player-side render (bridge `directRenderFrame`) saves/restores the
 *   scene binding + viewport and resets skia-dirtied GL state to defaults
 *   before mpv renders — both interop contracts handled bridge-side.
 */
class DirectGlPanel : GLJPanel(openGlCapabilities()) {

    interface Renderer {
        /** Called once with the JOGL context current: attach mpv's render
         *  context and start the (deferred) playback. */
        fun initialize(): Boolean

        /** Called each frame with the JOGL context current: render mpv into
         *  [framebuffer]. */
        fun render(framebuffer: Int, width: Int, height: Int)

        /** Called with the JOGL context current: detach mpv's render context. */
        fun detach()
    }

    @Volatile private var renderer: Renderer? = null

    private val renderQueued = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    /* Set once initialize() succeeds; display() retries until then (the
     * player may still be creating when the panel first realizes). */
    private val initializedOk = AtomicBoolean(false)

    init {
        println("[direct-video] GLJPanel created")
        isOpaque = true
        setSkipGLOrientationVerticalFlip(true)

        addGLEventListener(object : GLEventListener {
            override fun init(drawable: GLAutoDrawable) {
                println("[direct-video] JOGL init: attaching renderer")
                renderer?.initialize()
            }

            override fun display(drawable: GLAutoDrawable) {
                val r = renderer ?: return
                /* Late-attach retry: the player may not exist yet at the
                 * first display() (create runs on a background thread).
                 * Retry each display until initialize succeeds. */
                if (!initializedOk.get()) {
                    initializedOk.set(r.initialize())
                    if (!initializedOk.get()) return
                }
                val fbo = IntArray(1)
                drawable.gl.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, fbo, 0)
                r.render(
                    framebuffer = fbo[0],
                    width = drawable.surfaceWidth.coerceAtLeast(1),
                    height = drawable.surfaceHeight.coerceAtLeast(1),
                )
                // mpv render returns with framebuffer 0 bound; GLJPanel's
                // composition pass expects its own FBO — rebind it.
                drawable.gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[0])
            }

            override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, w: Int, h: Int) = Unit

            override fun dispose(drawable: GLAutoDrawable) {
                renderer?.detach()
            }
        })
    }

    fun attach(renderer: Renderer) {
        check(this.renderer == null) { "renderer already attached" }
        this.renderer = renderer
        requestRender()
    }

    /** Called from mpv's frame pump thread: coalesce and marshal to the EDT. */
    fun requestRender() {
        if (closed.get() || !isDisplayable || !renderQueued.compareAndSet(false, true)) return
        EventQueue.invokeLater {
            if (!closed.get() && isDisplayable) {
                runCatching(::display)
            } else {
                renderQueued.set(false)
            }
        }
    }

    /** Detaches mpv's render context with the panel's GL context current. */
    fun detachRenderer() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            invoke(true, com.jogamp.opengl.GLRunnable { gl ->
                renderer?.detach()
                true
            })
        }
        renderer = null
    }

    private companion object {
        fun openGlCapabilities(): GLCapabilities {
            GLProfile.initSingleton()
            return GLCapabilities(GLProfile.get(GLProfile.GL3)).apply {
                doubleBuffered = true
                depthBits = 0
                stencilBits = 0
                sampleBuffers = false
            }
        }
    }
}
