package com.nuviolinux.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isDesktop: Boolean
