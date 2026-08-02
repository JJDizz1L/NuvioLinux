package com.nuviolinux.app.features.watchprogress

import com.nuviolinux.app.features.details.MetaDetailsRepository
import com.nuviolinux.app.features.details.nextReleasedEpisodeAfter

object ResumePromptRepository {

    fun markPlayerEntered(videoId: String) {
        ResumePromptStorage.saveWasInPlayer(true)
        ResumePromptStorage.saveLastPlayerVideoId(videoId)
    }

    fun markPlayerExitedNormally() {
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)
    }

    suspend fun consumeResumePrompt(): ContinueWatchingItem? {
        val wasInPlayer = ResumePromptStorage.loadWasInPlayer()
        if (!wasInPlayer) return null

        val videoId = ResumePromptStorage.loadLastPlayerVideoId()
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)

        if (videoId.isNullOrBlank()) return null

        WatchProgressRepository.ensureLoaded()
        val entry = WatchProgressRepository.progressForVideo(videoId) ?: return null

        if (entry.isResumable) {
            return entry.toContinueWatchingItem()
        }

        if (!entry.isEpisode) return null

        val meta = MetaDetailsRepository.fetch(
            type = entry.parentMetaType,
            id = entry.parentMetaId,
        ) ?: return null

        val nextEpisode = meta.nextReleasedEpisodeAfter(
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            todayIsoDate = CurrentDateProvider.todayIsoDate(),
        ) ?: return null

        return entry.toUpNextContinueWatchingItem(nextEpisode)
    }
}
