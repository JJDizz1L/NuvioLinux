package com.nuvio.app.features.plugins

import com.nuvio.app.core.storage.DesktopStorage

internal object PluginStorage {
    private const val pluginsStateKey = "plugins_state"
    private val store = DesktopStorage.store("nuvio_plugins")

    fun loadState(profileId: Int): String? =
        store.getString("${pluginsStateKey}_$profileId")

    fun saveState(profileId: Int, payload: String) {
        store.putString("${pluginsStateKey}_$profileId", payload)
    }

    fun loadScraperSettings(scraperId: String): String? =
        store.getString("settings_${scraperId}")

    fun saveScraperSettings(scraperId: String, payload: String) {
        store.putString("settings_${scraperId}", payload)
    }
}

internal fun currentPluginPlatform(): String = "desktop"

internal fun currentPluginPlatformTags(): Set<String> =
    buildSet {
        add(currentPluginPlatform())
        add("jvm")
        add("linux")
    }

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
