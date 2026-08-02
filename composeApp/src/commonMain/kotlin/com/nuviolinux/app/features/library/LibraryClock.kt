package com.nuviolinux.app.features.library

internal expect object LibraryClock {
    fun nowEpochMs(): Long
}
