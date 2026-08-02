package com.nuviolinux.app.core.storage

import java.nio.file.Path
import java.nio.file.Paths

/**
 * XDG Base Directory compliant app paths for Nuvio Linux.
 *
 * - config  -> $XDG_CONFIG_HOME/nuvio-linux   (settings, profiles)
 * - state   -> $XDG_STATE_HOME/nuvio-linux    (window state, transient app state)
 * - cache   -> $XDG_CACHE_HOME/nuvio-linux    (extracted binaries, update installers)
 * - downloads -> xdg-user-dir DOWNLOAD        (user media, not app-owned data)
 *
 * Inside a Flatpak sandbox the XDG_*_HOME variables point into
 * ~/.var/app/io.github.jjdizz1l.NuvioLinux/, so no extra handling is needed.
 */
internal object AppPaths {
    private val userHome: Path
        get() = Paths.get(System.getProperty("user.home").orEmpty())

    val configDir: Path by lazy {
        xdgHome("XDG_CONFIG_HOME")?.resolve("nuvio-linux") ?: userHome.resolve(".config").resolve("nuvio-linux")
    }

    val stateDir: Path by lazy {
        xdgHome("XDG_STATE_HOME")?.resolve("nuvio-linux") ?: userHome.resolve(".local").resolve("state").resolve("nuvio-linux")
    }

    val cacheDir: Path by lazy {
        xdgHome("XDG_CACHE_HOME")?.resolve("nuvio-linux") ?: userHome.resolve(".cache").resolve("nuvio-linux")
    }

    val downloadDir: Path by lazy {
        val configured = runCatching { resolveUserDownloadDir() }
            .getOrNull()
            ?.takeIf { it.isAbsolute }
        configured ?: userHome.resolve("Downloads")
    }

    private fun resolveUserDownloadDir(): Path? {
        val process = ProcessBuilder("xdg-user-dir", "DOWNLOAD")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (output.isBlank()) return null
        return runCatching { Paths.get(output) }.getOrNull()
    }

    private fun xdgHome(envName: String): Path? =
        System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { Paths.get(path) }.getOrNull()
        }
}
