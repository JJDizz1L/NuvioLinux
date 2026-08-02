package com.nuviolinux.app.features.downloads

internal expect object DownloadsClock {
    fun nowEpochMs(): Long
}
