package com.nuviolinux.app.core.auth

import com.nuviolinux.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.util.prefs.Preferences
import kotlinx.serialization.json.Json

/**
 * Persists the Supabase session (access + refresh JWTs) through DesktopStorage,
 * so it lives in the app's own 0600-protected config dir instead of the
 * world-readable java.util.prefs XML, and is cleared by DesktopStorage.wipe().
 */
internal class DesktopSupabaseSessionManager(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionManager {
    private val store = DesktopStorage.store("supabase_session")

    override suspend fun loadSession(): UserSession? {
        store.getString(SESSION_KEY)?.let { payload ->
            decode(payload)?.let { return it }
        }
        migrateLegacyPreferencesSession()?.let { return it }
        return null
    }

    override suspend fun saveSession(session: UserSession) {
        store.putString(SESSION_KEY, json.encodeToString(session))
    }

    override suspend fun deleteSession() {
        store.remove(SESSION_KEY)
    }

    private fun decode(payload: String): UserSession? =
        runCatching { json.decodeFromString<UserSession>(payload) }.getOrNull()

    private suspend fun migrateLegacyPreferencesSession(): UserSession? {
        val legacyPayload = runCatching {
            Preferences.userRoot().get(SESSION_KEY, null)
        }.getOrNull() ?: return null
        val session = decode(legacyPayload) ?: return null
        saveSession(session)
        runCatching { Preferences.userRoot().remove(SESSION_KEY) }
        return session
    }

    private companion object {
        const val SESSION_KEY = "session"
    }
}
