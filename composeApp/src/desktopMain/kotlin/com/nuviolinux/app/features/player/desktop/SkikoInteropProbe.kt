package com.nuviolinux.app.features.player.desktop

import org.jetbrains.skia.DirectContext

/**
 * SPIKE (2026-08-24, harbor-pipeline.md option A): skiko GL-texture interop
 * feasibility probe. Walks the AWT component tree to the scene's SkiaLayer and
 * reflects into redrawerManager → redrawer → contextHandler → DirectContext.
 *
 * If [probedContext] comes back non-null, mpv can render into an FBO owned by
 * skiko's own GL context (created during a Compose draw, where the context is
 * current) and the frame can enter the Compose scene as a Skia surface
 * snapshot — deleting the entire readback/producer/slot pipeline.
 *
 * Reflection is spike-grade: field names are skiko-internal and may change
 * between versions. A production path needs a fallback (the current readback
 * pipeline) and a skiko-version guard.
 */
object SkikoInteropProbe {
    @Volatile
    var probedContext: DirectContext? = null
        private set

    @Volatile
    var rendererInfo: String? = null
        private set

    @Volatile
    private var probed = false

    fun probeOnce() {
        if (probed) return
        val win = runCatching {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        }.getOrNull()
        if (win == null) return // not on EDT / no window yet — retry next tick
        probed = true
        try {
            val layer = findSkiaLayer(win)
            if (layer == null) {
                println("[SkikoInterop] no SkiaLayer found in window ${win.name}")
                dumpTree(win, 0)
                return
            }
            // The layer instance may be an anonymous SkiaLayer subclass —
            // walk up to the declaring class for its private fields.
            val rm = fieldUpTheHierarchy(layer, "redrawerManager")?.apply { isAccessible = true }?.get(layer)
                ?: run { println("[SkikoInterop] redrawerManager field not found"); return }
            val redrawer = rm.javaClass.getMethod("getRedrawer").invoke(rm)
            val handler = fieldUpTheHierarchy(redrawer, "contextHandler")
                ?.apply { isAccessible = true }?.get(redrawer)
                ?: run { println("[SkikoInterop] contextHandler field not found"); return }
            val ctx = methodUpTheHierarchy(handler, "getContext")
                ?.apply { isAccessible = true }?.invoke(handler) as? DirectContext
            val info = methodUpTheHierarchy(handler, "rendererInfo")
                ?.apply { isAccessible = true }?.invoke(handler) as? String
            probedContext = ctx
            rendererInfo = info
            println("[SkikoInterop] DirectContext=${ctx != null} renderer=$info")
            if (ctx != null) verifyGlInterop(ctx)
        } catch (t: Throwable) {
            println("[SkikoInterop] probe failed: $t")
        }
    }

    private fun dumpTree(c: java.awt.Component, depth: Int) {
        if (depth > 6) return
        println("[SkikoInterop] tree: ${"  ".repeat(depth)}${c.javaClass.name}")
        if (c is java.awt.Container) {
            for (child in c.components) dumpTree(child, depth + 1)
        }
    }

    private fun methodUpTheHierarchy(obj: Any, name: String): java.lang.reflect.Method? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredMethod(name)
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            }
        }
        return null
    }

    /**
     * SPIKE step 2: create an FBO in skiko's current GL context (we are inside
     * a Compose draw here), wrap it as a Skia Surface, snapshot, and read a
     * pixel back through SKIA (not GL) to prove the zero-copy path works.
     */
    private fun verifyGlInterop(ctx: org.jetbrains.skia.DirectContext) {
        try {
            val packed = NativePlayerBridge.skikoCreateTestFbo(64, 64)
            if (packed <= 0) {
                println("[SkikoInterop] FBO creation failed (GL entry points or context)")
                return
            }
            val fboId = (packed shr 32).toInt()
            val rt = org.jetbrains.skia.BackendRenderTarget.Companion.makeGL(
                64, 64, /*sampleCnt=*/0, /*stencilBits=*/0, fboId,
                /*glFormat=*/0x8058 /* GL_RGBA8 */,
            )
            val surface = org.jetbrains.skia.Surface.makeFromBackendRenderTarget(
                ctx, rt, org.jetbrains.skia.SurfaceOrigin.TOP_LEFT,
                org.jetbrains.skia.SurfaceColorFormat.RGBA_8888,
                org.jetbrains.skia.ColorSpace.sRGB,
                null,
            )
            if (surface == null) {
                println("[SkikoInterop] makeFromBackendRenderTarget returned null")
                return
            }
            val image = surface.makeImageSnapshot()
            val info = org.jetbrains.skia.ImageInfo.Companion.makeN32(64, 64, org.jetbrains.skia.ColorAlphaType.OPAQUE)
            val data = org.jetbrains.skia.Data.Companion.makeUninitialized(64 * 64 * 4)
            val pm = org.jetbrains.skia.Pixmap()
            pm.reset(info, data, 64 * 4)
            val ok = image.readPixels(pm, 0, 0, false)
            var px: Int = -1
            if (ok) {
                val bytes = data.getBytes(0, 64 * 64 * 4)
                val o = (32 * 64 + 32) * 4
                px = ((bytes[o + 3].toInt() and 0xFF) shl 24) or
                     ((bytes[o + 0].toInt() and 0xFF) shl 16) or
                     ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                      (bytes[o + 2].toInt() and 0xFF)
            }
            println("[SkikoInterop] GL->Skia bridge: readPixels=$ok pixel@32,32=0x${px.toString(16)} (expect ffFF00FF magenta)")
            surface.close()
            rt.close()
        } catch (t: Throwable) {
            println("[SkikoInterop] verifyGlInterop failed: $t")
        }
    }

    private fun fieldUpTheHierarchy(obj: Any, name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun findSkiaLayer(c: java.awt.Component): java.awt.Component? {
        if (org.jetbrains.skiko.SkiaLayer::class.java.isInstance(c)) return c
        if (c is java.awt.Container) {
            for (child in c.components) {
                findSkiaLayer(child)?.let { return it }
            }
        }
        return null
    }
}
