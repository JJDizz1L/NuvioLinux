package com.nuviolinux.app.core.power

import co.touchlab.kermit.Logger
import com.nuviolinux.app.core.storage.DesktopCache
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * Keeps the desktop awake (screen blanking / suspend) while media plays.
 *
 * Primary: a logind `Inhibit` (sleep:idle) held by a `systemd-inhibit`
 * subprocess that stays alive for as long as playback runs — KDE PowerDevil
 * and other logind-aware power managers honor it, and the process keeps the
 * lock as long as it lives.
 *
 * Flatpak / no-system-bus fallbacks, tried in order:
 *  1. XDG Desktop Portal `org.freedesktop.portal.Inhibit` (v3 API) via the
 *     bundled `portal-inhibit-helper` subprocess. The portal bridges the
 *     session-bus call to the desktop's own inhibit mechanism; on KDE
 *     xdg-desktop-portal-kde calls PowerDevil's PolicyAgent as the host portal
 *     process, which PowerDevil honors (a direct sandboxed PolicyAgent call is
 *     silently dropped). The portal Request is bound to the caller's
 *     connection, so the helper makes the call and blocks, holding the
 *     connection; killing it releases the inhibition. Flags = 12
 *     (Suspend=4 | Idle=8), matching native `systemd-inhibit --what=sleep:idle`.
 *  2. KDE's `org.kde.Solid.PowerManagement.PolicyAgent.AddInhibition` cookie
 *     (release via `ReleaseInhibition`) — kept as a fallback for setups
 *     without a portal, though PowerDevil may drop it.
 *  3. `org.freedesktop.PowerManagement.Inhibit` cookie (legacy powerdevil).
 *  4. `org.freedesktop.ScreenSaver.Inhibit` cookie (GNOME gnome-settings-daemon,
 *     which does block blanking there).
 *
 * Idempotent: [setActive] only acts on state changes; callers may invoke it
 * from any thread.
 */
internal object ScreensaverInhibit {
    private val log = Logger.withTag("ScreensaverInhibit")
    private val lock = Any()

    /** Acquire/release spawn subprocesses and block (Thread.sleep, stream
     *  reads, gdbus waits up to 5s) — never run them on the caller's thread
     *  (setActive is called from the UI thread on every playback-state flip).
     *  Single-threaded executor keeps acquire/release strictly ordered. */
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-screensaver-inhibit").apply { isDaemon = true }
    }

    @Volatile
    private var active = false

    @Volatile
    private var systemdProcess: Process? = null

    @Volatile
    private var portalProcess: Process? = null

    @Volatile
    private var kdeCookie: String? = null

    @Volatile
    private var powerCookie: String? = null

    @Volatile
    private var screensaverCookie: String? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                // Synchronous release: the JVM is exiting, so an executor task
                // could never run — the inhibitor subprocesses would leak and
                // keep holding the logind lock after app close.
                synchronized(lock) {
                    active = false
                    releaseLocked()
                }
            }.apply { name = "nuvio-screensaver-release" },
        )
    }

    fun setActive(inhibit: Boolean) {
        synchronized(lock) {
            if (inhibit == active) return
            active = inhibit
            executor.execute {
                synchronized(lock) {
                    // A newer toggle already superseded this action.
                    if (inhibit != active) return@synchronized
                    if (inhibit) {
                        acquireLocked()
                    } else {
                        releaseLocked()
                    }
                }
            }
        }
    }

    /** Releases any held inhibitor; safe to call at any time. */
    fun release() = setActive(false)

    private fun acquireLocked() {
        if (holdingAnything()) return
        log.i { "acquiring screensaver inhibit" }
        if (acquireSystemdInhibit()) return
        if (acquirePortalInhibit()) return
        if (acquireKdePolicyAgentInhibit()) return
        if (acquireCookie("org.freedesktop.PowerManagement", "org.freedesktop.PowerManagement.Inhibit")) return
        acquireCookie("org.freedesktop.ScreenSaver", "org.freedesktop.ScreenSaver.Inhibit")
    }

    private fun holdingAnything(): Boolean =
        systemdProcess?.isAlive == true ||
            portalProcess?.isAlive == true ||
            kdeCookie != null ||
            powerCookie != null ||
            screensaverCookie != null

    private fun releaseLocked() {
        log.i { "releasing screensaver inhibit" }
        systemdProcess?.let { process ->
            runCatching { process.destroy() }
            systemdProcess = null
        }
        portalProcess?.let { process ->
            runCatching {
                process.destroy()
                if (!process.waitFor(1L, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            portalProcess = null
        }
        kdeCookie?.let { cookie ->
            kdeCookie = null
            gdbusCall(
                "org.kde.Solid.PowerManagement",
                "/org/kde/Solid/PowerManagement/PolicyAgent",
                "org.kde.Solid.PowerManagement.PolicyAgent.ReleaseInhibition",
                cookie,
            )
        }
        powerCookie?.let { cookie ->
            powerCookie = null
            gdbusCall(
                "org.freedesktop.PowerManagement",
                "/org/freedesktop/PowerManagement",
                "org.freedesktop.PowerManagement.UnInhibit",
                cookie,
            )
        }
        screensaverCookie?.let { cookie ->
            screensaverCookie = null
            gdbusCall(
                "org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.UnInhibit",
                cookie,
            )
        }
    }

    /** logind Inhibit via systemd-inhibit: holds the sleep/idle lock for as
     *  long as the subprocess runs. */
    private fun acquireSystemdInhibit(): Boolean {
        val process = runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=sleep:idle",
                "--mode=block",
                "--who=Nuvio Linux",
                "--why=Playing video",
                "sleep", "infinity",
            )
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false
        // Give it a moment to fail fast if the binary or service is missing.
        Thread.sleep(100L)
        if (!process.isAlive) {
            process.inputStream.bufferedReader().readLine()?.let { log.w { "systemd-inhibit failed: $it" } }
            return false
        }
        systemdProcess = process
        log.i { "screensaver inhibit held via systemd-inhibit" }
        return true
    }

    /** XDG Desktop Portal Inhibit (v3), held by the bundled persistent helper.
     *  The helper makes the call and blocks, keeping its session-bus connection
     *  alive — killing it closes the connection and the portal releases the
     *  inhibition. Works in the Flatpak sandbox, which has no system bus. */
    private fun acquirePortalInhibit(): Boolean {
        val helper = resolvePortalHelper() ?: return false
        if (!helper.canExecute()) helper.setExecutable(true)
        val process = runCatching {
            ProcessBuilder(helper.absolutePath, PORTAL_FLAGS.toString())
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false
        val reader = process.inputStream.bufferedReader()
        val line = reader.readLine()
        if (line == null || !line.startsWith("OK ")) {
            log.w { "portal-inhibit-helper failed: ${line?.trim().orEmpty().ifBlank { "no output" }}" }
            runCatching { process.destroy() }
            return false
        }
        if (!process.isAlive) {
            log.w { "portal-inhibit-helper exited immediately" }
            return false
        }
        portalProcess = process
        val handle = line.substringAfter("OK ").trim()
        log.i { "screensaver inhibit held via org.freedesktop.portal.Inhibit (handle $handle)" }
        return true
    }

    /** Locates the bundled portal-inhibit-helper: packaged app resources first,
     *  then the local build directory, then classpath extraction to cache. */
    private fun resolvePortalHelper(): File? {
        System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let { File(it).resolve("native/linux/$HELPER_NAME") }
            ?.takeIf(File::isFile)
            ?.let { return it }

        listOf(
            File("composeApp/build/native/linux/$HELPER_NAME"),
            File("build/native/linux/$HELPER_NAME"),
        ).firstOrNull { it.isFile }?.let { return it }

        val bytes = ScreensaverInhibit::class.java.getResourceAsStream("/native/linux/$HELPER_NAME")
            ?.use { it.readBytes() }
            ?: return null
        val dir = DesktopCache.installVersionedFiles(
            "portal-inhibit-helper",
            mapOf(HELPER_NAME to bytes),
        ).toFile()
        return dir.resolve(HELPER_NAME).takeIf(File::isFile)
    }

    /** KDE native inhibit via powerdevil's PolicyAgent. `types=5` blocks both
     *  suspend (InterruptSession=1) and screen blanking (ChangeScreenSettings=4).
     *  Cookie-based, so a one-shot gdbus call holds it until ReleaseInhibition. */
    private fun acquireKdePolicyAgentInhibit(): Boolean {
        val result = gdbusCall(
            "org.kde.Solid.PowerManagement",
            "/org/kde/Solid/PowerManagement/PolicyAgent",
            "org.kde.Solid.PowerManagement.PolicyAgent.AddInhibition",
            "5",
            "'Nuvio Linux'",
            "'Playing video'",
        ) ?: return false
        val cookie = SCREENSAVER_COOKIE_REGEX.find(result)?.groupValues?.get(1)
        if (cookie == null) {
            log.w { "PolicyAgent.AddInhibition returned unexpected result: $result" }
            return false
        }
        kdeCookie = cookie
        log.i { "screensaver inhibit held via org.kde.Solid.PowerManagement.PolicyAgent (cookie $cookie)" }
        return true
    }

    /** Cookie-based fallback on the session bus (works in the Flatpak
     *  sandbox, which has no system bus access). */
    private fun acquireCookie(destination: String, method: String): Boolean {
        val result = gdbusCall(
            destination,
            if (destination == "org.freedesktop.PowerManagement") {
                "/org/freedesktop/PowerManagement"
            } else {
                "/org/freedesktop/ScreenSaver"
            },
            method,
            "'Nuvio Linux'",
            "'Playing video'",
        ) ?: return false
        val cookie = SCREENSAVER_COOKIE_REGEX.find(result)?.groupValues?.get(1)
        if (cookie == null) {
            log.w { "$method returned unexpected result: $result" }
            return false
        }
        if (destination == "org.freedesktop.PowerManagement") {
            powerCookie = cookie
        } else {
            screensaverCookie = cookie
        }
        log.i { "screensaver inhibit held via $destination (cookie $cookie)" }
        return true
    }

    private fun gdbusCall(destination: String, path: String, method: String, vararg args: String): String? =
        runCatching {
            val process = ProcessBuilder(
                listOf(
                    "gdbus", "call", "--session",
                    "--dest", destination,
                    "--object-path", path,
                    "--method", method,
                ) + args,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                process.destroy()
                null
            } else if (process.exitValue() == 0) {
                output.trim()
            } else {
                log.w { "$method failed (exit ${process.exitValue()}): ${output.trim().take(200)}" }
                null
            }
        }.getOrNull()

    private val SCREENSAVER_COOKIE_REGEX = Regex("""\(uint32 (\d+),""")

    private const val HELPER_NAME = "portal-inhibit-helper"

    /** Portal Inhibit flags: 4 = Suspend, 8 = Idle (screen blanking). */
    private const val PORTAL_FLAGS = 12
}
