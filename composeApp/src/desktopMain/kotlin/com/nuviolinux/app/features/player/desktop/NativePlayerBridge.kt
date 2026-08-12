package com.nuviolinux.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuviolinux.app.core.storage.DesktopCache
import java.io.File

internal object NativePlayerBridge {
    private val log = Logger.withTag("NativePlayerBridge")

    init {
        loadNativeLibrary()
    }

    external fun create(
        hostViewPtr: Long,
        sourceUrl: String,
        headerLines: Array<String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        decoderPriority: Int,
        streamCacheBytes: Long,
        streamCacheOnDisk: Boolean,
    ): Long

    external fun dispose(handle: Long)
    external fun renderFrame(handle: Long, width: Int, height: Int, buffer: java.nio.ByteBuffer): Boolean
    external fun updateControls(handle: Long, controlsJson: String)
    external fun requestFocus(handle: Long)
    external fun setPaused(handle: Long, paused: Boolean)
    external fun seekTo(handle: Long, positionMs: Long)
    external fun seekBy(handle: Long, offsetMs: Long)
    external fun setSpeed(handle: Long, speed: Float)
    external fun adjustVolume(handle: Long, delta: Float)
    external fun setVolume(handle: Long, level: Float)
    external fun volume(handle: Long): Float
    external fun setResizeMode(handle: Long, mode: Int)
    external fun durationMs(handle: Long): Long
    external fun positionMs(handle: Long): Long
    external fun bufferedPositionMs(handle: Long): Long
    external fun isLoading(handle: Long): Boolean
    external fun isEnded(handle: Long): Boolean
    external fun isPaused(handle: Long): Boolean
    external fun speed(handle: Long): Float
    external fun audioTracksJson(handle: Long): String
    external fun subtitleTracksJson(handle: Long): String
    external fun selectAudioTrack(handle: Long, trackId: Int)
    external fun selectSubtitleTrack(handle: Long, trackId: Int)
    external fun addSubtitleUrl(handle: Long, url: String)
    external fun clearExternalSubtitles(handle: Long)
    external fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int)
    external fun setSubtitleDelayMs(handle: Long, delayMs: Int)
    external fun applySubtitleStyle(
        handle: Long,
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
        useLibass: Boolean,
    )

    /** Enables the JDK's non-reparenting-WM code path via setenv(3). Must run
     *  before the first AWT/X11 toolkit access (see AwtNonReparentingSupport). */
    external fun setAwtNonReparenting()

    private fun loadNativeLibrary() {
        val platform = DesktopHostOs.current
        require(platform == DesktopHostOs.LINUX) {
            "Native desktop playback is only supported on Linux, got $platform."
        }

        val libraryName = "libplayer_bridge.so"
        val platformDir = "linux"
        findPackagedApplicationLibrary(platformDir, libraryName)?.let { packagedLibrary ->
            log.d { "loading from packaged app resources: ${packagedLibrary.absolutePath}" }
            System.load(packagedLibrary.absolutePath)
            log.d { "loaded from packaged app resources: ${packagedLibrary.absolutePath}" }
            return
        }
        log.d { "lib not found in packaged app resources, trying local build directory" }
        findLocalBuildLibrary(platformDir, libraryName)?.let { localLibrary ->
            log.d { "loading from local build: ${localLibrary.absolutePath}" }
            System.load(localLibrary.absolutePath)
            log.d { "loaded from local build: ${localLibrary.absolutePath}" }
            return
        }
        log.d { "lib not found in local build, extracting from classpath resources" }

        val resource = "/native/$platformDir/$libraryName"
        val files = buildMap {
            put(libraryName, readResourceBytes(resource))
            bundledRuntimeResourceNames(platformDir).forEach { name ->
                resourceBytesOrNull("/native/$platformDir/$name")?.let { bytes -> put(name, bytes) }
            }
        }
        val directory = DesktopCache.installVersionedFiles("native-player-bridge/$platformDir", files).toFile()
        loadNativeRuntimeDependencies(platform, directory)
        log.d { "loading from extracted cache file: ${directory.resolve(libraryName).absolutePath}" }
        System.load(directory.resolve(libraryName).absolutePath)
        log.d { "loaded from extracted cache file: ${directory.resolve(libraryName).absolutePath}" }
    }

    private fun findPackagedApplicationLibrary(platformDir: String, libraryName: String): File? {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return null
        return resourcesDir.resolve("native/$platformDir/$libraryName").takeIf(File::isFile)
    }

    private fun findLocalBuildLibrary(platformDir: String, libraryName: String): File? {
        val roots = listOf(
            File("composeApp/build/native/$platformDir"),
            File("build/native/$platformDir"),
        )
        return roots.map { it.resolve(libraryName) }.firstOrNull { it.exists() }
    }

    private fun loadNativeRuntimeDependencies(platform: DesktopHostOs, directory: File) {
        if (platform != DesktopHostOs.LINUX) return
        // Linux needs no bundled runtime dependencies: libmpv and libEGL are
        // loaded dynamically by the bridge at runtime.
    }

    private fun bundledRuntimeResourceNames(platformDir: String): List<String> {
        val indexResource = "/native/$platformDir/runtime-files.txt"
        val indexed = NativePlayerBridge::class.java.getResourceAsStream(indexResource)
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
            .orEmpty()
        if (indexed.isNotEmpty()) return indexed
        return when (platformDir) {
            "linux" -> emptyList()
            else -> emptyList()
        }
    }

    private fun readResourceBytes(resource: String): ByteArray =
        resourceBytesOrNull(resource) ?: error("Missing native player resource: $resource")

    private fun resourceBytesOrNull(resource: String): ByteArray? =
        NativePlayerBridge::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
}
