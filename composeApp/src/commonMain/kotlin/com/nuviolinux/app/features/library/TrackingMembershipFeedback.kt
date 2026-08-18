package com.nuviolinux.app.features.library

import com.nuviolinux.app.core.ui.NuvioToastController
import com.nuviolinux.app.features.tracking.TrackingLibraryTab
import com.nuviolinux.app.features.tracking.TrackingMembershipApplyResult
import com.nuviolinux.app.features.tracking.TrackingMembershipResolution
import com.nuviolinux.app.features.tracking.TrackingProviderRegistry
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.tracking_list_item_added
import nuviolinux.composeapp.generated.resources.tracking_list_status_rewritten
import org.jetbrains.compose.resources.getString

/**
 * Shows feedback after a library membership apply (the "+"/save toggle).
 * A rewritten list status gets its own toast; a plain successful add gets a
 * confirmation toast too — without it, a working add is indistinguishable
 * from a silent no-op.
 */
internal suspend fun showTrackingMembershipApplyFeedback(result: TrackingMembershipApplyResult) {
    val rewrite = result.rewrites.firstOrNull()
    if (rewrite != null) {
        showRewriteToast(rewrite)
        return
    }
    val resolution = result.resolutions.firstOrNull() ?: return
    val providerName = providerDisplayName(resolution.providerId)
    val tabs = TrackingProviderRegistry.libraryProvider(resolution.providerId)?.snapshot()?.tabs.orEmpty()
    val listTitle = tabs.statusTitle(resolution.resolvedListKey, providerName)
    NuvioToastController.show(
        getString(
            Res.string.tracking_list_item_added,
            providerName,
            listTitle,
        ),
    )
}

private suspend fun showRewriteToast(rewrite: TrackingMembershipResolution) {
    val providerName = providerDisplayName(rewrite.providerId)
    val tabs = TrackingProviderRegistry.libraryProvider(rewrite.providerId)?.snapshot()?.tabs.orEmpty()
    val requestedTitle = tabs.statusTitle(rewrite.requestedListKey, providerName)
    val resolvedTitle = tabs.statusTitle(rewrite.resolvedListKey, providerName)
    NuvioToastController.show(
        getString(
            Res.string.tracking_list_status_rewritten,
            providerName,
            resolvedTitle,
            requestedTitle,
        ),
    )
}

internal suspend fun showTrackingMembershipRewriteFeedback(result: TrackingMembershipApplyResult) {
    val rewrite = result.rewrites.firstOrNull() ?: return
    val providerName = providerDisplayName(rewrite.providerId)
    val tabs = TrackingProviderRegistry.libraryProvider(rewrite.providerId)?.snapshot()?.tabs.orEmpty()
    val requestedTitle = tabs.statusTitle(rewrite.requestedListKey, providerName)
    val resolvedTitle = tabs.statusTitle(rewrite.resolvedListKey, providerName)
    NuvioToastController.show(
        getString(
            Res.string.tracking_list_status_rewritten,
            providerName,
            resolvedTitle,
            requestedTitle,
        ),
    )
}

private fun providerDisplayName(providerId: com.nuviolinux.app.features.tracking.TrackingProviderId): String =
    TrackingProviderRegistry.authProvider(providerId)
        ?.descriptor
        ?.displayName
        ?: providerId.storageId.replaceFirstChar { char -> char.titlecase() }

private fun List<TrackingLibraryTab>.statusTitle(
    key: String,
    providerName: String,
): String = firstOrNull { tab -> tab.key == key }
    ?.title
    ?.removePrefix("$providerName ")
    ?: key.substringAfterLast(':')
