package com.nuviolinux.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuviolinux.app.features.simkl.SimklBrandAsset
import com.nuviolinux.app.features.simkl.simklBrandPainter
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.introdb_favicon
import nuviolinux.composeapp.generated.resources.mdblist_logo
import nuviolinux.composeapp.generated.resources.rating_tmdb
import nuviolinux.composeapp.generated.resources.trakt_tv_favicon
import org.jetbrains.compose.resources.painterResource

@Composable
internal actual fun integrationLogoPainter(logo: IntegrationLogo): Painter =
    when (logo) {
        IntegrationLogo.Tmdb -> painterResource(Res.drawable.rating_tmdb)
        IntegrationLogo.Trakt -> painterResource(Res.drawable.trakt_tv_favicon)
        IntegrationLogo.Simkl -> simklBrandPainter(SimklBrandAsset.Glyph)
        IntegrationLogo.MdbList -> painterResource(Res.drawable.mdblist_logo)
        IntegrationLogo.IntroDb -> painterResource(Res.drawable.introdb_favicon)
    }
