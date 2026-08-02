package com.nuviolinux.app.features.library

import com.nuviolinux.app.core.storage.DesktopStorage

internal actual object LibraryStorage {
    private val store = DesktopStorage.store("library")

    actual fun loadPayload(profileId: Int): String? =
        store.getString("library_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString("library_$profileId", payload)
    }
}
