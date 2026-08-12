package com.nuviolinux.app.core.sync

import com.nuviolinux.app.core.storage.DesktopStorage

internal actual object SyncClientIdentityStorage {
    private val store = DesktopStorage.store("sync_client_identity")

    actual fun loadClientId(): String? =
        store.getString("client_instance_id")

    actual fun saveClientId(clientId: String) {
        store.putString("client_instance_id", clientId)
    }

    actual fun clearClientId() {
        store.remove("client_instance_id")
    }
}
