#!/usr/bin/env bash
#
# Checks that a built Nuvio Linux app image is portable to baseline x86-64
# (no x86-64-v4 / AVX-512 code in the bundled JRE or the jpackage launcher).
#
# This guards against accidentally bundling a distro JDK built with
# -march=x86-64-v4 (e.g. CachyOS's cachyos-znver4 jdk21-openjdk), which fails
# at launch with "CPU ISA level is lower than required" on v3 CPUs (issue #3).
#
# Usage: ./dist/check-portability.sh <app-image-dir>
#   <app-image-dir>  e.g. composeApp/build/compose/binaries/main/app/nuvio-linux
#
# Exit code 0 = portable, 1 = v4/AVX-512 code found.
set -euo pipefail

APP_DIR="${1:-composeApp/build/compose/binaries/main/app/nuvio-linux}"

LIBJVM="${APP_DIR}/lib/runtime/lib/server/libjvm.so"
LAUNCHER="${APP_DIR}/bin/nuvio-linux"

fail=0

for f in "${LIBJVM}" "${LAUNCHER}"; do
    if [[ ! -f "${f}" ]]; then
        echo "error: ${f} not found" >&2
        exit 1
    fi
    echo "checking $(basename "${f}")..."
    if readelf -n "${f}" 2>/dev/null | grep -q 'x86 ISA needed:.*x86-64-v4'; then
        echo "FAIL: ${f} requires x86-64-v4 (AVX-512)" >&2
        fail=1
    fi
    zmm="$(objdump -d -j .text "${f}" 2>/dev/null | grep -c 'zmm' || true)"
    # A baseline build may contain a handful of CPUID-guarded AVX-512
    # dispatch stubs (e.g. 3 in Temurin's libjvm); a -march=x86-64-v4 build
    # has tens of thousands.
    if (( zmm > 10 )); then
        echo "FAIL: ${f} has ${zmm} zmm (AVX-512) instructions" >&2
        fail=1
    fi
done

if [[ "${fail}" != 0 ]]; then
    echo "error: app image is not baseline x86-64 portable — rebuild with a baseline JDK" >&2
    exit 1
fi

echo "OK: app image is baseline x86-64 portable"
