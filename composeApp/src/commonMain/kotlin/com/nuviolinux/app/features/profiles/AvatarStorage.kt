package com.nuviolinux.app.features.profiles

internal expect object AvatarStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}