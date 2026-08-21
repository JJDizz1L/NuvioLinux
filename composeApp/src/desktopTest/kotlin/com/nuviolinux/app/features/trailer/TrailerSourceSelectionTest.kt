package com.nuviolinux.app.features.trailer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the playback-source preference policy (REVIEW-NOTES T1):
 * adaptive_separate -> progressive -> hls_last_resort -> video-only.
 * Non-googlevideo URLs bypass reachability probing, keeping these tests
 * deterministic and offline.
 */
class TrailerSourceSelectionTest {

    private fun video(height: Int, tag: String) = StreamCandidate(
        client = "visionos",
        priority = 0,
        url = "https://example.invalid/video$height-$tag",
        score = height.toDouble(),
        bitrate = 1_000_000L,
        mimeType = "video/mp4",
        hasN = false,
        height = height,
        fps = 24,
        ext = "mp4",
    )

    private fun audio(tag: String) = StreamCandidate(
        client = "visionos",
        priority = 0,
        url = "https://example.invalid/audio-$tag",
        score = 100.0,
        bitrate = 130_000L,
        mimeType = "audio/mp4",
        hasN = false,
        height = 0,
        fps = 0,
        ext = "m4a",
    )

    private fun manifest(height: Int) = ManifestCandidate(
        client = "visionos",
        priority = 0,
        manifestUrl = "https://example.invalid/hls-$height.m3u8",
        selectedVariantUrl = "https://example.invalid/hls-variant-$height.m3u8",
        height = height,
        bandwidth = 4_000_000L,
    )

    @Test
    fun `prefers separate streams even when the HLS variant claims more height`() {
        val source = runBlocking {
            TrailerExtractionPlatform.buildPlaybackSource(
                bestManifest = manifest(1080),
                progressiveCandidates = listOf(video(720, "p")),
                videoCandidates = listOf(video(1080, "v"), video(720, "v2")),
                audioCandidates = listOf(audio("a")),
            )
        }
        assertNotNull(source)
        assertEquals("https://example.invalid/video1080-v", source.videoUrl)
        assertEquals("https://example.invalid/audio-a", source.audioUrl)
    }

    @Test
    fun `falls back to progressive when no audio stream exists - HLS is last resort`() {
        val source = runBlocking {
            TrailerExtractionPlatform.buildPlaybackSource(
                bestManifest = manifest(1080),
                progressiveCandidates = listOf(video(720, "p")),
                videoCandidates = listOf(video(1080, "v")),
                audioCandidates = emptyList(),
            )
        }
        assertNotNull(source)
        /* Old policy would have picked the 1080p HLS master here. */
        assertEquals("https://example.invalid/video720-p", source.videoUrl)
        assertNull(source.audioUrl)
    }

    @Test
    fun `uses HLS only when nothing else is reachable`() {
        val source = runBlocking {
            TrailerExtractionPlatform.buildPlaybackSource(
                bestManifest = manifest(1080),
                progressiveCandidates = emptyList(),
                videoCandidates = emptyList(),
                audioCandidates = emptyList(),
            )
        }
        assertNotNull(source)
        assertEquals("https://example.invalid/hls-1080.m3u8", source.videoUrl)
    }

    @Test
    fun `walks down the candidate chain past unreachable entries`() {
        val source = runBlocking {
            TrailerExtractionPlatform.buildPlaybackSource(
                bestManifest = null,
                /* *.invalid.googlevideo.com keeps the probe path active
                 * (domain matches) while DNS fails fast offline. */
                progressiveCandidates = listOf(video(480, "dead").copy(url = "https://dead.invalid.googlevideo.com/p480")),
                videoCandidates = listOf(
                    video(1080, "dead").copy(url = "https://dead.invalid.googlevideo.com/v1080"),
                    video(720, "alive"),
                ),
                audioCandidates = listOf(audio("a")),
            )
        }
        assertNotNull(source)
        assertEquals("https://example.invalid/video720-alive", source.videoUrl)
        assertEquals("https://example.invalid/audio-a", source.audioUrl)
    }

    @Test
    fun `returns video-only as a degenerate fallback`() {
        val source = runBlocking {
            TrailerExtractionPlatform.buildPlaybackSource(
                bestManifest = null,
                progressiveCandidates = emptyList(),
                videoCandidates = listOf(video(1080, "v")),
                audioCandidates = emptyList(),
            )
        }
        assertNotNull(source)
        assertEquals("https://example.invalid/video1080-v", source.videoUrl)
        assertNull(source.audioUrl)
    }

    @Test
    fun `null when every category is empty`() {
        val source = runBlocking {
            TrailerExtractionPlatform.buildPlaybackSource(
                bestManifest = null,
                progressiveCandidates = emptyList(),
                videoCandidates = emptyList(),
                audioCandidates = emptyList(),
            )
        }
        assertNull(source)
    }
}
