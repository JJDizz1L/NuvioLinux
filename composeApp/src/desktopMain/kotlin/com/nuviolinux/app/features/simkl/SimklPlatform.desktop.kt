package com.nuviolinux.app.features.simkl

import com.nuviolinux.app.core.storage.DesktopStorage
import com.nuviolinux.app.core.storage.ProfileScopedKey
import java.security.MessageDigest
import java.security.SecureRandom

internal actual object SimklPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}

internal actual object SimklPkceCrypto {
    private val secureRandom = SecureRandom()

    actual fun secureRandomBytes(size: Int): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    actual fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

internal actual object SimklAuthStorage {
    private const val metadataKey = "simkl_auth_metadata"
    private const val accessTokenKey = "simkl_access_token"
    private const val codeVerifierKey = "simkl_code_verifier"
    private val store = DesktopStorage.store("nuvio_simkl_auth")

    actual fun loadMetadataPayload(): String? =
        store.getString(ProfileScopedKey.of(metadataKey))

    actual fun saveMetadataPayload(payload: String) {
        store.putString(ProfileScopedKey.of(metadataKey), payload)
    }

    actual fun loadAccessToken(): String? =
        store.getString(ProfileScopedKey.of(accessTokenKey))

    actual fun saveAccessToken(value: String?) {
        store.putString(ProfileScopedKey.of(accessTokenKey), value)
    }

    actual fun loadCodeVerifier(): String? =
        store.getString(ProfileScopedKey.of(codeVerifierKey))

    actual fun saveCodeVerifier(value: String?) {
        store.putString(ProfileScopedKey.of(codeVerifierKey), value)
    }

    actual fun removeProfile(profileId: Int) {
        store.removeAll(
            listOf(
                ProfileScopedKey.of(metadataKey, profileId),
                ProfileScopedKey.of(accessTokenKey, profileId),
                ProfileScopedKey.of(codeVerifierKey, profileId),
            ),
        )
    }
}
