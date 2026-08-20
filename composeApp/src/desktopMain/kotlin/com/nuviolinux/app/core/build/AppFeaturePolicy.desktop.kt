package com.nuviolinux.app.core.build

actual object AppFeaturePolicy {
    // Flatpak detection was only used by inAppUpdaterEnabled. Kept (commented)
    // so re-enabling in-app updates is a one-line change:
    //     actual val inAppUpdaterEnabled: Boolean = !isFlatpakRuntime
    // private val isFlatpakRuntime: Boolean =
    //     System.getenv("FLATPAK_ID") != null ||
    //         System.getProperty("user.home").orEmpty().startsWith("/app/") ||
    //         System.getProperty("java.home").orEmpty().startsWith("/app/")

    actual val pluginsEnabled: Boolean = true
    actual val downloadsEnabled: Boolean = true
    // DEADCODE: false on Linux — SettingsPage.Notifications gated in SettingsScreen.kt:105
    // and SettingsSearch.kt:268, yet EpisodeReleaseNotificationsRepository still
    // ensureLoaded() in App.kt:477. See DEADCODE.md §2.
    actual val notificationsEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = true
    // DEADCODE: false — AccountSettingsPage.kt:74 canDeleteAccount always false, API kept but unexposed. See DEADCODE.md §2.
    actual val accountDeletionEnabled: Boolean = false
    // DEADCODE: false — AddonsScreen.kt:97 usePersonalMediaCopy dead branch. See DEADCODE.md §2.
    actual val personalMediaAddonCopyEnabled: Boolean = false
    actual val p2pEnabled: Boolean = true
    // DEADCODE: false on Linux — external-player stack guarded at every call-site
    // (App.kt, StreamsScreen.kt, PlaybackSettingsPage.kt) but storage keys still
    // persisted in PlayerSettingsStorage.desktop.kt. See DEADCODE.md §2.
    actual val externalPlayerSupported: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    actual val heroTrailerPlaybackSupported: Boolean = true
    // In-app updates are disabled for now: Linux builds are updated through
    // the system package manager, so the GitHub-release banner isn't useful.
    // The updater code stays intact — re-enable with `!isFlatpakRuntime` above.
    // DEADCODE: false — AppUpdaterRepository/Controller/Banner/Notifier early-return;
    // AppUpdaterPlatform.desktop.kt:40 isSupported never used. See DEADCODE.md §2.
    actual val inAppUpdaterEnabled: Boolean = false
    // Instead of the download/install banner, surface a new release as an
    // in-app toast that links to the GitHub releases page.
    actual val updateNotificationEnabled: Boolean = true
    actual val imdbRatingLogoEnabled: Boolean = true
}
