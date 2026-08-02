package com.nuviolinux.app.features.settings

import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.settings_nav_bar_style_adaptive
import nuviolinux.composeapp.generated.resources.settings_nav_bar_style_expanded
import nuviolinux.composeapp.generated.resources.settings_nav_bar_style_compact
import nuviolinux.composeapp.generated.resources.settings_nav_bar_style_classic
import org.jetbrains.compose.resources.StringResource

enum class NavBarStyle(
    val key: String,
    val labelRes: StringResource,
) {
    ADAPTIVE("adaptive", Res.string.settings_nav_bar_style_adaptive),
    EXPANDED("expanded", Res.string.settings_nav_bar_style_expanded),
    COMPACT("compact", Res.string.settings_nav_bar_style_compact),
    CLASSIC("classic", Res.string.settings_nav_bar_style_classic),
    ;

    companion object {
        fun fromKey(key: String?): NavBarStyle =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: ADAPTIVE
    }
}
