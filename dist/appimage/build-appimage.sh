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

APP_IMAGE="composeApp/build/compose/binaries/main/app/nuvio-linux"
VERSION="$(grep -E '^VERSION_NAME=' composeApp/Configuration/DesktopVersion.properties | cut -d= -f2)"
OUTPUT="nuvio-linux-${VERSION}-x86_64.AppImage"

APPIMAGETOOL="${APPDIR_APPIMAGETOOL:-appimagetool}"
command -v "${APPIMAGETOOL}" >/dev/null || {
  echo "error: appimagetool not found. Install it or set APPDIR_APPIMAGETOOL." >&2
  exit 1
}

echo "[nuvio-linux-appimage] building app image (version ${VERSION})..."
./gradlew :composeApp:createDistributable

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-appimage.XXXXXX")"
trap 'rm -rf "${TMP}"' EXIT
APPDIR="${TMP}/AppDir"

install -d "${APPDIR}/usr/lib/nuvio-linux"
cp -a "${APP_IMAGE}/." "${APPDIR}/usr/lib/nuvio-linux/"

cat > "${APPDIR}/AppRun" <<'EOF'
#!/bin/sh
exec "$APPDIR/usr/lib/nuvio-linux/bin/nuvio-linux" "$@"
EOF
chmod +x "${APPDIR}/AppRun"

sed \
  -e 's/^Name=Nuvio Linux$/Name=Nuvio Linux AppImage/' \
  -e 's|^Exec=.*|Exec=AppRun %u|' \
  dist/desktop/nuvio-linux.desktop > "${APPDIR}/nuvio-linux.desktop"

for size in 16 32 48 64 128 256 512; do
  install -Dm644 "dist/desktop/icons/hicolor/${size}x${size}/apps/nuvio-linux.png" \
    "${APPDIR}/usr/share/icons/hicolor/${size}x${size}/apps/nuvio-linux.png"
done
install -Dm644 dist/desktop/icons/hicolor/512x512/apps/nuvio-linux.png "${APPDIR}/nuvio-linux.png"

echo "[nuvio-linux-appimage] building ${OUTPUT}..."
APPIMAGE_EXTRACT_AND_RUN=1 "${APPIMAGETOOL}" --appimage-extract-and-run "${APPDIR}" "${OUTPUT}" 2>/dev/null \
  || APPIMAGE_EXTRACT_AND_RUN=1 "${APPIMAGETOOL}" "${APPDIR}" "${OUTPUT}"

ls -la "${OUTPUT}"
echo "[nuvio-linux-appimage] done."
