package com.nuvio.app.features.profiles

internal expect object ProfileHoverHapticFeedback {
    fun prepare()
    fun release()
}
