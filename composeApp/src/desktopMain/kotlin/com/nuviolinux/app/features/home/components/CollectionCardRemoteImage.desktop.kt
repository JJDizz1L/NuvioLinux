package com.nuviolinux.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nuviolinux.app.core.ui.NuvioAsyncImage as AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

private data class SkiaGifAnimation(
    val frames: List<ImageBitmap>,
    val delaysMs: List<Long>,
)

private val desktopGifHttpClient by lazy { HttpClient(CIO) }
private val desktopGifCache = mutableMapOf<String, SkiaGifAnimation?>()

private suspend fun loadDesktopGifAnimation(url: String): SkiaGifAnimation? {
    if (desktopGifCache.containsKey(url)) {
        return desktopGifCache[url]
    }
    val anim = withContext(Dispatchers.IO) {
        try {
            val bytes = desktopGifHttpClient.get(url).body<ByteArray>()
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            val count = codec.frameCount
            if (count <= 1) return@withContext null
            val width = codec.width
            val height = codec.height
            if (width <= 0 || height <= 0) return@withContext null

            val frames = mutableListOf<ImageBitmap>()
            val delays = mutableListOf<Long>()

            for (i in 0 until count) {
                val bitmap = Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(width, height))
                }
                codec.readPixels(bitmap, i)
                frames.add(bitmap.asComposeImageBitmap())
                val duration = codec.getFrameInfo(i).duration
                delays.add(if (duration > 0) duration.toLong() else 100L)
            }
            SkiaGifAnimation(frames, delays)
        } catch (_: Exception) {
            null
        }
    }
    desktopGifCache[url] = anim
    return anim
}

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val isGifUrl = remember(imageUrl) {
        imageUrl.contains(".gif", ignoreCase = true)
    }
    val context = LocalPlatformContext.current
    val displayImageUrl = if (animateIfPossible && isGifUrl) {
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

    if (animateIfPossible && isGifUrl) {
        val hoverInteractionSource = remember { MutableInteractionSource() }
        val isHovered by hoverInteractionSource.collectIsHoveredAsState()
        var animation by remember(imageUrl) { mutableStateOf(desktopGifCache[imageUrl]) }

        LaunchedEffect(imageUrl) {
            if (animation == null && !desktopGifCache.containsKey(imageUrl)) {
                animation = loadDesktopGifAnimation(imageUrl)
            }
        }

        val currentAnimation = animation
        var frameIndex by remember(imageUrl, isHovered) { mutableStateOf(0) }

        LaunchedEffect(imageUrl, currentAnimation, isHovered) {
            val playableAnimation = currentAnimation?.takeIf { it.frames.isNotEmpty() }
                ?: return@LaunchedEffect
            if (!isHovered) return@LaunchedEffect
            while (true) {
                val delayMs = playableAnimation.delaysMs.getOrElse(frameIndex) { 100L }
                delay(delayMs)
                frameIndex = (frameIndex + 1) % playableAnimation.frames.size
            }
        }

        Box(modifier = modifier.hoverable(hoverInteractionSource)) {
            if (
                currentAnimation != null &&
                currentAnimation.frames.isNotEmpty() &&
                (isHovered || staticImageUrl.isNullOrBlank())
            ) {
                Image(
                    bitmap = currentAnimation.frames[
                        if (isHovered) frameIndex else currentAnimation.frames.lastIndex
                    ],
                    contentDescription = contentDescription,
                    modifier = Modifier.matchParentSize(),
                    contentScale = contentScale,
                )
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    modifier = Modifier.matchParentSize(),
                    contentScale = contentScale,
                )
            }
        }
        return
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
