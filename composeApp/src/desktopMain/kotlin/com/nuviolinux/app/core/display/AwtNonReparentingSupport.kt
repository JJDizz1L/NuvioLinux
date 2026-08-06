package com.nuviolinux.app.core.display

import co.touchlab.kermit.Logger
import com.nuviolinux.app.features.player.desktop.NativePlayerBridge
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Applies the JDK's non-reparenting window-manager workaround for tiling
 * window managers (niri, sway, i3, bspwm, and the rest of the family).
 *
 * Tiling compositors do not wrap client windows in a WM-managed frame, so the
 * X11 AWT toolkit misjudges the real window size and keeps rendering into a
 * small box while the rest of the window stays black. Setting
 * `_JAVA_AWT_WM_NONREPARENTING=1` tells AWT to skip the reparenting
 * assumption entirely.
 *
 * The JDK reads the variable via native getenv(3) on the first X11 toolkit
 * use and caches it, so it must be set in-process BEFORE any AWT access. This
 * is done through a tiny JNI call into the player bridge ([applyIfNeeded] is
 * the first statement of `main`). Detection is conditional — only known
 * non-reparenting window managers trigger it, so reparenting desktops (KDE,
 * GNOME, XFCE, …) are never affected.
 */
internal object AwtNonReparentingSupport {
    private val log = Logger.withTag("WindowEnvironment")

    private val tilingWindowManagerMarkers = listOf(
        "niri", "sway", "i3", "bspwm", "hyprland", "river", "qtile", "xmonad",
        "awesome", "dwm", "herbstluftwm", "leftwm", "spectrwm", "stumpwm",
        "ratpoison", "wmii", "monsterwm", "notion", "wayfire", "labwc",
        "cage", "weston", "cosmic", "gamescope",
    )

    fun applyIfNeeded() {
        // The user already opted in (or out) manually — respect that.
        if (System.getenv("_JAVA_AWT_WM_NONREPARENTING") != null) return
        if (System.getenv("DISPLAY").isNullOrBlank()) return // no X11 AWT toolkit
        // Explicit override (testing / detection fallback).
        if (System.getenv("NUVIO_FORCE_NONREPARENTING") == "1") {
            log.i { "non-reparenting mode forced via NUVIO_FORCE_NONREPARENTING=1" }
            enable()
            return
        }
        val windowManager = detectWindowManager() ?: return
        val isTiling = tilingWindowManagerMarkers.any { windowManager.contains(it) }
        if (!isTiling) return
        log.i { "non-reparenting (tiling) window manager detected: $windowManager — enabling _JAVA_AWT_WM_NONREPARENTING" }
        enable()
    }

    private fun enable() {
        // Belt-and-braces for JDKs that honor the property; JDK 21 only reads
        // the environment variable (via native getenv).
        System.setProperty("sun.awt.nonreparenting", "true")
        runCatching { NativePlayerBridge.setAwtNonReparenting() }
            .onFailure { log.w { "failed to enable non-reparenting mode: $it" } }
    }

    private fun detectWindowManager(): String? {
        xpropRootWindowManagerName()?.let { return it }
        xpropSupportingCheckName()?.let { return it }
        return envWindowManagerHint()
    }

    /** `xprop -root _NET_WM_NAME` — many WMs publish their name on the root window. */
    private fun xpropRootWindowManagerName(): String? =
        runXprop("-root", "_NET_WM_NAME")?.windowNameFromXprop()

    /** Fallback: follow `_NET_SUPPORTING_WM_CHECK` to the WM window and read its name. */
    private fun xpropSupportingCheckName(): String? {
        val windowId = runXprop("-root", "_NET_SUPPORTING_WM_CHECK")?.windowIdFromXprop() ?: return null
        return runXprop("-id", windowId, "_NET_WM_NAME")?.windowNameFromXprop()
    }

    private fun runXprop(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("xprop") + args)
            .redirectErrorStream(true)
            .start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(750L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else {
            output.get(250L, TimeUnit.MILLISECONDS)
                .trim()
                .lowercase()
                .takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun String.windowNameFromXprop(): String? =
        "\"(.*)\"".toRegex().find(this)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun String.windowIdFromXprop(): String? =
        "# 0x([0-9a-f]+)".toRegex().find(this)?.groupValues?.get(1)

    /** Env-var fallback for Wayland compositors (no X11 WM name to read). */
    private fun envWindowManagerHint(): String? {
        listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "DESKTOP_SESSION")
            .mapNotNull { System.getenv(it) }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it.trim().lowercase() }
        if (!System.getenv("SWAYSOCK").isNullOrBlank()) return "sway"
        if (!System.getenv("HYPRLAND_INSTANCE_SIGNATURE").isNullOrBlank()) return "hyprland"
        return null
    }
}
