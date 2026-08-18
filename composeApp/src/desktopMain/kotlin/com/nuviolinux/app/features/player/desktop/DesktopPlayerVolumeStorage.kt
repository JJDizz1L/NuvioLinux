package com.nuviolinux.app.features.player.desktop

import com.nuviolinux.app.core.storage.DesktopStorage
import javax.swing.Timer

internal object DesktopPlayerVolumeStorage {
    private const val VolumeLevelKey = "volume_level"
    private const val PersistDelayMs = 250

    private val store = DesktopStorage.store("nuvio_player_runtime")

    private var pendingVolumeLevel: Float? = null

    private val persistTimer = Timer(PersistDelayMs) {
        pendingVolumeLevel?.let { level ->
            store.putFloat(VolumeLevelKey, level)
            pendingVolumeLevel = null
        }
    }.apply {
        isRepeats = false
    }

    fun loadVolumeLevel(): Float? =
        store.getFloat(VolumeLevelKey)?.coerceIn(0f, 1f)

    fun saveVolumeLevel(level: Float) {
        pendingVolumeLevel = level.coerceIn(0f, 1f)
        persistTimer.restart()
    }
}
