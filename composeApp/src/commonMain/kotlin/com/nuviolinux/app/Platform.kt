package com.nuviolinux.app

import io.github.jan.supabase.auth.SessionManager

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isDesktop: Boolean

internal expect fun platformSessionManager(): SessionManager

/** True when verbose console logging was requested (NUVIO_LOGS=1 on desktop). */
internal expect val verboseLoggingEnabled: Boolean
