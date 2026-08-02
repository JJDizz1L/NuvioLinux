package com.nuviolinux.app

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isDesktop: Boolean = true
