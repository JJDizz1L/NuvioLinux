/*
 * portal_inhibit_helper.c — persistent holder for the XDG Desktop Portal
 * Inhibit API (org.freedesktop.portal.Inhibit, version 3).
 *
 * Sandboxed apps (Flatpak) have no system-bus access, so they can't reach
 * logind directly. The portal bridges a session-bus Inhibit call to the
 * desktop's own inhibit mechanism (on KDE, xdg-desktop-portal-kde calls
 * PowerDevil's PolicyAgent as the host portal process, which PowerDevil
 * honors — a direct sandboxed PolicyAgent call is silently dropped).
 *
 * The portal Request object is bound to the *calling connection*: when the
 * caller's unique bus name drops, xdg-desktop-portal closes the request and
 * the backend releases the inhibition. A one-shot `gdbus call` would therefore
 * release the inhibitor the instant it returned. This helper instead makes the
 * call and then blocks forever, keeping its session-bus connection alive; the
 * inhibition is released when this process dies (SIGTERM/SIGINT/SIGKILL all
 * close the connection). No explicit Request.Close is needed.
 *
 * Usage: portal-inhibit-helper <flags>
 *   prints "OK <request-handle>" on success and stays alive; prints an error
 *   line and exits non-zero on failure.
 *
 * Built with: gcc -O2 $(pkg-config --cflags --libs gio-2.0) (baseline x86-64).
 */

#include <gio/gio.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>

int main(int argc, char **argv) {
    guint32 flags = 0;
    if (argc < 2 || sscanf(argv[1], "%u", &flags) != 1 || flags == 0) {
        fprintf(stderr, "usage: %s <flags>\n", argv[0] ? argv[0] : "portal-inhibit-helper");
        return 2;
    }

    GError *error = NULL;
    GDBusConnection *conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (conn == NULL) {
        fprintf(stderr, "session bus: %s\n", error ? error->message : "unknown error");
        g_clear_error(&error);
        return 1;
    }

    GVariantBuilder options;
    g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&options, "{sv}", "reason", g_variant_new_string("Playing video"));

    GVariant *reply = g_dbus_connection_call_sync(
        conn,
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.Inhibit",
        "Inhibit",
        g_variant_new("(sua{sv})", "", flags, &options),
        G_VARIANT_TYPE("(o)"),
        G_DBUS_CALL_FLAGS_NONE,
        30000,
        NULL,
        &error);

    if (reply == NULL) {
        fprintf(stderr, "Inhibit failed: %s\n", error ? error->message : "unknown error");
        g_clear_error(&error);
        return 1;
    }

    const char *handle = NULL;
    g_variant_get(reply, "(&o)", &handle);
    printf("OK %s\n", handle);
    fflush(stdout);
    g_variant_unref(reply);

    /* Ignore SIGTERM/SIGINT: default termination is what we want — the
     * connection closes and the portal releases the inhibition. */
    signal(SIGTERM, SIG_DFL);
    signal(SIGINT, SIG_DFL);
    signal(SIGPIPE, SIG_IGN);

    /* Block forever, keeping the GDBusConnection (and thus the request)
     * alive. g_bus_get_sync returns a shared connection owned by the process;
     * as long as we don't exit or close it, the unique name stays registered
     * and the portal keeps the request open. */
    for (;;) {
        g_usleep(G_USEC_PER_SEC);
    }

    return 0;
}
