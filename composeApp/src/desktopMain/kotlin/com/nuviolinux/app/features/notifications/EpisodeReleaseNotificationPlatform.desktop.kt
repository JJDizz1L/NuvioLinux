package com.nuviolinux.app.features.notifications

// DEADCODE: desktop no-op — AppFeaturePolicy.notificationsEnabled=false gates UI
// (SettingsScreen.kt:105), yet repository still ensureLoaded() in App.kt:477.
// All methods return false/Unit. See DEADCODE.md §2/§3.
internal actual object EpisodeReleaseNotificationPlatform {
    actual suspend fun notificationsAuthorized(): Boolean = false

    actual suspend fun requestAuthorization(): Boolean = false

    actual suspend fun scheduleEpisodeReleaseNotifications(
        requests: List<EpisodeReleaseNotificationRequest>,
    ) = Unit

    actual suspend fun clearScheduledEpisodeReleaseNotifications() = Unit

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) = Unit
}
