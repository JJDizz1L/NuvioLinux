package com.nuviolinux.app.features.simkl

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object SimklSyncStorage {
    private const val payloadKey = "simkl_sync_snapshot"
    private val store = DesktopStorage.store("nuvio_simkl_sync")

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of(payloadKey, profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
    }

    actual fun removeProfile(profileId: Int) {
        store.remove(ProfileScopedKey.of(payloadKey, profileId))
    }
}
