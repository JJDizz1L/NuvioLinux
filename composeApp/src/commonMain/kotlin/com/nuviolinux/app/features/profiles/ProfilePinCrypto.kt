package com.nuviolinux.app.features.profiles

internal expect object ProfilePinCrypto {
    fun sha256Hex(value: String): String
}