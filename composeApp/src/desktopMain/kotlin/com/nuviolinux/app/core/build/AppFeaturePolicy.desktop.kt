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
    actual val notificationsEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = true
    actual val accountDeletionEnabled: Boolean = false
    actual val personalMediaAddonCopyEnabled: Boolean = false
    actual val p2pEnabled: Boolean = true
    actual val externalPlayerSupported: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    actual val heroTrailerPlaybackSupported: Boolean = false
    // In-app updates are disabled for now: Linux builds are updated through
    // the system package manager, so the GitHub-release banner isn't useful.
    // The updater code stays intact — re-enable with `!isFlatpakRuntime` above.
    actual val inAppUpdaterEnabled: Boolean = false
    // Instead of the download/install banner, surface a new release as an
    // in-app toast that links to the GitHub releases page.
    actual val updateNotificationEnabled: Boolean = true
    actual val imdbRatingLogoEnabled: Boolean = true
}
