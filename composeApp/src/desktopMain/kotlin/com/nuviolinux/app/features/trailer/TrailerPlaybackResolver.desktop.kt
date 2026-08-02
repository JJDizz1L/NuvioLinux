package com.nuviolinux.app.features.trailer

actual object TrailerPlaybackResolver {
    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? = null
}
