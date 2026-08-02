package com.nuviolinux.app.core.build

enum class AppInstallFormat(val displayNameSuffix: String) {
    NATIVE(""),
    FLATPAK(" Flatpak"),
    APPIMAGE(" AppImage"),
}

/**
 * Resolves which packaging format this copy of Nuvio Linux was installed as,
 * so the window title (and launcher entry) can distinguish installs that
 * otherwise share the same base name.
 */
object AppIdentity {
    const val DISPLAY_NAME_BASE = "Nuvio Linux"

    val installFormat: AppInstallFormat by lazy {
        val isFlatpakRuntime =
            System.getenv("FLATPAK_ID") != null ||
                System.getProperty("user.home").orEmpty().startsWith("/app/") ||
                System.getProperty("java.home").orEmpty().startsWith("/app/")
        when {
            isFlatpakRuntime -> AppInstallFormat.FLATPAK
            System.getenv("APPIMAGE") != null -> AppInstallFormat.APPIMAGE
            else -> AppInstallFormat.NATIVE
        }
    }

    val displayName: String
        get() = DISPLAY_NAME_BASE + installFormat.displayNameSuffix
}
