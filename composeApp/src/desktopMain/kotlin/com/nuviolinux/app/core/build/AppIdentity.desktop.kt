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

    /**
     * Console banner label for the install format. Refines NATIVE into the
     * actual package family by probing the local package databases (arch =
     * pacman local db entry, rpm = rpmdb directory, deb = dpkg database).
     */
    val installFormatLabel: String by lazy {
        when (installFormat) {
            AppInstallFormat.FLATPAK -> "flatpak"
            AppInstallFormat.APPIMAGE -> "appimage"
            AppInstallFormat.NATIVE -> when {
                java.io.File("/var/lib/pacman/local").listFiles()
                    ?.any { it.name.startsWith("nuvio-linux") } == true -> "arch"
                java.io.File("/var/lib/rpm").isDirectory -> "rpm"
                java.io.File("/var/lib/dpkg").isDirectory -> "deb"
                else -> "source"
            }
        }
    }
}
