package com.nuviolinux.app.features.trailer

expect object TrailerPlaybackResolver {
    suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource?
}
