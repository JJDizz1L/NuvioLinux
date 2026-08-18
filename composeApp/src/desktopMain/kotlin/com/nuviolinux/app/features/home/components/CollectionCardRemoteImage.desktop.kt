package com.nuviolinux.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.nuviolinux.app.core.ui.NuvioAsyncImage as AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val context = LocalPlatformContext.current
    val displayImageUrl = if (animateIfPossible) {
        staticImageUrl?.takeIf { it.isNotBlank() } ?: imageUrl
    } else {
        imageUrl
    }
    val request = remember(context, displayImageUrl) {
        ImageRequest.Builder(context)
            .data(displayImageUrl)
            .memoryCacheKey("home-collection:$displayImageUrl")
            .diskCacheKey(displayImageUrl)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}