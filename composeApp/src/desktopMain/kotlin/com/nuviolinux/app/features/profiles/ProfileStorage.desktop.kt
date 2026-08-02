package com.nuviolinux.app.features.profiles

import com.nuviolinux.app.core.storage.DesktopStorage

internal actual object ProfileStorage {
    private val store = DesktopStorage.store("profiles")

    actual fun loadPayload(): String? = store.getString("profiles")

    actual fun savePayload(payload: String) {
        store.putString("profiles", payload)
    }
}
