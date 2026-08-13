package com.nuviolinux.app.core.display

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayServerDetectorTest {
    @Test
    fun `x11 session with an X11 display maps to X11`() {
        assertEquals(
            DisplayServer.X11,
            DisplayServerDetector.detect(
                sessionType = "x11",
                hasWaylandDisplay = false,
                hasX11Display = true,
            ),
        )
    }

    @Test
    fun `wayland session with an X11 display maps to XWayland`() {
        assertEquals(
            DisplayServer.XWayland,
            DisplayServerDetector.detect(
                sessionType = "wayland",
                hasWaylandDisplay = true,
                hasX11Display = true,
            ),
        )
    }

    @Test
    fun `wayland session with only a Wayland display maps to Wayland`() {
        assertEquals(
            DisplayServer.Wayland,
            DisplayServerDetector.detect(
                sessionType = "wayland",
                hasWaylandDisplay = true,
                hasX11Display = false,
            ),
        )
    }

    @Test
    fun `wayland session with no displays maps to Unknown`() {
        assertEquals(
            DisplayServer.Unknown,
            DisplayServerDetector.detect(
                sessionType = "wayland",
                hasWaylandDisplay = false,
                hasX11Display = false,
            ),
        )
    }

    @Test
    fun `unknown session type with an X11 display maps to X11`() {
        assertEquals(
            DisplayServer.X11,
            DisplayServerDetector.detect(
                sessionType = null,
                hasWaylandDisplay = false,
                hasX11Display = true,
            ),
        )
    }

    @Test
    fun `unknown session type with only a Wayland display maps to Wayland`() {
        assertEquals(
            DisplayServer.Wayland,
            DisplayServerDetector.detect(
                sessionType = null,
                hasWaylandDisplay = true,
                hasX11Display = false,
            ),
        )
    }

    @Test
    fun `no displays maps to Unknown`() {
        assertEquals(
            DisplayServer.Unknown,
            DisplayServerDetector.detect(
                sessionType = null,
                hasWaylandDisplay = false,
                hasX11Display = false,
            ),
        )
    }

    @Test
    fun `x11 session type wins over a stray Wayland display`() {
        assertEquals(
            DisplayServer.X11,
            DisplayServerDetector.detect(
                sessionType = "x11",
                hasWaylandDisplay = true,
                hasX11Display = true,
            ),
        )
    }
}
