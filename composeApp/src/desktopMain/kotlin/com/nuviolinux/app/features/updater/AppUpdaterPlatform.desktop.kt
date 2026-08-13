package com.nuviolinux.app.features.updater

import com.nuviolinux.app.core.build.AppVersionConfig
import com.nuviolinux.app.core.storage.AppPaths
import com.nuviolinux.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuviolinux.composeapp.generated.resources.Res
import nuviolinux.composeapp.generated.resources.updates_download_failed_http
import nuviolinux.composeapp.generated.resources.updates_downloaded_file_missing
import nuviolinux.composeapp.generated.resources.updates_empty_download_body
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

private const val desktopUpdaterPreferencesName = "updater"
private const val ignoredTagKey = "ignored_release_tag"
private const val lastNotifiedTagKey = "last_notified_release_tag"

private val desktopUpdaterHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

actual object AppUpdaterPlatform {
    private val currentOs: DesktopUpdaterOs = DesktopUpdaterOs.current()
    private val store = DesktopStorage.store(desktopUpdaterPreferencesName)
    actual val isDebugBuild: Boolean = false

    actual val isSupported: Boolean = currentOs != DesktopUpdaterOs.UNKNOWN

    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = "JJDizz1L",
        repo = "NuvioLinux",
        channelBranch = null,
        includePrereleases = true,
        userAgent = "NuvioLinux",
    )

    actual val assetSelector: AppUpdateAssetSelector
        get() = currentOs.assetSelector

    actual val currentVersionName: String = AppVersionConfig.DESKTOP_VERSION_NAME

    actual fun getIgnoredTag(): String? = store.getString(ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        store.putString(ignoredTagKey, tag)
    }

    actual fun getLastNotifiedTag(): String? = store.getString(lastNotifiedTagKey)

    actual fun setLastNotifiedTag(tag: String?) {
        store.putString(lastNotifiedTagKey, tag)
    }

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(updatesDir(), safeName)
            val tempFile = File(updatesDir(), "$safeName.part")
            if (destination.exists()) destination.delete()
            if (tempFile.exists()) tempFile.delete()

            val request = HttpRequest.newBuilder()
                .uri(URI(assetUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
            val response = desktopUpdaterHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                error(runBlocking { getString(Res.string.updates_download_failed_http, response.statusCode()) })
            }

            val totalBytes = response.headers().firstValue("Content-Length").orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            var downloadedBytes = 0L
            response.body()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            } ?: error(runBlocking { getString(Res.string.updates_empty_download_body) })

            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
            destination.absolutePath
        }
    }

    actual fun canInstallDownloadedUpdate(): Boolean = true

    actual fun openInstallPermissionSettings() = Unit

    actual fun installDownloadedUpdate(path: String): Result<Unit> = runCatching {
        val updateFile = File(path)
        check(updateFile.exists()) { runBlocking { getString(Res.string.updates_downloaded_file_missing) } }

        launchInstaller(updateFile)
        scheduleAppExit()
    }

    private fun updatesDir(): File =
        File(AppPaths.cacheDir.resolve("updates").also { it.createDirectories() }.toUri())

    private fun launchInstaller(updateFile: File) {
        val command = listOf("xdg-open", updateFile.absolutePath)
        ProcessBuilder(command).start()
    }

    private fun scheduleAppExit() {
        thread(name = "nuvio-linux-updater-exit", isDaemon = true) {
            Thread.sleep(500)
            exitProcess(0)
        }
    }
}

private enum class DesktopUpdaterOs {
    LINUX,
    UNKNOWN;

    val assetSelector: AppUpdateAssetSelector
        get() {
            val archFragments = desktopArchitectureFragments()
            return when (this) {
                LINUX -> AppUpdateAssetSelector(
                    fileExtensions = listOf(".deb", ".rpm", ".AppImage"),
                    preferredNameFragments = archFragments + listOf("linux"),
                    fallbackNameFragments = listOf("universal", "all"),
                )
                UNKNOWN -> AppUpdateAssetSelector(fileExtensions = emptyList())
            }
        }

    companion object {
        fun current(): DesktopUpdaterOs {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return if (osName.contains("linux")) LINUX else UNKNOWN
        }
    }
}

private fun desktopArchitectureFragments(): List<String> {
    val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    return when {
        arch == "aarch64" || arch == "arm64" -> listOf("arm64", "aarch64")
        arch == "x86" || arch == "i386" || arch == "i686" -> listOf("x86", "i386", "i686")
        arch.contains("64") -> listOf("x64", "x86_64", "amd64")
        else -> emptyList()
    }
}
