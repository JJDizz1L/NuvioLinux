package com.nuviolinux.app.features.plugins

import com.nuviolinux.app.core.storage.DesktopStorage

internal object PluginStorage {
    private const val pluginsStateKey = "plugins_state"
    private val store = DesktopStorage.store("plugins")

    fun loadState(profileId: Int): String? =
        store.getString("${pluginsStateKey}_$profileId")

    fun saveState(profileId: Int, payload: String) {
        store.putString("${pluginsStateKey}_$profileId", payload)
    }

    fun hasScraperCode(profileId: Int, scraperId: String): Boolean =
        store.contains(scraperCodeKey(profileId, scraperId))

    fun loadScraperCode(profileId: Int, scraperId: String): String? =
        store.getString(scraperCodeKey(profileId, scraperId))

    fun saveScraperCode(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean {
        val key = scraperCodeKey(profileId, scraperId)
        if (!overwrite && store.contains(key)) return true
        return runCatching { store.putString(key, code) }.isSuccess
    }

    fun loadScraperSettings(scraperId: String): String? =
        store.getString("settings_${scraperId}")

    fun saveScraperSettings(scraperId: String, payload: String) {
        store.putString("settings_${scraperId}", payload)
    }

    private fun scraperCodeKey(profileId: Int, scraperId: String): String =
        "scraper_code_${profileId}_${pluginDigestHex("SHA256", scraperId)}"
}

internal fun currentPluginPlatform(): String = "desktop"

internal fun currentPluginPlatformTags(): Set<String> =
    buildSet {
        add(currentPluginPlatform())
        add("jvm")
        add("linux")
    }

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
