package com.nuviolinux.app.core.storage

import com.nuviolinux.app.core.build.AppFeaturePolicy
import com.nuviolinux.app.core.sync.SyncManager
import com.nuviolinux.app.core.sync.ProfileSettingsSync
import com.nuviolinux.app.features.addons.AddonRepository
import com.nuviolinux.app.features.catalog.CatalogRepository
import com.nuviolinux.app.features.collection.CollectionMobileSettingsRepository
import com.nuviolinux.app.features.collection.CollectionRepository
import com.nuviolinux.app.features.details.MetaDetailsRepository
import com.nuviolinux.app.features.details.MetaScreenSettingsRepository
import com.nuviolinux.app.features.home.HomeCatalogSettingsRepository
import com.nuviolinux.app.features.home.HomeRepository
import com.nuviolinux.app.features.library.LibraryRepository
import com.nuviolinux.app.features.library.LibraryDisplaySettingsRepository
import com.nuviolinux.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuviolinux.app.features.player.PlayerLaunchStore
import com.nuviolinux.app.features.player.PlayerSettingsRepository
import com.nuviolinux.app.features.p2p.P2pSettingsRepository
import com.nuviolinux.app.features.plugins.PluginRepository
import com.nuviolinux.app.features.player.SubtitleRepository
import com.nuviolinux.app.features.profiles.ProfileRepository
import com.nuviolinux.app.features.search.SearchRepository
import com.nuviolinux.app.features.settings.ThemeSettingsRepository
import com.nuviolinux.app.features.streams.StreamContextStore
import com.nuviolinux.app.features.streams.StreamBadgeSettingsRepository
import com.nuviolinux.app.features.streams.StreamLaunchStore
import com.nuviolinux.app.features.streams.StreamsRepository
import com.nuviolinux.app.features.trakt.TraktAuthRepository
import com.nuviolinux.app.features.trakt.TraktSettingsRepository
import com.nuviolinux.app.core.ui.CardDepthStyleRepository
import com.nuviolinux.app.core.ui.PosterCardStyleRepository
import com.nuviolinux.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuviolinux.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuviolinux.app.features.watchprogress.WatchProgressRepository
import com.nuviolinux.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuviolinux.app.features.watched.WatchedRepository

internal object LocalAccountDataCleaner {
    fun wipe() {
        SyncManager.cancelAccountSync()
        WatchProgressSourceCoordinator.clearLocalState()
        ProfileSettingsSync.clearAccountState()
        ContinueWatchingEnrichmentCache.clearLocalState()
        WatchProgressRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        LibraryRepository.runAccountStorageWipe {
            PlatformLocalAccountDataCleaner.wipe()
        }

        ProfileRepository.clearInMemory()
        AddonRepository.clearLocalState()
        if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.clearLocalState()
        }
        HomeRepository.clear()
        HomeCatalogSettingsRepository.clearLocalState()
        MetaScreenSettingsRepository.clearLocalState()
        LibraryRepository.clearLocalState()
        LibraryDisplaySettingsRepository.clearLocalState()
        ContinueWatchingPreferencesRepository.clearLocalState()
        EpisodeReleaseNotificationsRepository.clearLocalState()
        CollectionMobileSettingsRepository.clearLocalState()
        CollectionRepository.clearLocalState()
        ThemeSettingsRepository.clearLocalState()
        PosterCardStyleRepository.clearLocalState()
        CardDepthStyleRepository.clearLocalState()
        TraktAuthRepository.clearLocalState()
        TraktSettingsRepository.clearLocalState()
        PlayerSettingsRepository.clearLocalState()
        StreamBadgeSettingsRepository.clearLocalState()
        P2pSettingsRepository.clearLocalState()
        CatalogRepository.clear()
        StreamsRepository.clear()
        MetaDetailsRepository.clear()
        SearchRepository.reset()
        SubtitleRepository.clear()
        PlayerLaunchStore.clear()
        StreamLaunchStore.clear()
        StreamContextStore.clear()
    }
}

internal expect object PlatformLocalAccountDataCleaner {
    fun wipe()
}
