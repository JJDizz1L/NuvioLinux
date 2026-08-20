#!/usr/bin/env bash
#
# Checks that a built Nuvio Linux app image is portable to baseline x86-64
# (no x86-64-v4 / AVX-512 code in the bundled JRE, the jpackage launcher, or
# the in-jar libplayer_bridge.so).
#
# This guards against accidentally bundling a distro JDK built with
# -march=x86-64-v4 (e.g. CachyOS's cachyos-znver4 jdk21-openjdk), which fails
# at launch with "CPU ISA level is lower than required" on v3 CPUs (issue #3).
#
# Usage: ./dist/check-portability.sh <app-image-dir>
#   <app-image-dir>  e.g. composeApp/build/compose/binaries/main-release/app/nuvio-linux
#
# Exit code 0 = portable, 1 = v4/AVX-512 code found.
set -euo pipefail

APP_DIR="${1:-composeApp/build/compose/binaries/main-release/app/nuvio-linux}"

fail=0
missing=0

check_binary() {
    local file="$1"

    if [[ ! -f "${file}" ]]; then
        echo "error: ${file} not found" >&2
        missing=1
        return
    fi

    echo "checking ${file#"$APP_DIR"/}..."
    if readelf -n "${file}" 2>/dev/null | grep -q 'x86 ISA needed:.*x86-64-v4'; then
        echo "FAIL: ${file} requires x86-64-v4 (AVX-512)" >&2
        fail=1
    fi
    local zmm
    zmm="$(objdump -d -j .text "${file}" 2>/dev/null | grep -c 'zmm' || true)"
    # A baseline build may contain a handful of CPUID-guarded AVX-512
    # dispatch stubs (e.g. 3 in Temurin's libjvm); a -march=x86-64-v4 build
    # has tens of thousands.
    if (( zmm > 10 )); then
        echo "FAIL: ${file} has ${zmm} zmm (AVX-512) instructions" >&2
        fail=1
    fi
}

check_binary "${APP_DIR}/lib/runtime/lib/server/libjvm.so"
check_binary "${APP_DIR}/lib/runtime/lib/libawt.so"
check_binary "${APP_DIR}/lib/libapplauncher.so"
check_binary "${APP_DIR}/bin/nuvio-linux"

# The C++ mpv bridge ships inside the application jar at native/linux/.
TMP_BRIDGE="$(mktemp "${TMPDIR:-/tmp}/libplayer_bridge.XXXXXX.so")"
trap 'rm -f "${TMP_BRIDGE}"' EXIT
jar_path="$(ls "${APP_DIR}"/lib/app/composeApp-desktop*.jar 2>/dev/null | head -n 1 || true)"
if [[ -n "${jar_path}" ]]; then
    if unzip -p "${jar_path}" native/linux/libplayer_bridge.so > "${TMP_BRIDGE}" 2>/dev/null \
        && [[ -s "${TMP_BRIDGE}" ]]; then
        check_binary "${TMP_BRIDGE}"
    else
        echo "note: native/linux/libplayer_bridge.so not found in ${jar_path}"
    fi
else
    echo "note: no application jar found under ${APP_DIR}/lib/app"
fi

if [[ "${missing}" != 0 ]]; then
    echo "error: some expected binaries are missing from the app image" >&2
    exit 1
fi

if [[ "${fail}" != 0 ]]; then
    echo "error: app image is not baseline x86-64 portable — rebuild with a baseline JDK" >&2
    exit 1
fi

echo "OK: app image is baseline x86-64 portable"
