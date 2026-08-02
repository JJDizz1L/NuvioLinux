package com.nuviolinux.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuviolinux.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuviolinux.app.core.ui.NuvioDesktopImageScaling
import com.nuviolinux.app.core.ui.NuvioTokens
import com.nuviolinux.app.core.ui.FullscreenActionButton
import com.nuviolinux.app.core.ui.fullscreenActionHorizontalInsetForWidth
import com.nuviolinux.app.core.ui.isFullscreenActionSupported
import com.nuviolinux.app.features.details.MetaDetails
import com.nuviolinux.app.features.details.formatRuntimeForDisplay
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.detail_logo_content_description
import nuviolinux.composeapp.generated.resources.hero_add_to_library
import nuviolinux.composeapp.generated.resources.hero_mark_unwatched
import nuviolinux.composeapp.generated.resources.hero_mark_watched
import nuviolinux.composeapp.generated.resources.hero_remove_from_library
import org.jetbrains.compose.resources.stringResource

@Composable
fun DesktopDetailHero(
    meta: MetaDetails,
    playButtonLabel: String,
    isSaved: Boolean,
    isWatched: Boolean,
    scrollOffset: () -> Int,
    onHeightChanged: (Int) -> Unit,
    heroTrailerSourceUrl: String?,
    heroTrailerSourceAudioUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerPlayWhenReady: Boolean,
    heroTrailerMuted: Boolean,
    heroGradientColor: Color? = null,
    onBackdropLoaded: (Painter) -> Unit = {},
    onHeroTrailerReady: () -> Unit,
    onHeroTrailerEnded: () -> Unit,
    onHeroTrailerError: () -> Unit,
    onPlayClick: () -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onWatchedClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bottomGradientColor = heroGradientColor ?: colorScheme.background
    val sideGradientColor = heroGradientColor ?: colorScheme.background
    val space = NuvioTokens.Space
    val opacity = NuvioTokens.Opacity
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_trailer_alpha",
    )
    var logoLoadError by remember(meta.id, meta.logo) {
        mutableStateOf(false)
    }
    val logoUrl = meta.logo?.takeIf { it.isNotBlank() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(660.dp)
            .graphicsLayer { clip = true }
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        val actionHorizontalInset = fullscreenActionHorizontalInsetForWidth(maxWidth.value)
        val imageUrl = meta.background ?: meta.poster
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = meta.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = scrollOffset() * 0.38f
                        scaleX = 1.04f
                        scaleY = 1.04f
                    },
                alignment = Alignment.Center,
                contentScale = ContentScale.Crop,
                desktopImageScaling = NuvioDesktopImageScaling.Disabled,
                onSuccess = { state -> onBackdropLoaded(state.painter) },
            )
        } else {
            DesktopStripePlaceholder(modifier = Modifier.fillMaxSize())
        }

        if (heroTrailerSourceUrl != null) {
            HeroTrailerPlayerSurface(
                sourceUrl = heroTrailerSourceUrl,
                sourceAudioUrl = heroTrailerSourceAudioUrl,
                playWhenReady = heroTrailerPlayWhenReady,
                muted = heroTrailerMuted,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = trailerAlpha
                        translationY = scrollOffset() * 0.38f
                        scaleX = 1.04f
                        scaleY = 1.04f
                    },
                onReady = onHeroTrailerReady,
                onEnded = onHeroTrailerEnded,
                onError = onHeroTrailerError,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.14f to bottomGradientColor.copy(alpha = opacity.subtle),
                            0.38f to bottomGradientColor.copy(alpha = opacity.overlayLight),
                            0.66f to bottomGradientColor.copy(alpha = opacity.overlayHeavy),
                            0.88f to bottomGradientColor.copy(alpha = 0.98f),
                            1.00f to bottomGradientColor,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to sideGradientColor.copy(alpha = opacity.overlayHeavy),
                            0.32f to sideGradientColor.copy(alpha = opacity.medium),
                            0.62f to sideGradientColor.copy(alpha = opacity.subtle),
                            1.00f to Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 760.dp)
                .padding(start = space.s56, end = space.s32, bottom = space.s40),
        ) {
            if (logoUrl != null && !logoLoadError) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .height(120.dp),
                    alignment = Alignment.CenterStart,
                    contentScale = ContentScale.Fit,
                    onError = { logoLoadError = true },
                )
            } else {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = NuvioTokens.Type.displayMd,
                        lineHeight = NuvioTokens.LineHeight.displayMd,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(space.s20))
            DesktopHeroMetaRow(meta = meta)
            if (meta.externalRatings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(space.s12))
                DetailRatingsRow(
                    ratings = meta.externalRatings,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
            if (meta.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(space.s12))
                Text(
                    text = meta.genres.take(4).joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = NuvioTokens.Type.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Spacer(modifier = Modifier.height(space.s16))
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = NuvioTokens.Type.bodyLg,
                        lineHeight = NuvioTokens.LineHeight.bodyLg,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(space.s28))
            DetailActionButtons(
                modifier = Modifier.widthIn(max = 520.dp),
                playLabel = playButtonLabel,
                secondaryActions = listOf(
                    DetailSecondaryAction(
                        label = if (isWatched) {
                            stringResource(Res.string.hero_mark_unwatched)
                        } else {
                            stringResource(Res.string.hero_mark_watched)
                        },
                        icon = if (isWatched) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.CheckCircleOutline
                        },
                        isActive = isWatched,
                        onClick = onWatchedClick,
                    ),
                    DetailSecondaryAction(
                        label = if (isSaved) {
                            stringResource(Res.string.hero_remove_from_library)
                        } else {
                            stringResource(Res.string.hero_add_to_library)
                        },
                        icon = if (isSaved) {
                            Icons.Default.Check
                        } else {
                            Icons.Default.Add
                        },
                        isActive = isSaved,
                        onClick = onSaveClick,
                        onLongClick = onSaveLongClick,
                    ),
                ),
                isTablet = true,
                onPlayClick = onPlayClick,
                onPlayLongClick = onPlayLongClick,
            )
        }

        if (isFullscreenActionSupported) {
            FullscreenActionButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = space.s32, end = actionHorizontalInset),
                buttonSize = 48.dp,
                iconSize = 24.dp,
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.82f),
                contentColor = colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DesktopHeroMetaRow(meta: MetaDetails) {
    val colorScheme = MaterialTheme.colorScheme
    val space = NuvioTokens.Space
    val opacity = NuvioTokens.Opacity
    val metaItems = buildList {
        desktopYearLabel(meta)?.let(::add)
        desktopSeasonCountLabel(meta)?.let(::add)
        formatRuntimeForDisplay(meta.runtime)?.let(::add)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.s16),
    ) {
        metaItems.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = NuvioTokens.Type.bodyLg,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = NuvioTokens.LetterSpacing.none,
                ),
                color = colorScheme.onBackground,
                maxLines = 1,
            )
        }
        meta.ageRating?.takeIf { it.isNotBlank() }?.let { rating ->
            Box(
                modifier = Modifier
                    .border(
                        NuvioTokens.Border.thin,
                        colorScheme.onBackground.copy(alpha = opacity.overlayLight),
                        RoundedCornerShape(NuvioTokens.Radius.sm),
                    )
                    .padding(horizontal = space.s8, vertical = space.s2),
            ) {
                Text(
                    text = rating,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = NuvioTokens.Type.bodySm,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
