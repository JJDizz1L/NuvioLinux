package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    private val isFlatpakRuntime: Boolean =
        System.getenv("FLATPAK_ID") != null ||
            System.getProperty("user.home").orEmpty().startsWith("/app/") ||
            System.getProperty("java.home").orEmpty().startsWith("/app/")

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
    actual val inAppUpdaterEnabled: Boolean = !isFlatpakRuntime
    actual val imdbRatingLogoEnabled: Boolean = true
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = false
}
