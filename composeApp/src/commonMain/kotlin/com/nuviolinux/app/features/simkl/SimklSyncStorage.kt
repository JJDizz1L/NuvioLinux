package com.nuviolinux.app.features.simkl

internal expect object SimklSyncStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
    fun removeProfile(profileId: Int)
}
