#!/usr/bin/env bash
#
# Builds a portable (GOAMD64=v1, baseline x86-64) TorrServer binary and
# installs it as the bundled app resource at
#   composeApp/src/desktopMain/resources/torrserver/linux-amd64/TorrServer
#
# The official YouROK/TorrServer release binary is built with GOAMD64=v3
# (AVX-512) and refuses to start on older x86-64 CPUs, so we build from
# source with the baseline microarchitecture level instead.
#
# Requirements: go (>= 1.25), node, yarn (for the embedded web UI).
# Pinned upstream: YouROK/TorrServer tag MatriX.142.2
#
# Usage: ./dist/fetch-torrserver.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="MatriX.142.2"
SRC_URL="https://github.com/YouROK/TorrServer/archive/refs/tags/${TAG}.tar.gz"
# Pinned upstream source: sha256 of the ${TAG} tag tarball.
SRC_SHA256="83ad3df139c7ceedaabb4997bfa2625d5c4b5919171812e9b259be9116d2b155"
OUTPUT="${ROOT_DIR}/composeApp/src/desktopMain/resources/torrserver/linux-amd64/TorrServer"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-torrserver.XXXXXX")"
trap 'rm -rf "${TMP}"' EXIT

command -v go >/dev/null || { echo "error: go is required (>= 1.25)" >&2; exit 1; }
command -v yarn >/dev/null || { echo "error: yarn is required for the embedded web UI" >&2; exit 1; }
command -v node >/dev/null || { echo "error: node is required for the embedded web UI" >&2; exit 1; }

echo "[nuvio-torrserver] downloading source ${TAG}..."
curl -fsSL -o "${TMP}/src.tar.gz" "${SRC_URL}"
echo "${SRC_SHA256}  ${TMP}/src.tar.gz" | sha256sum -c - >/dev/null
tar xzf "${TMP}/src.tar.gz" -C "${TMP}"
SRC="${TMP}/TorrServer-${TAG}"

echo "[nuvio-torrserver] building embedded web UI..."
cd "${SRC}"
export NODE_OPTIONS=--openssl-legacy-provider
go run gen_web.go --clean

echo "[nuvio-torrserver] building server (GOAMD64=v1, baseline x86-64)..."
cd "${SRC}/server"
export GOAMD64=v1 CGO_ENABLED=0
go build \
  -ldflags="-s -w -checklinkname=0" \
  -tags=nosqlite \
  -trimpath \
  -o "${TMP}/TorrServer" \
  ./cmd

install -Dm755 "${TMP}/TorrServer" "${OUTPUT}"
echo "[nuvio-torrserver] installed ${OUTPUT}"
sha256sum "${OUTPUT}"
