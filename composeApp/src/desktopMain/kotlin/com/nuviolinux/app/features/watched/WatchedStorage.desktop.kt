package com.nuviolinux.app.features.watched

import com.nuviolinux.app.core.storage.DesktopStorage

actual object WatchedStorage {
    private val store = DesktopStorage.store("watched")

    actual fun loadPayload(profileId: Int): String? =
        store.getString("watched_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString("watched_$profileId", payload)
    }
}
