package com.nuviolinux.app.core.display

/**
 * Display server environment, detected from the standard XDG variables.
 *
 * The app always renders through the AWT X11 toolkit (Skiko/Compose Desktop
 * have no native Wayland backend yet), so on a Wayland session it runs under
 * XWayland. Knowing which case we are in lets the window layer pick stable
 * parameters per environment.
 */
enum class DisplayServer {
    /** Native X11 session. */
    X11,

    /** Wayland session with XWayland available (DISPLAY is set). */
    XWayland,

    /** Pure Wayland session with no XWayland — the AWT window cannot start. */
    Wayland,

    Unknown,
}

object DisplayServerDetector {
    fun detect(): DisplayServer = detect(
        sessionType = System.getenv("XDG_SESSION_TYPE")?.trim()?.lowercase(),
        hasWaylandDisplay = !System.getenv("WAYLAND_DISPLAY").isNullOrBlank(),
        hasX11Display = !System.getenv("DISPLAY").isNullOrBlank(),
    )

    /** Pure session→server mapping, exposed for tests (no environment access). */
    internal fun detect(
        sessionType: String?,
        hasWaylandDisplay: Boolean,
        hasX11Display: Boolean,
    ): DisplayServer = when {
        sessionType == "wayland" && hasX11Display -> DisplayServer.XWayland
        sessionType == "wayland" && !hasX11Display && hasWaylandDisplay -> DisplayServer.Wayland
        sessionType == "x11" && hasX11Display -> DisplayServer.X11
        hasX11Display -> DisplayServer.X11
        hasWaylandDisplay -> DisplayServer.Wayland
        else -> DisplayServer.Unknown
    }

    /** True when running under XWayland: a Wayland session with an X11 display. */
    fun isXWayland(): Boolean = detect() == DisplayServer.XWayland
}
