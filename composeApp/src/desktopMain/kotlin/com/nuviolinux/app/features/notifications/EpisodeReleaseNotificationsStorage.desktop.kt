package com.nuviolinux.app.features.notifications

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object EpisodeReleaseNotificationsStorage {
    private val store = DesktopStorage.store("episode_release_notifications")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("episode_release_notifications"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("episode_release_notifications"), payload)
    }
}
