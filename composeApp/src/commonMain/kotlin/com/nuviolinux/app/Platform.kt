package com.nuviolinux.app

import io.github.jan.supabase.auth.SessionManager

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isDesktop: Boolean

internal expect fun platformSessionManager(): SessionManager
