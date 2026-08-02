package com.nuviolinux.app.features.details

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey

internal actual object SeasonViewModeStorage {
    private val store = DesktopStorage.store("season_view_mode")

    actual fun load(): SeasonViewMode? =
        SeasonViewMode.parse(store.getString(ProfileScopedKey.of("season_view_mode")))

    actual fun save(mode: SeasonViewMode) {
        store.putString(ProfileScopedKey.of("season_view_mode"), SeasonViewMode.persist(mode))
    }
}
