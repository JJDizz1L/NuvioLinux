package com.nuviolinux.app.features.downloads

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private val store = DesktopStorage.store("downloads")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("downloads"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("downloads"), payload)
    }
}
