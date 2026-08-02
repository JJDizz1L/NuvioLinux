package com.nuviolinux.app.features.watched

actual object WatchedClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
