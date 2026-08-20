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

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-temurin}"

MANIFEST="dist/flatpak/io.github.jjdizz1l.NuvioLinux.yml"
BUILD_DIR="dist/flatpak/build"
REPO_DIR="dist/flatpak/repo"
VERSION="$(grep -E '^VERSION_NAME=' composeApp/Configuration/DesktopVersion.properties | cut -d= -f2)"
PACKAGE_RELEASE="${NUVIO_PACKAGE_RELEASE:-1}"
BUNDLE="dist/flatpak/nuvio-linux-${VERSION}-${PACKAGE_RELEASE}.flatpak"

echo "[nuvio-linux-flatpak] building app image (version ${VERSION})..."
./gradlew :composeApp:createReleaseDistributable

echo "[nuvio-linux-flatpak] assembling flatpak sources..."
SRC="dist/flatpak/flatpak-src"
rm -rf "${SRC}"
mkdir -p "${SRC}"
cp -a composeApp/build/compose/binaries/main-release/app/nuvio-linux "${SRC}/nuvio-app"

cat > "${SRC}/nuvio-linux" <<EOF
#!/bin/sh
# Point libva at the VA-API drivers:
#  - Mesa GL extension's dri dir (radeonsi for AMD, nouveau)
#  - the app-mounted VAAPI.Intel extension dir (iHD for Intel iGPUs)
#  - the runtime-mounted VAAPI.nvidia extension dir (nvidia_drv_video.so -> NVDEC)
#  - libva's default dir
# None of these are in libva's default search path.
export LIBVA_DRIVERS_PATH="/usr/lib/x86_64-linux-gnu/GL/default/lib/dri:/app/lib/intel-vaapi-driver:/usr/lib/x86_64-linux-gnu/dri/nvidia-vaapi-driver:/usr/lib/x86_64-linux-gnu/dri"
# The nvidia-vaapi-driver shim is named "nvidia" and is NOT auto-detected by
# libva — but LIBVA_DRIVER_NAME allows NO fallback, so it must be set ONLY on
# NVIDIA systems (on AMD/Intel it would break VAAPI entirely).
if [ -e /dev/nvidiactl ] || [ -d /sys/module/nvidia ]; then
  export LIBVA_DRIVER_NAME=nvidia
fi
exec /app/nuvio/bin/nuvio-linux "\$@"
EOF
chmod +x "${SRC}/nuvio-linux"

sed \
  -e 's/^Name=Nuvio Linux$/Name=Nuvio Linux Flatpak/' \
  -e 's|^Exec=.*|Exec=nuvio-linux %u|' \
  dist/desktop/nuvio-linux.desktop > "${SRC}/nuvio-linux.desktop"

sed \
  -e 's/<name>Nuvio Linux<\/name>/<name>Nuvio Linux Flatpak<\/name>/' \
  dist/desktop/io.github.jjdizz1l.NuvioLinux.metainfo.xml > "${SRC}/io.github.jjdizz1l.NuvioLinux.metainfo.xml"

if command -v convert >/dev/null 2>&1; then
  convert composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png -resize 512x512 "${SRC}/nuvio-linux.png"
else
  cp dist/desktop/icons/hicolor/512x512/apps/nuvio-linux.png "${SRC}/nuvio-linux.png"
fi

echo "[nuvio-linux-flatpak] building (this compiles ffmpeg/libplacebo/libmpv — takes a while)..."
flatpak-builder --force-clean --ccache --default-branch=stable --state-dir="${BUILD_DIR}/.cache" \
  "${BUILD_DIR}/app" "${MANIFEST}" 2>&1 | tail -20

echo "[nuvio-linux-flatpak] exporting repo..."
rm -rf "${REPO_DIR}"
flatpak build-export "${REPO_DIR}" "${BUILD_DIR}/app" stable

echo "[nuvio-linux-flatpak] bundling ${BUNDLE}..."
flatpak build-bundle "${REPO_DIR}" "${BUNDLE}" io.github.jjdizz1l.NuvioLinux stable

ls -la "${BUNDLE}"
echo "[nuvio-linux-flatpak] done."
