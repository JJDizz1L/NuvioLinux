package com.nuviolinux.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuviolinux.app.core.build.AppIdentity
import com.nuviolinux.app.core.display.DisplayServerDetector
import com.nuviolinux.app.core.display.WindowDiagnostics
import com.nuviolinux.app.core.ui.NuvioTheme
import com.nuviolinux.app.core.deeplink.handleAppUrl
import com.nuviolinux.app.features.p2p.P2pStreamingEngine
import com.nuviolinux.app.features.player.PlatformPlayerSurface
import com.nuviolinux.app.features.player.desktop.DesktopAppFullscreenController
import com.nuviolinux.app.features.player.desktop.DesktopWindowGeometry
import com.nuviolinux.app.features.player.desktop.DesktopWindowModeStorage
import com.nuviolinux.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuviolinux.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuviolinux.app.features.player.desktop.registerDesktopAppFullscreenToggle
import java.awt.Desktop
import java.awt.Color as AwtColor
import javax.swing.JComponent

private val NuvioLinuxNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val NuvioLinuxIconPath = "icons/nuvio-app-icon.png"

fun main(args: Array<String>) {
    Logger.withTag("WindowEnvironment").i { "display server: ${DisplayServerDetector.detect()}" }
    installDesktopOpenUriHandler()
    handleDesktopLaunchArgs(args)
    preloadNativePlayerBridgeAsync()

    application {
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        val wasFullscreenOnLastExit = remember { DesktopWindowModeStorage.loadWasFullscreen() }
        val savedGeometry = remember { DesktopWindowModeStorage.loadWindowedGeometry() }
        val windowState = rememberWindowState(
            width = savedGeometry?.width?.dp ?: 1280.dp,
            height = savedGeometry?.height?.dp ?: 820.dp,
            position = savedGeometry?.let { WindowPosition.Absolute(x = it.x.dp, y = it.y.dp) }
                ?: WindowPosition.PlatformDefault,
            placement = if (wasFullscreenOnLastExit) {
                WindowPlacement.Fullscreen
            } else {
                WindowPlacement.Floating
            },
        )
        val fullscreenController = remember { DesktopAppFullscreenController() }

        Window(
            onCloseRequest = {
                P2pStreamingEngine.shutdown()
                exitApplication()
            },
            title = if (smokePlayerUrl == null) AppIdentity.displayName else "Nuvio Linux Player Smoke",
            state = windowState,
            icon = painterResource(NuvioLinuxIconPath),
        ) {
            SideEffect {
                window.background = NuvioLinuxNativeBackground
                window.rootPane.background = NuvioLinuxNativeBackground
                window.contentPane.background = NuvioLinuxNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window) {
                fullscreenController.applyRestoredFullscreenState(window, windowState, wasFullscreenOnLastExit)
            }
            LaunchedEffect(windowState) {
                snapshotFlow { windowState.placement }
                    .collect { placement ->
                        DesktopWindowModeStorage.saveWasFullscreen(placement == WindowPlacement.Fullscreen)
                    }
            }
            LaunchedEffect(windowState) {
                // Only persist geometry while windowed: fullscreen coordinates
                // aren't a meaningful "windowed position" to restore later.
                snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }
                    .collect { (placement, position, size) ->
                        val isWindowed = placement == WindowPlacement.Floating &&
                            !fullscreenController.isFullscreen(window, windowState)
                        if (isWindowed && position.isSpecified) {
                            DesktopWindowModeStorage.saveWindowedGeometry(
                                DesktopWindowGeometry(
                                    x = position.x.value,
                                    y = position.y.value,
                                    width = size.width.value,
                                    height = size.height.value,
                                ),
                            )
                        }
                    }
            }
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle(
                    handler = { targetWindow ->
                        if (targetWindow == null || targetWindow === window) {
                            fullscreenController.toggle(window, windowState)
                            DesktopWindowModeStorage.saveWasFullscreen(
                                fullscreenController.isFullscreen(window, windowState),
                            )
                        }
                    },
                    isFullscreen = { targetWindow ->
                        (targetWindow == null || targetWindow === window) &&
                            fullscreenController.isFullscreen(window, windowState)
                    },
                )
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                val uninstallWindowDiagnostics = WindowDiagnostics.install(window, windowState)
                onDispose {
                    fullscreenController.dispose(window)
                    uninstallFullscreenShortcuts()
                    unregisterFullscreenToggle()
                    uninstallWindowDiagnostics()
                }
            }

            if (smokePlayerUrl == null) {
                App()
            } else {
                NuvioTheme {
                    PlatformPlayerSurface(
                        sourceUrl = smokePlayerUrl,
                        modifier = Modifier.fillMaxSize(),
                        onControllerReady = {},
                        onSnapshot = {},
                        onError = {},
                    )
                }
            }
        }
    }
}

private fun installDesktopOpenUriHandler() {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return

    runCatching {
        desktop.setOpenURIHandler { event ->
            event.uri
                ?.toString()
                ?.trim()
                ?.takeIf(::isDesktopAppUrl)
                ?.let(::handleAppUrl)
        }
    }
}

private fun handleDesktopLaunchArgs(args: Array<String>) {
    args.asSequence()
        .map(String::trim)
        .filter(::isDesktopAppUrl)
        .forEach(::handleAppUrl)
}

private fun isDesktopAppUrl(value: String): Boolean =
    value.startsWith("nuvio://", ignoreCase = true) ||
        value.startsWith("stremio://", ignoreCase = true)
