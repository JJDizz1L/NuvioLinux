package com.nuviolinux.app.features.watchprogress

internal expect object WatchProgressClock {
    fun nowEpochMs(): Long
}
