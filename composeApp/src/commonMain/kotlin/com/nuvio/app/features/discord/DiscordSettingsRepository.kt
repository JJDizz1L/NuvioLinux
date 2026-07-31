package com.nuvio.app.features.discord

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DiscordSettingsRepository {
    private val _uiState = MutableStateFlow(DiscordSettings())
    val uiState: StateFlow<DiscordSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var enabled = false
    private var hideTitle = false
    private var showWhenPaused = true
    private var showWhenBrowsing = true
    private var showPoster = true
    private var showTimestamp = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): DiscordSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (enabled == value) return
        enabled = value
        publish()
        DiscordSettingsStorage.saveEnabled(value)
    }

    fun setHideTitle(value: Boolean) {
        ensureLoaded()
        if (hideTitle == value) return
        hideTitle = value
        publish()
        DiscordSettingsStorage.saveHideTitle(value)
    }

    fun setShowWhenPaused(value: Boolean) {
        ensureLoaded()
        if (showWhenPaused == value) return
        showWhenPaused = value
        publish()
        DiscordSettingsStorage.saveShowWhenPaused(value)
    }

    fun setShowWhenBrowsing(value: Boolean) {
        ensureLoaded()
        if (showWhenBrowsing == value) return
        showWhenBrowsing = value
        publish()
        DiscordSettingsStorage.saveShowWhenBrowsing(value)
    }

    fun setShowPoster(value: Boolean) {
        ensureLoaded()
        if (showPoster == value) return
        showPoster = value
        publish()
        DiscordSettingsStorage.saveShowPoster(value)
    }

    fun setShowTimestamp(value: Boolean) {
        ensureLoaded()
        if (showTimestamp == value) return
        showTimestamp = value
        publish()
        DiscordSettingsStorage.saveShowTimestamp(value)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        enabled = DiscordSettingsStorage.loadEnabled() ?: false
        hideTitle = DiscordSettingsStorage.loadHideTitle() ?: false
        showWhenPaused = DiscordSettingsStorage.loadShowWhenPaused() ?: true
        showWhenBrowsing = DiscordSettingsStorage.loadShowWhenBrowsing() ?: true
        showPoster = DiscordSettingsStorage.loadShowPoster() ?: true
        showTimestamp = DiscordSettingsStorage.loadShowTimestamp() ?: true
        publish()
    }

    private fun publish() {
        _uiState.value = DiscordSettings(
            enabled = enabled,
            hideTitle = hideTitle,
            showWhenPaused = showWhenPaused,
            showWhenBrowsing = showWhenBrowsing,
            showPoster = showPoster,
            showTimestamp = showTimestamp,
        )
    }
}
