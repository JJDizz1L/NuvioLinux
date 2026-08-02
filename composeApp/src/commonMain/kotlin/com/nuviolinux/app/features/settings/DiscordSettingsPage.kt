package com.nuviolinux.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuviolinux.app.features.discord.DiscordSettings
import com.nuviolinux.app.features.discord.DiscordSettingsRepository
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.settings_discord_enable_rpc
import nuviolinux.composeapp.generated.resources.settings_discord_enable_rpc_description
import nuviolinux.composeapp.generated.resources.settings_discord_hide_title
import nuviolinux.composeapp.generated.resources.settings_discord_hide_title_description
import nuviolinux.composeapp.generated.resources.settings_discord_section_title
import nuviolinux.composeapp.generated.resources.settings_discord_show_poster
import nuviolinux.composeapp.generated.resources.settings_discord_show_poster_description
import nuviolinux.composeapp.generated.resources.settings_discord_show_timestamp
import nuviolinux.composeapp.generated.resources.settings_discord_show_timestamp_description
import nuviolinux.composeapp.generated.resources.settings_discord_show_when_browsing
import nuviolinux.composeapp.generated.resources.settings_discord_show_when_browsing_description
import nuviolinux.composeapp.generated.resources.settings_discord_show_when_paused
import nuviolinux.composeapp.generated.resources.settings_discord_show_when_paused_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.discordSettingsContent(
    isTablet: Boolean,
    settings: DiscordSettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_discord_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discord_enable_rpc),
                    description = stringResource(Res.string.settings_discord_enable_rpc_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = DiscordSettingsRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discord_hide_title),
                    description = stringResource(Res.string.settings_discord_hide_title_description),
                    checked = settings.hideTitle,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = DiscordSettingsRepository::setHideTitle,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discord_show_when_paused),
                    description = stringResource(Res.string.settings_discord_show_when_paused_description),
                    checked = settings.showWhenPaused,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = DiscordSettingsRepository::setShowWhenPaused,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discord_show_when_browsing),
                    description = stringResource(Res.string.settings_discord_show_when_browsing_description),
                    checked = settings.showWhenBrowsing,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = DiscordSettingsRepository::setShowWhenBrowsing,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discord_show_poster),
                    description = stringResource(Res.string.settings_discord_show_poster_description),
                    checked = settings.showPoster,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = DiscordSettingsRepository::setShowPoster,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discord_show_timestamp),
                    description = stringResource(Res.string.settings_discord_show_timestamp_description),
                    checked = settings.showTimestamp,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = DiscordSettingsRepository::setShowTimestamp,
                )
            }
        }
    }
}
