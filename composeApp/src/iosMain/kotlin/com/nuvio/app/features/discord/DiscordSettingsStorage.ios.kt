package com.nuvio.app.features.discord

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults

actual object DiscordSettingsStorage {
    private const val enabledKey = "discord_enabled"
    private const val hideTitleKey = "discord_hide_title"
    private const val showWhenPausedKey = "discord_show_when_paused"
    private const val showWhenBrowsingKey = "discord_show_when_browsing"
    private const val showPosterKey = "discord_show_poster"
    private const val showTimestampKey = "discord_show_timestamp"
    private val syncKeys = listOf(
        enabledKey,
        hideTitleKey,
        showWhenPausedKey,
        showWhenBrowsingKey,
        showPosterKey,
        showTimestampKey,
    )

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        saveBoolean(enabledKey, enabled)
    }

    actual fun loadHideTitle(): Boolean? = loadBoolean(hideTitleKey)

    actual fun saveHideTitle(enabled: Boolean) {
        saveBoolean(hideTitleKey, enabled)
    }

    actual fun loadShowWhenPaused(): Boolean? = loadBoolean(showWhenPausedKey)

    actual fun saveShowWhenPaused(enabled: Boolean) {
        saveBoolean(showWhenPausedKey, enabled)
    }

    actual fun loadShowWhenBrowsing(): Boolean? = loadBoolean(showWhenBrowsingKey)

    actual fun saveShowWhenBrowsing(enabled: Boolean) {
        saveBoolean(showWhenBrowsingKey, enabled)
    }

    actual fun loadShowPoster(): Boolean? = loadBoolean(showPosterKey)

    actual fun saveShowPoster(enabled: Boolean) {
        saveBoolean(showPosterKey, enabled)
    }

    actual fun loadShowTimestamp(): Boolean? = loadBoolean(showTimestampKey)

    actual fun saveShowTimestamp(enabled: Boolean) {
        saveBoolean(showTimestampKey, enabled)
    }

    private fun loadBoolean(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val scopedKey = ProfileScopedKey.of(key)
        return if (defaults.objectForKey(scopedKey) != null) {
            defaults.boolForKey(scopedKey)
        } else {
            null
        }
    }

    private fun saveBoolean(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(key))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadHideTitle()?.let { put(hideTitleKey, encodeSyncBoolean(it)) }
        loadShowWhenPaused()?.let { put(showWhenPausedKey, encodeSyncBoolean(it)) }
        loadShowWhenBrowsing()?.let { put(showWhenBrowsingKey, encodeSyncBoolean(it)) }
        loadShowPoster()?.let { put(showPosterKey, encodeSyncBoolean(it)) }
        loadShowTimestamp()?.let { put(showTimestampKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncBoolean(hideTitleKey)?.let(::saveHideTitle)
        payload.decodeSyncBoolean(showWhenPausedKey)?.let(::saveShowWhenPaused)
        payload.decodeSyncBoolean(showWhenBrowsingKey)?.let(::saveShowWhenBrowsing)
        payload.decodeSyncBoolean(showPosterKey)?.let(::saveShowPoster)
        payload.decodeSyncBoolean(showTimestampKey)?.let(::saveShowTimestamp)
    }
}
