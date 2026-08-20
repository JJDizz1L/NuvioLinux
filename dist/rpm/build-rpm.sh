#!/usr/bin/env bash
#
# Builds a Fedora RPM package from the local source tree.
# Requires: rpmbuild (Arch: `pacman -S rpm-tools`, Fedora: `dnf install rpm-build`),
#           a baseline x86-64 JDK (e.g. Temurin 25) as JAVA_HOME.
#
# Usage: ./dist/rpm/build-rpm.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-temurin}"

command -v rpmbuild >/dev/null || {
  echo "error: rpmbuild not found. Install rpm-tools (Arch) or rpm-build (Fedora)." >&2
  exit 1
}

echo "[nuvio-linux-rpm] using JAVA_HOME=${JAVA_HOME}"
./gradlew :composeApp:packageReleaseRpm

RPM_DIR="composeApp/build/compose/binaries/main-release/rpm"
ls -la "${RPM_DIR}"/*.rpm
echo "[nuvio-linux-rpm] done."
