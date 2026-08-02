package com.nuviolinux.app.features.library

internal expect object LibraryDisplaySettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
