package com.nuviolinux.app.features.watchprogress

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object ContinueWatchingEnrichmentStorage {
    private val store = DesktopStorage.store("continue_watching_enrichment")

    actual fun loadPayload(key: String): String? =
        store.getString(ProfileScopedKey.of(key.scopedKey()))

    actual fun savePayload(key: String, payload: String) {
        store.putString(ProfileScopedKey.of(key.scopedKey()), payload)
    }

    actual fun removePayload(key: String) {
        store.remove(ProfileScopedKey.of(key.scopedKey()))
    }

    private fun String.scopedKey(): String = "continue_watching_enrichment_$this"
}
