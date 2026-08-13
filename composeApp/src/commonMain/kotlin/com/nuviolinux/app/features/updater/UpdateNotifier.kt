package com.nuviolinux.app.features.updater

import com.nuviolinux.app.core.build.AppFeaturePolicy
import com.nuviolinux.app.core.ui.NuvioToastController
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.updates_toast_available
import org.jetbrains.compose.resources.getString

private const val releasesListUrl = "https://github.com/JJDizz1L/NuvioLinux/releases"

/**
 * Replaces the disabled download/install banner with a lightweight, toast-only
 * release check. When a newer release is found, it shows an in-app toast that
 * links to the GitHub releases page; it never downloads or installs anything.
 *
 * Notifies once per new release tag (persisted via AppUpdaterPlatform) and
 * fails silently on network/API errors.
 */
object UpdateNotifier {
    private var checkStarted = false

    suspend fun notifyIfUpdateAvailable() {
        if (checkStarted || !AppFeaturePolicy.updateNotificationEnabled || !AppUpdaterPlatform.isSupported) {
            return
        }
        checkStarted = true

        AppUpdaterRepository.getLatestReleaseInfo().onSuccess { release ->
            val remoteNewer = VersionUtils.isRemoteNewer(release.tag, AppUpdaterPlatform.currentVersionName)
            val alreadyNotified = AppUpdaterPlatform.getLastNotifiedTag() == release.tag
            if (remoteNewer && !alreadyNotified) {
                AppUpdaterPlatform.setLastNotifiedTag(release.tag)
                NuvioToastController.show(
                    message = getString(Res.string.updates_toast_available, release.tag),
                    durationMillis = 6000L,
                    actionUri = releasesListUrl,
                )
            }
        }
    }
}
