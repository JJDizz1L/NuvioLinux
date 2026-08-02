package com.nuviolinux.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerP2pConsentPolicyTest {
    @Test
    fun debridResolvableTorrentDoesNotRequestP2pConsent() {
        assertFalse(
            shouldRequestP2pConsentForPlayerControls(
                isP2pStream = true,
                shouldResolveToPlayableStream = true,
                p2pSettingsVisible = true,
                p2pEnabled = false,
            ),
        )
    }

    @Test
    fun unresolvedTorrentRequestsP2pConsentWhenP2pIsDisabled() {
        assertTrue(
            shouldRequestP2pConsentForPlayerControls(
                isP2pStream = true,
                shouldResolveToPlayableStream = false,
                p2pSettingsVisible = true,
                p2pEnabled = false,
            ),
        )
    }

    @Test
    fun enabledP2pDoesNotRequestConsentAgain() {
        assertFalse(
            shouldRequestP2pConsentForPlayerControls(
                isP2pStream = true,
                shouldResolveToPlayableStream = false,
                p2pSettingsVisible = true,
                p2pEnabled = true,
            ),
        )
    }
}
