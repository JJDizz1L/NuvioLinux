package com.nuviolinux.app.features.downloads

internal actual object DownloadsClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
