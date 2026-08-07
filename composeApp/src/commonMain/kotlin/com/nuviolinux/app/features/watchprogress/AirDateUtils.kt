package com.nuviolinux.app.features.watchprogress

import androidx.compose.runtime.Composable
import com.nuviolinux.app.core.format.formatReleaseDateWithoutYear
import com.nuviolinux.app.core.time.daysUntilEpisodeRelease
import com.nuviolinux.app.core.time.parseEpisodeReleaseEpochMs
import nuviolinux.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun computeAirDateBadgeText(
    releasedIso: String?,
    todayIsoDate: String,
): String? {
    if (releasedIso.isNullOrBlank() || todayIsoDate.isBlank()) {
        return null
    }

    val releaseEpoch = parseEpisodeReleaseEpochMs(releasedIso)
    if (releaseEpoch != null && WatchProgressClock.nowEpochMs() >= releaseEpoch) {
        return null
    }

    val daysUntil = daysUntilEpisodeRelease(
        todayIsoDate = todayIsoDate,
        releasedDate = releasedIso,
    ) ?: return null

    return when {
        daysUntil < 0 -> null
        daysUntil == 0 -> stringResource(Res.string.cw_airs_today)
        daysUntil == 1 -> stringResource(Res.string.cw_airs_tomorrow)
        daysUntil in 2..7 -> pluralStringResource(Res.plurals.cw_airs_in_days, daysUntil, daysUntil)
        else -> {
            val formattedDate = formatReleaseDateWithoutYear(releasedIso)
            stringResource(Res.string.cw_airs_date, formattedDate)
        }
    }
}

fun parseReleaseDateToEpochMs(raw: String?): Long? {
    return parseEpisodeReleaseEpochMs(raw)
}

class ReleaseAlertState(
    val isReleaseAlert: Boolean,
    val isNewSeasonRelease: Boolean,
)

private const val ReleaseAlertWindowMs = 60L * 24 * 60 * 60 * 1000
private val NoReleaseAlertState = ReleaseAlertState(false, false)

fun calculateReleaseAlertState(
    seedLastUpdatedEpochMs: Long,
    seedSeasonNumber: Int?,
    nextSeasonNumber: Int?,
    releasedIso: String?,
): ReleaseAlertState {
    if (releasedIso.isNullOrBlank()) return NoReleaseAlertState

    val releaseEpoch = parseReleaseDateToEpochMs(releasedIso)
        ?: return NoReleaseAlertState

    val nowMs = WatchProgressClock.nowEpochMs()
    if (nowMs < releaseEpoch) return NoReleaseAlertState
    if (releaseEpoch <= seedLastUpdatedEpochMs) return NoReleaseAlertState
    if (nowMs - releaseEpoch >= ReleaseAlertWindowMs) return NoReleaseAlertState

    val isNewSeasonRelease =
        seedSeasonNumber != null &&
        nextSeasonNumber != null &&
        nextSeasonNumber != seedSeasonNumber

    return ReleaseAlertState(
        isReleaseAlert = true,
        isNewSeasonRelease = isNewSeasonRelease
    )
}
