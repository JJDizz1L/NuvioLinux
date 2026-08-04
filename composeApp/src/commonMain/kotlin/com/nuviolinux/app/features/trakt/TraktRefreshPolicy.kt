package com.nuviolinux.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuviolinux.app.features.tracking.TrackingRefreshIntent

internal const val TRAKT_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
internal const val TRAKT_AUTOMATIC_REFRESH_INTERVAL_MS =
    TRAKT_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L

/**
 * Freshness gate for automatic Trakt refreshes (mirrors SimklRefreshPolicy):
 * user-initiated refreshes always run; AUTOMATIC refreshes are limited to one
 * per [TRAKT_AUTOMATIC_REFRESH_INTERVAL_MS] (errors/unknown state bypass).
 */
internal fun shouldRunTraktRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = TRAKT_AUTOMATIC_REFRESH_INTERVAL_MS,
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true

    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

/**
 * Periodic Trakt refresh used when Trakt is the active library/watch-progress
 * source: the nuvio settings pull deliberately skips Trakt sources, so this
 * keeps library + progress fresh on the same cadence Simkl uses.
 */
internal object TraktAutomaticRefresh {
    private val log = Logger.withTag("TraktAutomaticRefresh")

    @Volatile
    private var lastAutomaticRefreshAtEpochMs: Long? = null

    suspend fun refreshIfDue(): Boolean {
        val nowEpochMs = TraktPlatformClock.nowEpochMs()
        if (
            !shouldRunTraktRefresh(
                intent = TrackingRefreshIntent.AUTOMATIC,
                lastCheckedAtEpochMs = lastAutomaticRefreshAtEpochMs,
                nowEpochMs = nowEpochMs,
                hasError = false,
            )
        ) {
            return false
        }
        runCatching { TraktLibraryRepository.refreshNow() }
            .onFailure { error -> log.w { "Periodic Trakt library refresh failed: ${error.message}" } }
        runCatching { TraktProgressRepository.invalidateAndRefresh() }
            .onFailure { error -> log.w { "Periodic Trakt progress refresh failed: ${error.message}" } }
        lastAutomaticRefreshAtEpochMs = nowEpochMs
        return true
    }
}
