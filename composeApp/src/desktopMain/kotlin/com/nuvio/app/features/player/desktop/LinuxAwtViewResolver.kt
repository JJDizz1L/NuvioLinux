package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.awt.Component

internal object LinuxAwtViewResolver {
    private val log = Logger.withTag("LinuxAwtViewResolver")
    private val componentPeerField by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        log.d { "resolveNativeViewPointer: component=$component displayable=${component.isDisplayable}" }

        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val peerClass = peer.javaClass
        val peerClassName = peerClass.name
        log.d { "peer class: $peerClassName" }

        val getWindow = findMethod(peerClass, "getWindow")
        val getHWnd = findMethod(peerClass, "getHWnd")

        log.d { "getWindow method found=${getWindow != null}, getHWnd found=${getHWnd != null}" }

        val xWindow = when {
            getWindow != null -> (getWindow.invoke(peer) as Number).toLong()
            getHWnd != null -> (getHWnd.invoke(peer) as Number).toLong()
            else -> error("Cannot resolve X11 window handle from peer: ${peerClass.name}")
        }

        log.d { "resolved xWindow=0x${xWindow.toString(16)}" }

        if (xWindow == 0L) error("X11 Window pointer was zero.")
        return xWindow
    }

    private fun findMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
}
