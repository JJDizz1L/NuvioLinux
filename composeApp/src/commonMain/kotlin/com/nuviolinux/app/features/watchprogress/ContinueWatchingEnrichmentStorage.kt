package com.nuviolinux.app.features.watchprogress

internal expect object ContinueWatchingEnrichmentStorage {
    fun loadPayload(key: String): String?
    fun savePayload(key: String, payload: String)
    fun removePayload(key: String)
}
