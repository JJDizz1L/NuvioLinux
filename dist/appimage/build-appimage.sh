#!/usr/bin/env bash
#
# Builds a portable AppImage (system mpv, baseline x86-64) from the local
# source tree. Requires appimagetool on PATH (or set APPDIR_APPIMAGETOOL).
# On FUSE-less hosts/CI, appimagetool runs with --appimage-extract-and-run.
#
# Usage: ./dist/appimage/build-appimage.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"

APP_IMAGE="composeApp/build/compose/binaries/main/app/Nuvio"
VERSION="$(grep -E '^VERSION_NAME=' composeApp/Configuration/DesktopVersion.properties | cut -d= -f2)"
OUTPUT="Nuvio-${VERSION}-x86_64.AppImage"

APPIMAGETOOL="${APPDIR_APPIMAGETOOL:-appimagetool}"
command -v "${APPIMAGETOOL}" >/dev/null || {
  echo "error: appimagetool not found. Install it or set APPDIR_APPIMAGETOOL." >&2
  exit 1
}

echo "[nuvio-appimage] building app image (version ${VERSION})..."
./gradlew :composeApp:createDistributable

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-appimage.XXXXXX")"
trap 'rm -rf "${TMP}"' EXIT
APPDIR="${TMP}/AppDir"

install -d "${APPDIR}/usr/lib/nuvio"
cp -a "${APP_IMAGE}/." "${APPDIR}/usr/lib/nuvio/"

cat > "${APPDIR}/AppRun" <<'EOF'
#!/bin/sh
exec "$APPDIR/usr/lib/nuvio/bin/Nuvio" "$@"
EOF
chmod +x "${APPDIR}/AppRun"

cat > "${APPDIR}/nuvio-linux.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Nuvio
Comment=Nuvio desktop media player
Exec=AppRun
Icon=nuvio-linux
Categories=AudioVideo;Player;
Terminal=false
StartupNotify=true
MimeType=x-scheme-handler/nuvio;x-scheme-handler/stremio;
EOF

install -Dm644 composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png \
  "${APPDIR}/nuvio-linux.png"

echo "[nuvio-appimage] building ${OUTPUT}..."
APPIMAGE_EXTRACT_AND_RUN=1 "${APPIMAGETOOL}" --appimage-extract-and-run "${APPDIR}" "${OUTPUT}" 2>/dev/null \
  || APPIMAGE_EXTRACT_AND_RUN=1 "${APPIMAGETOOL}" "${APPDIR}" "${OUTPUT}"

ls -la "${OUTPUT}"
echo "[nuvio-appimage] done."
