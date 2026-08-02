package com.nuviolinux.app.features.settings

import com.nuviolinux.app.core.storage.DesktopStorage

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
