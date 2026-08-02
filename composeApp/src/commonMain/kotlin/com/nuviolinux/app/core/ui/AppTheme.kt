package com.nuviolinux.app.core.ui

import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.theme_amber
import nuviolinux.composeapp.generated.resources.theme_crimson
import nuviolinux.composeapp.generated.resources.theme_emerald
import nuviolinux.composeapp.generated.resources.theme_ocean
import nuviolinux.composeapp.generated.resources.theme_rose
import nuviolinux.composeapp.generated.resources.theme_violet
import nuviolinux.composeapp.generated.resources.theme_white
import org.jetbrains.compose.resources.StringResource

enum class AppTheme {
    CRIMSON,
    OCEAN,
    VIOLET,
    EMERALD,
    AMBER,
    ROSE,
    WHITE,
}

val AppTheme.labelRes: StringResource
    get() = when (this) {
        AppTheme.CRIMSON -> Res.string.theme_crimson
        AppTheme.OCEAN -> Res.string.theme_ocean
        AppTheme.VIOLET -> Res.string.theme_violet
        AppTheme.EMERALD -> Res.string.theme_emerald
        AppTheme.AMBER -> Res.string.theme_amber
        AppTheme.ROSE -> Res.string.theme_rose
        AppTheme.WHITE -> Res.string.theme_white
    }
