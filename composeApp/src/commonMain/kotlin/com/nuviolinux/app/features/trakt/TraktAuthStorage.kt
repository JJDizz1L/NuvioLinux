package com.nuviolinux.app.features.trakt

internal expect object TraktAuthStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
    fun removeProfile(profileId: Int)
}
