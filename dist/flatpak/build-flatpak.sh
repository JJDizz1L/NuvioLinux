#!/usr/bin/env bash
#
# Builds a Flatpak bundle (io.github.jjdizz1l.NuvioLinux) from the local
# source tree. libmpv + deps are built from source inside the sandbox for
# baseline x86-64 portability.
#
# Usage: ./dist/flatpak/build-flatpak.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"

MANIFEST="dist/flatpak/io.github.jjdizz1l.NuvioLinux.yml"
BUILD_DIR="dist/flatpak/build"
REPO_DIR="dist/flatpak/repo"
VERSION="$(grep -E '^VERSION_NAME=' composeApp/Configuration/DesktopVersion.properties | cut -d= -f2)"
BUNDLE="dist/flatpak/nuvio-linux-${VERSION}.flatpak"

echo "[nuvio-flatpak] building app image (version ${VERSION})..."
./gradlew :composeApp:createDistributable

echo "[nuvio-flatpak] assembling flatpak sources..."
SRC="dist/flatpak/flatpak-src"
rm -rf "${SRC}"
mkdir -p "${SRC}"
cp -a composeApp/build/compose/binaries/main/app/Nuvio "${SRC}/Nuvio"

cat > "${SRC}/nuvio" <<EOF
#!/bin/sh
exec /app/nuvio/bin/Nuvio "\$@"
EOF
chmod +x "${SRC}/nuvio"

cat > "${SRC}/nuvio-linux.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Nuvio
Comment=Nuvio desktop media player
Exec=nuvio
Icon=nuvio-linux
Categories=AudioVideo;Player;
Terminal=false
StartupNotify=true
MimeType=x-scheme-handler/nuvio;x-scheme-handler/stremio;
EOF

if command -v convert >/dev/null 2>&1; then
  convert composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png -resize 512x512 "${SRC}/nuvio-linux.png"
else
  cp composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png "${SRC}/nuvio-linux.png"
fi

echo "[nuvio-flatpak] building (this compiles ffmpeg/libplacebo/libmpv — takes a while)..."
flatpak-builder --force-clean --ccache --state-dir="${BUILD_DIR}/.cache" \
  "${BUILD_DIR}/app" "${MANIFEST}" 2>&1 | tail -20

echo "[nuvio-flatpak] exporting repo..."
rm -rf "${REPO_DIR}"
flatpak build-export "${REPO_DIR}" "${BUILD_DIR}/app"

echo "[nuvio-flatpak] bundling ${BUNDLE}..."
flatpak build-bundle "${REPO_DIR}" "${BUNDLE}" io.github.jjdizz1l.NuvioLinux stable

ls -la "${BUNDLE}"
echo "[nuvio-flatpak] done."
