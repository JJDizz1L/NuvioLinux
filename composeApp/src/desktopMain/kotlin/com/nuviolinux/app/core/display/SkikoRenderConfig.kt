package com.nuviolinux.app.core.display

import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Configures the Skiko render API before any AWT/Skiko initialization.
 *
 * Compose Desktop's default Skiko renderer is OpenGL (GLX via GLFW on X11).
 * NVIDIA's GLX and EGL stacks cannot coexist in one process: once Skiko has
 * a GLX context current on the EDT, the player bridge's offscreen EGL
 * display rejects eglMakeCurrent (it fails with EGL_NOT_INITIALIZED on the
 * NVIDIA vendor), so the video renderer silently falls back to software.
 * Mesa tolerates the coexistence (AMD/Intel are unaffected).
 *
 * Detecting the NVIDIA proprietary driver and switching Skiko to its
 * software rasterizer keeps the UI rendering on the CPU while the bridge
 * gets a working EGL context for GPU video decoding/rendering (nvdec).
 * A user-set `skiko.renderApi` property or `SKIKO_RENDER_API` env always
 * wins (override escape hatch).
 */
object SkikoRenderConfig {
    fun applyIfNeeded() {
        if (System.getProperty("skiko.renderApi") != null || System.getenv("SKIKO_RENDER_API") != null) {
            return
        }
        if (isNvidiaProprietaryDriver()) {
            Logger.withTag("SkikoRenderConfig").i {
                "NVIDIA proprietary driver detected; forcing Skiko software renderer " +
                    "(NVIDIA GLX/EGL cannot coexist in-process — the player bridge's EGL " +
                    "context needs a clean EGL state)"
            }
            System.setProperty("skiko.renderApi", "SOFTWARE")
        }
    }

    private fun isNvidiaProprietaryDriver(): Boolean =
        Files.exists(Path.of("/sys/module/nvidia")) || Files.exists(Path.of("/dev/nvidiactl"))
}