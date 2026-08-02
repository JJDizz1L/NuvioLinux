package com.nuviolinux.app.features.trakt

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object TraktAuthStorage {
    private val store = DesktopStorage.store("trakt_auth")
    private const val legacyPayloadKey = "trakt_auth"
    private const val payloadKey = "trakt_auth_payload"

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of(payloadKey, profileId))
            ?: migrateLegacyPrimaryProfilePayload(profileId)

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
    }

    private fun migrateLegacyPrimaryProfilePayload(profileId: Int): String? {
        if (profileId != 1) return null
        val payload = store.getString(legacyPayloadKey)?.takeIf { it.isNotBlank() } ?: return null
        store.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
        store.remove(legacyPayloadKey)
        return payload
    }
}

internal actual object TraktLibraryStorage {
    private val store = DesktopStorage.store("trakt_library")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("trakt_library"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("trakt_library"), payload)
    }
}

internal actual object TraktSettingsStorage {
    private val store = DesktopStorage.store("trakt_settings")
    private const val pendingWatchProgressSourceKey = "pending_watch_progress_source"

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("trakt_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("trakt_settings"), payload)
    }

    actual fun loadPendingWatchProgressSourcePayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId))

    actual fun savePendingWatchProgressSourcePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId), payload)
    }

    actual fun clearPendingWatchProgressSourcePayload(profileId: Int) {
        store.remove(ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId))
    }
}
