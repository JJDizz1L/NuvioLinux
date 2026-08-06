package com.nuviolinux.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface NativePlayerEventSink {
    fun onPlayerEvent(type: String, value: Double)
}

internal object NativePlayerBridge {
    private val log = Logger.withTag("NativePlayerBridge")
    private val preloadStarted = AtomicBoolean(false)

    init {
        loadNativeLibrary()
    }

    external fun create(
        hostViewPtr: Long,
        sourceUrl: String,
        headerLines: Array<String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        controlsPageUrl: String,
        decoderPriority: Int,
        streamCacheBytes: Long,
        streamCacheOnDisk: Boolean,
        eventSink: NativePlayerEventSink,
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
    )

    /** Enables the JDK's non-reparenting-WM code path via setenv(3). Must run
     *  before the first AWT/X11 toolkit access (see AwtNonReparentingSupport). */
    external fun setAwtNonReparenting()

    val controlsPageUrl: String by lazy { controlsPageAssets.url }
    private val controlsPageAssets: ControlsPageAssets by lazy { exportControlsPageAssets() }

    fun preloadAsync() {
        if (!preloadStarted.compareAndSet(false, true)) return
        Thread {
            runCatching { controlsPageAssets }
        }.apply {
            name = "nuvio-native-player-preload"
            isDaemon = true
            start()
        }
    }

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
        val input = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?: error("Missing bundled native player bridge: $resource")
        val dir = File(System.getProperty("java.io.tmpdir"), "native-player-bridge").apply { mkdirs() }
        val file = Files.createTempFile(dir.toPath(), "player-bridge-", ".so").toFile()
        file.deleteOnExit()
        input.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        }
        log.d { "loading from extracted temp file: ${file.absolutePath}" }
        System.load(file.absolutePath)
        log.d { "loaded from extracted temp file: ${file.absolutePath}" }
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

    private fun exportControlsPageAssets(): ControlsPageAssets {
        val root = File(System.getProperty("java.io.tmpdir"), "nuvio-player-ui").apply { mkdirs() }
        val fontsDir = root.resolve("fonts").apply { mkdirs() }
        val htmlFile = root.resolve("controls.html")
        writeTextIfChanged(
            target = htmlFile,
            text = readTextResource("/player-ui/controls.html"),
        )
        writeTextIfChanged(
            target = root.resolve("controls.css"),
            text = readTextResource("/player-ui/controls.css")
                .replace("/* __NUVIO_PLAYER_FONT_FACES__ */", nativePlayerFontFaces()),
        )
        copyResourceIfChanged(
            resource = "/player-ui/controls.js",
            target = root.resolve("controls.js"),
        )
        copyResourceIfChanged(
            resource = "/composeResources/nuviolinux.composeapp.generated.resources/font/jetbrains_sans_regular.ttf",
            target = fontsDir.resolve("jetbrains_sans_regular.ttf"),
        )
        copyResourceIfChanged(
            resource = "/composeResources/nuviolinux.composeapp.generated.resources/font/jetbrains_sans_semibold.ttf",
            target = fontsDir.resolve("jetbrains_sans_semibold.ttf"),
        )
        copyResourceIfChanged(
            resource = "/composeResources/nuviolinux.composeapp.generated.resources/font/jetbrains_sans_bold.ttf",
            target = fontsDir.resolve("jetbrains_sans_bold.ttf"),
        )
        return ControlsPageAssets(
            url = htmlFile.toURI().toASCIIString(),
        )
    }

    private fun nativePlayerFontFaces(): String =
        """
            @font-face {
              font-family: "Nuvio Linux JetBrains Sans";
              src: url("fonts/jetbrains_sans_regular.ttf") format("truetype");
              font-weight: 400;
              font-style: normal;
              font-display: block;
            }
            @font-face {
              font-family: "Nuvio Linux JetBrains Sans";
              src: url("fonts/jetbrains_sans_semibold.ttf") format("truetype");
              font-weight: 600;
              font-style: normal;
              font-display: block;
            }
            @font-face {
              font-family: "Nuvio Linux JetBrains Sans";
              src: url("fonts/jetbrains_sans_bold.ttf") format("truetype");
              font-weight: 700 900;
              font-style: normal;
              font-display: block;
            }
        """.trimIndent()

    private fun readTextResource(resource: String): String =
        NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing native player controls resource: $resource")

    private fun writeTextIfChanged(target: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (target.exists() && target.readBytes().contentEquals(bytes)) return
        target.writeBytes(bytes)
    }

    private fun copyResourceIfChanged(resource: String, target: File) {
        val bytes = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?.use { it.readBytes() }
            ?: error("Missing native player controls resource: $resource")
        if (target.exists() && target.readBytes().contentEquals(bytes)) return
        Files.createDirectories(target.parentFile.toPath())
        target.writeBytes(bytes)
    }

    private data class ControlsPageAssets(
        val url: String,
    )
}

internal fun preloadNativePlayerBridgeAsync() {
    runCatching {
        NativePlayerBridge.preloadAsync()
    }
}
