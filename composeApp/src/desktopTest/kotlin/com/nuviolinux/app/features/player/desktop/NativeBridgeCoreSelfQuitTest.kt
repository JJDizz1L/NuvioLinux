package com.nuviolinux.app.features.player.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression harness for the dispose-time SIGABRT (2026-08-22 crash):
 * when mpv's core quits on its own (MPV_EVENT_SHUTDOWN — e.g. an auto-profile
 * switched vo away from libmpv and the popped-out window was closed, or an
 * idle=no config), the event loop set running=false and exited WITHOUT being
 * joined. destroy() then skipped the join behind a CAS on running, and
 * ~MpvPlayer destroyed a finished-but-joinable std::thread → std::terminate.
 *
 * Repro recipe (run from a shell with a fresh Gradle daemon so env carries):
 *
 *   export JAVA_HOME=/usr/lib/jvm/java-25-temurin
 *   mkdir -p /tmp/opencode/mpvconf-repro && echo idle=no > /tmp/opencode/mpvconf-repro/mpv.conf
 *   ./gradlew --stop
 *   export MPV_HOME=/tmp/opencode/mpvconf-repro
 *   ./gradlew :composeApp:desktopTest --tests "*NativeBridgeCoreSelfQuit*"
 *
 * Pre-fix this aborts the test JVM (SIGABRT); post-fix it passes.
 */
class NativeBridgeCoreSelfQuitTest {

    @Test
    fun disposeAfterCoreSelfQuitExitsCleanly() {
        assumeTrue(
            "MPV_HOME (idle=no conf dir) not exported to the test JVM",
            !System.getenv("MPV_HOME").isNullOrBlank(),
        )

        val handle = NativePlayerBridge.create(
            hostViewPtr = 0L,
            sourceUrl = "http://127.0.0.1:9/dead.mp4",
            sourceAudioUrl = null,
            headerLines = emptyArray(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            streamCacheBytes = 64L * 1024 * 1024,
            streamCacheOnDisk = false,
        )
        check(handle != 0L) { "bridge create returned no handle" }

        // Give the failed open time to quit the core (idle=no) and let the
        // event loop process MPV_EVENT_SHUTDOWN + exit unjoined.
        Thread.sleep(6000)

        NativePlayerBridge.dispose(handle)
    }
}
