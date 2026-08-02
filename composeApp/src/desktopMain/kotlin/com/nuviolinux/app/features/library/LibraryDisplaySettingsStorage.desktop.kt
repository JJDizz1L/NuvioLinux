package com.nuviolinux.app.features.library

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object LibraryDisplaySettingsStorage {
    private const val payloadKey = "library_display_settings_payload"
    private val store = DesktopStorage.store("library_display_settings")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
