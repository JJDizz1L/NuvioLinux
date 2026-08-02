package com.nuviolinux.app.features.details

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object MetaScreenSettingsStorage {
    private val store = DesktopStorage.store("meta_screen_settings")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("meta_screen_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("meta_screen_settings"), payload)
    }
}
