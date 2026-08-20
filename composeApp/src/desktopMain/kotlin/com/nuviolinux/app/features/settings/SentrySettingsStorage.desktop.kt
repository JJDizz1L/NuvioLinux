package com.nuviolinux.app.features.settings

import com.nuviolinux.app.core.storage.DesktopStorage

// DEADCODE: crash reporting fully disabled on Linux — isSupported always false
// gates AdvancedSettingsPage.kt:82 + SettingsSearch.kt:412. SentryConfig DSN
// (composeApp/build.gradle.kts:78) has zero consumers, no Sentry.init() call.
// See DEADCODE.md §3.
internal actual object SentrySettingsPlatform {
    actual val crashReportsSupported: Boolean = false
}

internal actual object SentrySettingsStorage {
    private const val enabledKey = "enabled"
    private val store = DesktopStorage.store("sentry_settings")

    actual fun loadEnabled(): Boolean? =
        if (store.contains(enabledKey)) store.getBoolean(enabledKey) else null

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(enabledKey, enabled)
    }
}
