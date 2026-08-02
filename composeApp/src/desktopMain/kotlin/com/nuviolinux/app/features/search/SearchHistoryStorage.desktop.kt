package com.nuviolinux.app.features.search

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object SearchHistoryStorage {
    private val store = DesktopStorage.store("search_history")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("search_history"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("search_history"), payload)
    }
}
