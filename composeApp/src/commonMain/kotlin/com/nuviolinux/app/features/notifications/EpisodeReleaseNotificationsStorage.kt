package com.nuviolinux.app.features.notifications

internal expect object EpisodeReleaseNotificationsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}