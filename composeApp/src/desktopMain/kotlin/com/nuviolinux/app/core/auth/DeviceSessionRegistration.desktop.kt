package com.nuviolinux.app.core.auth

import java.net.InetAddress

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val osName = System.getProperty("os.name").orEmpty().trim().ifBlank { "Desktop" }
    val osVersion = System.getProperty("os.version").orEmpty().trim()
    val deviceName = sequenceOf(
        System.getenv("COMPUTERNAME"),
        System.getenv("HOSTNAME"),
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: "$osName device"
    val platform = listOf(osName, osVersion)
        .filter(String::isNotBlank)
        .joinToString(" ")

    return DeviceClientMetadata(
        clientName = "Nuvio Linux",
        deviceName = deviceName,
        platform = platform,
    )
}
