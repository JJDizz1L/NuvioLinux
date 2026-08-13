package com.nuviolinux.app.core.power

import co.touchlab.kermit.Logger
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
 *  1. KDE's `org.kde.Solid.PowerManagement.PolicyAgent` at
 *     `/org/kde/Solid/PowerManagement/PolicyAgent` — `AddInhibition(types,
 *     app_name, reason)` returns a cookie, released with `ReleaseInhibition`.
 *     This is exactly what `xdg-desktop-portal-kde` calls internally for the
 *     Inhibit portal, and powerdevil bridges it to logind on the host, so it
 *     works from the sandbox. `types` is a bitmask: `1` = InterruptSession
 *     (sleep), `4` = ChangeScreenSettings (screen blanking/idle) — `5` = both.
 *     On Plasma 6 the older `org.freedesktop.ScreenSaver.Inhibit` is a compat
 *     no-op and `beginSuppressing*` no longer exists, so this is the only
 *     reliable session-bus inhibit on KDE.
 *  2. `org.freedesktop.PowerManagement.Inhibit` cookie (legacy powerdevil).
 *  3. `org.freedesktop.ScreenSaver.Inhibit` cookie (GNOME gnome-settings-daemon,
 *     which does block blanking there).
 *
 * Idempotent: [setActive] only acts on state changes; callers may invoke it
 * from any thread.
 */
internal object ScreensaverInhibit {
    private val log = Logger.withTag("ScreensaverInhibit")
    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var systemdProcess: Process? = null

    @Volatile
    private var kdeCookie: String? = null

    @Volatile
    private var powerCookie: String? = null

    @Volatile
    private var screensaverCookie: String? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { setActive(false) })
    }

    fun setActive(inhibit: Boolean) {
        synchronized(lock) {
            if (inhibit == active) return
            active = inhibit
            if (inhibit) {
                acquireLocked()
            } else {
                releaseLocked()
            }
        }
    }

    /** Releases any held inhibitor; safe to call at any time. */
    fun release() = setActive(false)

    private fun acquireLocked() {
        if (holdingAnything()) return
        log.i { "acquiring screensaver inhibit" }
        if (acquireSystemdInhibit()) return
        if (acquireKdePolicyAgentInhibit()) return
        if (acquireCookie("org.freedesktop.PowerManagement", "org.freedesktop.PowerManagement.Inhibit")) return
        acquireCookie("org.freedesktop.ScreenSaver", "org.freedesktop.ScreenSaver.Inhibit")
    }

    private fun holdingAnything(): Boolean =
        systemdProcess?.isAlive == true ||
            kdeCookie != null ||
            powerCookie != null ||
            screensaverCookie != null

    private fun releaseLocked() {
        log.i { "releasing screensaver inhibit" }
        systemdProcess?.let { process ->
            runCatching { process.destroy() }
            systemdProcess = null
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
}
