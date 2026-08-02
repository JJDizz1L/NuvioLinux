package com.nuviolinux.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import kotlin.math.min

private const val LoadingIndicatorSegments = 12
private const val LoadingIndicatorAnimationMillis = 1_017
private const val LoadingIndicatorSourceSize = 600f
private const val LoadingIndicatorSourceFrames = 60f
private const val LoadingIndicatorLayerFrameStep = 5f
private const val LoadingIndicatorLayerOpacityStep = 8f
private const val LoadingIndicatorStrokeWidth = 40f
private const val LoadingIndicatorOuterRadius = 278f
private const val LoadingIndicatorInnerRadius = 176f

@Composable
fun NuvioLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.nuvio.colors.textSecondary,
    size: Dp = NuvioTokens.Space.s40,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        val transition = rememberInfiniteTransition(label = "nuvio_linux_lottie_loading_indicator")
        val frame by transition.animateFloat(
            initialValue = 0f,
            targetValue = LoadingIndicatorSourceFrames,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = LoadingIndicatorAnimationMillis,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "nuvio_linux_lottie_loading_indicator_frame",
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size
            val minDimension = min(canvasSize.width, canvasSize.height)
            val sourceScale = minDimension / LoadingIndicatorSourceSize
            val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
            val strokeWidth = LoadingIndicatorStrokeWidth * sourceScale
            val outerRadius = LoadingIndicatorOuterRadius * sourceScale
            val innerRadius = LoadingIndicatorInnerRadius * sourceScale

            repeat(LoadingIndicatorSegments) { index ->
                val alpha = loadingIndicatorOpacity(
                    index = index,
                    frame = frame,
                ) / 100f
                rotate(
                    degrees = index * (360f / LoadingIndicatorSegments),
                    pivot = center,
                ) {
                    drawLine(
                        color = color.copy(alpha = alpha),
                        start = Offset(center.x, center.y - outerRadius),
                        end = Offset(center.x, center.y - innerRadius),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private fun loadingIndicatorOpacity(
    index: Int,
    frame: Float,
): Float {
    val sourceFrame = frame.coerceIn(0f, LoadingIndicatorSourceFrames)
    if (index == 0) {
        return interpolateLoadingIndicatorOpacity(
            startFrame = 0f,
            endFrame = LoadingIndicatorSourceFrames,
            startOpacity = 100f,
            endOpacity = 0f,
            frame = sourceFrame,
        )
    }

    val initialOpacity = index * LoadingIndicatorLayerOpacityStep
    val fadeOutEndFrame = index * LoadingIndicatorLayerFrameStep - 1f
    val fadeInEndFrame = index * LoadingIndicatorLayerFrameStep
    return when {
        sourceFrame <= fadeOutEndFrame ->
            interpolateLoadingIndicatorOpacity(
                startFrame = 0f,
                endFrame = fadeOutEndFrame,
                startOpacity = initialOpacity,
                endOpacity = 0f,
                frame = sourceFrame,
            )

        sourceFrame <= fadeInEndFrame ->
            interpolateLoadingIndicatorOpacity(
                startFrame = fadeOutEndFrame,
                endFrame = fadeInEndFrame,
                startOpacity = 0f,
                endOpacity = 100f,
                frame = sourceFrame,
            )

        else ->
            interpolateLoadingIndicatorOpacity(
                startFrame = fadeInEndFrame,
                endFrame = LoadingIndicatorSourceFrames,
                startOpacity = 100f,
                endOpacity = initialOpacity,
                frame = sourceFrame,
            )
    }.coerceIn(0f, 100f)
}

private fun interpolateLoadingIndicatorOpacity(
    startFrame: Float,
    endFrame: Float,
    startOpacity: Float,
    endOpacity: Float,
    frame: Float,
): Float {
    if (endFrame <= startFrame) return endOpacity
    val progress = ((frame - startFrame) / (endFrame - startFrame))
        .coerceIn(0f, 1f)
    return startOpacity + (endOpacity - startOpacity) * progress
}
