package com.nuviolinux.app.core.tracking

import com.nuviolinux.app.features.simkl.SimklAuthRepository
import com.nuviolinux.app.features.simkl.SimklMutationRepository
import com.nuviolinux.app.features.simkl.SimklLibraryRepository
import com.nuviolinux.app.features.simkl.SimklProgressRepository
import com.nuviolinux.app.features.simkl.SimklTrackingLibraryProvider
import com.nuviolinux.app.features.simkl.SimklTrackingProgressProvider
import com.nuviolinux.app.features.simkl.SimklWatchedSyncAdapter
import com.nuviolinux.app.features.simkl.SimklSyncRepository
import com.nuviolinux.app.features.tracking.TrackingProviderRegistry
import com.nuviolinux.app.features.trakt.TraktAuthRepository
import com.nuviolinux.app.features.trakt.TraktScrobbleRepository
import com.nuviolinux.app.features.trakt.TraktTrackingLibraryProvider
import com.nuviolinux.app.features.trakt.TraktTrackingProgressProvider
import com.nuviolinux.app.features.watching.sync.TraktWatchedSyncAdapter

fun ensureTrackingProvidersRegistered() {
    TraktAuthRepository.descriptor
    TraktScrobbleRepository.ensureRegistered()
    SimklAuthRepository.descriptor
    SimklSyncRepository.state
    SimklLibraryRepository.uiState
    SimklProgressRepository.uiState
    SimklMutationRepository.ensureRegistered()
    TrackingProviderRegistry.registerLibraryProvider(TraktTrackingLibraryProvider)
    TrackingProviderRegistry.registerLibraryProvider(SimklTrackingLibraryProvider)
    TrackingProviderRegistry.registerWatchedProvider(TraktWatchedSyncAdapter)
    TrackingProviderRegistry.registerWatchedProvider(SimklWatchedSyncAdapter)
    TrackingProviderRegistry.registerProgressProvider(TraktTrackingProgressProvider)
    TrackingProviderRegistry.registerProgressProvider(SimklTrackingProgressProvider)
}
