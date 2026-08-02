package com.nuviolinux.app.core.ui

internal expect object CardDepthStyleStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
