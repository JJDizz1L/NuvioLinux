package com.nuvio.app.features.discord

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object DiscordSettingsStorage {
    private const val preferencesName = "nuvio_discord_settings"
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

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

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

    private fun loadBoolean(key: String): Boolean? =
        preferences?.let { sharedPreferences ->
            val scopedKey = ProfileScopedKey.of(key)
            if (sharedPreferences.contains(scopedKey)) {
                sharedPreferences.getBoolean(scopedKey, false)
            } else {
                null
            }
        }

    private fun saveBoolean(key: String, enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(key), enabled)
            ?.apply()
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
        preferences?.edit()?.apply {
            syncKeys.forEach { remove(ProfileScopedKey.of(it)) }
        }?.apply()

        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncBoolean(hideTitleKey)?.let(::saveHideTitle)
        payload.decodeSyncBoolean(showWhenPausedKey)?.let(::saveShowWhenPaused)
        payload.decodeSyncBoolean(showWhenBrowsingKey)?.let(::saveShowWhenBrowsing)
        payload.decodeSyncBoolean(showPosterKey)?.let(::saveShowPoster)
        payload.decodeSyncBoolean(showTimestampKey)?.let(::saveShowTimestamp)
    }
}
