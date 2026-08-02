package com.nuviolinux.app.features.profiles

import com.nuviolinux.app.core.storage.DesktopStorage

internal actual object AvatarStorage {
    private val store = DesktopStorage.store("avatars")

    actual fun loadPayload(): String? = store.getString("avatars")

    actual fun savePayload(payload: String) {
        store.putString("avatars", payload)
    }
}
