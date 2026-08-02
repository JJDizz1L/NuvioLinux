package com.nuviolinux.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.compose_settings_page_debrid
import nuviolinux.composeapp.generated.resources.compose_settings_page_presence
import nuviolinux.composeapp.generated.resources.compose_settings_page_mdblist_ratings
import nuviolinux.composeapp.generated.resources.compose_settings_page_tmdb_enrichment
import nuviolinux.composeapp.generated.resources.settings_integrations_debrid_description
import nuviolinux.composeapp.generated.resources.settings_integrations_discord_description
import nuviolinux.composeapp.generated.resources.settings_integrations_mdblist_description
import nuviolinux.composeapp.generated.resources.settings_integrations_section_title
import nuviolinux.composeapp.generated.resources.settings_integrations_tmdb_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.integrationsContent(
    isTablet: Boolean,
    onTmdbClick: () -> Unit,
    onMdbListClick: () -> Unit,
    onDebridClick: () -> Unit,
    onDiscordClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_integrations_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_tmdb_enrichment),
                    description = stringResource(Res.string.settings_integrations_tmdb_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.Tmdb),
                    isTablet = isTablet,
                    onClick = onTmdbClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_mdblist_ratings),
                    description = stringResource(Res.string.settings_integrations_mdblist_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.MdbList),
                    isTablet = isTablet,
                    onClick = onMdbListClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_debrid),
                    description = stringResource(Res.string.settings_integrations_debrid_description),
                    isTablet = isTablet,
                    onClick = onDebridClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_presence),
                    description = stringResource(Res.string.settings_integrations_discord_description),
                    isTablet = isTablet,
                    onClick = onDiscordClick,
                )
            }
        }
    }
}
