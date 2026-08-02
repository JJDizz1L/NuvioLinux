package com.nuviolinux.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuviolinux.app.core.build.AppFeaturePolicy
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.compose_settings_page_addons
import nuviolinux.composeapp.generated.resources.compose_settings_page_plugins
import nuviolinux.composeapp.generated.resources.settings_content_discovery_addons_description
import nuviolinux.composeapp.generated.resources.settings_content_discovery_addons_description_appstore
import nuviolinux.composeapp.generated.resources.settings_content_discovery_plugins_description
import nuviolinux.composeapp.generated.resources.settings_content_discovery_section_sources
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.contentDiscoveryContent(
    isTablet: Boolean,
    showPluginsEntry: Boolean,
    onAddonsClick: () -> Unit,
    onPluginsClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_content_discovery_section_sources),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_addons),
                    description = stringResource(
                        if (AppFeaturePolicy.personalMediaAddonCopyEnabled) {
                            Res.string.settings_content_discovery_addons_description_appstore
                        } else {
                            Res.string.settings_content_discovery_addons_description
                        },
                    ),
                    isTablet = isTablet,
                    onClick = onAddonsClick,
                )
                if (showPluginsEntry) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_plugins),
                        description = stringResource(Res.string.settings_content_discovery_plugins_description),
                        isTablet = isTablet,
                        onClick = onPluginsClick,
                    )
                }
            }
        }
    }
}
