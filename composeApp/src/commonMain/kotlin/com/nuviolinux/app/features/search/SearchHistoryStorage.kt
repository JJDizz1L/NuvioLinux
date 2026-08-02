package com.nuviolinux.app.features.search

internal expect object SearchHistoryStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
