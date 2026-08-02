package com.nuviolinux.app.features.watchprogress

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object ContinueWatchingPreferencesStorage {
    private val store = DesktopStorage.store("continue_watching_preferences")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("continue_watching_preferences"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("continue_watching_preferences"), payload)
    }
}
