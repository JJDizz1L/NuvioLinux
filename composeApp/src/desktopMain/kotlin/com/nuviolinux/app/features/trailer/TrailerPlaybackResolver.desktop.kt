package com.nuviolinux.app.features.trailer

import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }
    private val cacheMutex = Mutex()
    private var playbackCache = emptyMap<String, CachedPlaybackSource>()

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? {
        if (youtubeUrl.isBlank()) return null
        cacheMutex.lock()
        try {
            playbackCache = playbackCache.filterValues { entry ->
                entry.cachedAt.elapsedNow() < PlaybackCacheTtl
            }
            playbackCache[youtubeUrl]?.let { cached ->
                TrailerExtractionPlatform.diagnostic(
                    "cache hit ageMs=${cached.cachedAt.elapsedNow().inWholeMilliseconds}",
                )
                return cached.source
            }
        } finally {
            cacheMutex.unlock()
        }

        val source = extractor.extractPlaybackSource(youtubeUrl)
        if (source != null) {
            cacheMutex.lock()
            try {
                playbackCache = playbackCache + (
                    youtubeUrl to CachedPlaybackSource(
                        source = source,
                        cachedAt = TimeSource.Monotonic.markNow(),
                    )
                )
            } finally {
                cacheMutex.unlock()
            }
        }
        return source
    }

    private data class CachedPlaybackSource(
        val source: TrailerPlaybackSource,
        val cachedAt: TimeMark,
    )
}

private val PlaybackCacheTtl = 10.minutes