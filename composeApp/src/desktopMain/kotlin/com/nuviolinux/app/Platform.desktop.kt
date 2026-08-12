package com.nuviolinux.app

import com.nuviolinux.app.core.auth.DesktopSupabaseSessionManager
import io.github.jan.supabase.auth.SessionManager

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isDesktop: Boolean = true

internal actual fun platformSessionManager(): SessionManager = DesktopSupabaseSessionManager()
