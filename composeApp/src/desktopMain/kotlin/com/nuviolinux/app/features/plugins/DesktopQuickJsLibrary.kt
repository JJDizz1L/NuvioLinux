package com.nuviolinux.app.features.plugins

import com.nuviolinux.app.core.storage.DesktopCache
import java.util.Locale

private const val QuickJsLibraryPathProperty = "com.dokar.quickjs.library.path"
private const val QuickJsLibraryNameProperty = "com.dokar.quickjs.library.name"

internal fun configureDesktopQuickJsLibrary() {
    if (!System.getProperty(QuickJsLibraryPathProperty).isNullOrBlank()) return
    val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val osPrefix = when {
        osName.contains("linux") -> "linux"
        osName.contains("mac") || osName.contains("osx") -> "macos"
        osName.contains("windows") -> "windows"
        else -> return
    }
    val osArch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    val archSuffix = when (osArch) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64", "x64" -> "x64"
        else -> return
    }
    val extension = when (osPrefix) {
        "linux" -> "so"
        "macos" -> "dylib"
        else -> "dll"
    }
    val libraryName = "libquickjs.$extension"
    val resourceFolder = "${osPrefix}_$archSuffix"
    val resource = "jni/$resourceFolder/$libraryName"
    val bytes = requireNotNull(
        DesktopQuickJsLibrary::class.java.classLoader?.getResourceAsStream(resource)?.use { it.readBytes() },
    ) { "Missing QuickJS native library resource: $resource" }
    val directory = DesktopCache.installVersionedFiles(
        namespace = "quickjs/$resourceFolder",
        files = mapOf(libraryName to bytes),
    )
    System.setProperty(QuickJsLibraryPathProperty, directory.toAbsolutePath().toString())
    System.setProperty(QuickJsLibraryNameProperty, libraryName)
}

private object DesktopQuickJsLibrary
