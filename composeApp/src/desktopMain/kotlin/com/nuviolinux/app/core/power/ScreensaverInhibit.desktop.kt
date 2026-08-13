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
 *  1. KDE's native `org.kde.Solid.PowerManagement` suppress API
 *     (`beginSuppressingSleep` + `beginSuppressingScreenPowerManagement`) —
 *     the same calls `xdg-desktop-portal-kde` makes internally for the Inhibit
 *     portal. Cookie-free and not tied to the caller connection, so a one-shot
 *     `gdbus` call holds it until the matching `stop*` call. This is the only
 *     thing that actually blocks PowerDevil's screen blanking on Plasma 6:
 *     `org.freedesktop.ScreenSaver.Inhibit` (ksmserver) only guards the locker,
 *     and `org.freedesktop.PowerManagement` is no longer implemented.
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
    private var kdeSleepSuppressed = false

    @Volatile
    private var kdeScreenSuppressed = false

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
        if (acquireKdeSuppress()) return
        if (acquireCookie("org.freedesktop.PowerManagement", "org.freedesktop.PowerManagement.Inhibit")) return
        acquireCookie("org.freedesktop.ScreenSaver", "org.freedesktop.ScreenSaver.Inhibit")
    }

    private fun holdingAnything(): Boolean =
        systemdProcess?.isAlive == true ||
            kdeSleepSuppressed ||
            kdeScreenSuppressed ||
            powerCookie != null ||
            screensaverCookie != null

    private fun releaseLocked() {
        log.i { "releasing screensaver inhibit" }
        systemdProcess?.let { process ->
            runCatching { process.destroy() }
            systemdProcess = null
        }
        if (kdeScreenSuppressed) {
            gdbusCall(
                "org.kde.Solid.PowerManagement",
                "/org/kde/Solid/PowerManagement",
                "org.kde.Solid.PowerManagement.stopSuppressingScreenPowerManagement",
            )
            kdeScreenSuppressed = false
        }
        if (kdeSleepSuppressed) {
            gdbusCall(
                "org.kde.Solid.PowerManagement",
                "/org/kde/Solid/PowerManagement",
                "org.kde.Solid.PowerManagement.stopSuppressingSleep",
            )
            kdeSleepSuppressed = false
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

    /** KDE native suppress API (org.kde.Solid.PowerManagement): blocks PowerDevil
     *  screen blanking and suspend until the matching stop* call. Cookie-free and
     *  not connection-bound, so a one-shot gdbus call holds it. */
    private fun acquireKdeSuppress(): Boolean {
        val sleepOk = gdbusCall(
            "org.kde.Solid.PowerManagement",
            "/org/kde/Solid/PowerManagement",
            "org.kde.Solid.PowerManagement.beginSuppressingSleep",
        ) != null
        val screenOk = gdbusCall(
            "org.kde.Solid.PowerManagement",
            "/org/kde/Solid/PowerManagement",
            "org.kde.Solid.PowerManagement.beginSuppressingScreenPowerManagement",
        ) != null
        if (!sleepOk && !screenOk) return false
        kdeSleepSuppressed = sleepOk
        kdeScreenSuppressed = screenOk
        log.i { "screensaver inhibit held via org.kde.Solid.PowerManagement (sleep=$sleepOk screen=$screenOk)" }
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
