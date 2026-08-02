package com.nuviolinux.app.features.collection

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object CollectionStorage {
    private val store = DesktopStorage.store("collections")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("collections"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("collections"), payload)
    }
}
