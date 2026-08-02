package com.nuviolinux.app.features.discord

import kotlinx.serialization.json.JsonObject

internal expect object DiscordSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadHideTitle(): Boolean?
    fun saveHideTitle(enabled: Boolean)
    fun loadShowWhenPaused(): Boolean?
    fun saveShowWhenPaused(enabled: Boolean)
    fun loadShowWhenBrowsing(): Boolean?
    fun saveShowWhenBrowsing(enabled: Boolean)
    fun loadShowPoster(): Boolean?
    fun saveShowPoster(enabled: Boolean)
    fun loadShowTimestamp(): Boolean?
    fun saveShowTimestamp(enabled: Boolean)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
