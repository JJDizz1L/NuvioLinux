package com.nuviolinux.app.features.player.desktop

/** NuvioDesktop only supports Linux. */
internal enum class DesktopHostOs {
    LINUX,
    UNKNOWN;

    companion object {
        val current: DesktopHostOs by lazy {
            val osName = System.getProperty("os.name").orEmpty().lowercase(java.util.Locale.ROOT)
            if (osName.contains("linux")) LINUX else UNKNOWN
        }
    }
}
