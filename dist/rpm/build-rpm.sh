#!/usr/bin/env bash
#
# Builds a Fedora RPM package from the local source tree.
# Requires: Docker (rpmbuild runs inside a Fedora container — Arch's rpm-tools
#           writes empty header digests that Fedora's rpm rejects, issue #15),
#           a baseline x86-64 JDK (e.g. Temurin 25) as JAVA_HOME.
#
# Usage: ./dist/rpm/build-rpm.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-temurin}"

docker info >/dev/null 2>&1 || {
  echo "error: Docker daemon not reachable. The RPM is built inside a Fedora" >&2
  echo "       container (host rpmbuild produces packages Fedora rejects)." >&2
  exit 1
}

echo "[nuvio-linux-rpm] using JAVA_HOME=${JAVA_HOME}"
./gradlew :composeApp:packageReleaseRpm

RPM_DIR="composeApp/build/compose/binaries/main-release/rpm"
ls -la "${RPM_DIR}"/*.rpm
echo "[nuvio-linux-rpm] done."
