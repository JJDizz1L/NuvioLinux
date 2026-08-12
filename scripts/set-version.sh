#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESKTOP_VERSION_FILE="$ROOT_DIR/composeApp/Configuration/DesktopVersion.properties"

DESKTOP_VERSION=""
DESKTOP_CODE=""
DRY_RUN=false
SHOW=false

usage() {
  cat <<'EOF'
Usage:
  ./scripts/set-version.sh --desktop 0.1.0 --desktop-code 1
  ./scripts/set-version.sh --show

Options:
  --desktop VERSION       Set the app release version.
  --desktop-code CODE     Set the app build code.
  --dry-run               Print changes without writing files.
  --show                  Print current configured versions.
  -h, --help              Show this help.

The version is stored in composeApp/Configuration/DesktopVersion.properties.
A --desktop change also syncs dist/arch/PKGBUILD (pkgver + pkgrel reset),
dist/rpm/nuvio-linux.spec (%changelog) and the AppStream metainfo <releases>.
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

read_key() {
  local file="$1"
  local key="$2"

  [[ -f "$file" ]] || return 0
  awk -F '=' -v target="$key" '
    {
      raw_key = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", raw_key)
      if (raw_key == target) {
        sub(/^[^=]*=/, "", $0)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
        print $0
        exit
      }
    }
  ' "$file"
}

validate_version() {
  local label="$1"
  local value="$2"

  [[ "$value" =~ ^[0-9]+(\.[0-9]+){1,3}([._-][0-9A-Za-z][0-9A-Za-z._-]*)?$ ]] ||
    die "$label must look like 0.1.0 or 0.1.0-alpha.1"
}

validate_code() {
  local label="$1"
  local value="$2"

  [[ "$value" =~ ^[0-9]+$ ]] || die "$label must be a positive integer"
  (( 10#$value > 0 )) || die "$label must be greater than 0"
}

write_key() {
  local file="$1"
  local key="$2"
  local value="$3"

  mkdir -p "$(dirname "$file")"
  local tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/nuvio-linux-version.XXXXXX")"

  if [[ -f "$file" ]]; then
    awk -v key="$key" -v value="$value" '
      BEGIN { seen = 0 }
      {
        raw_key = $0
        sub(/[[:space:]]*=.*/, "", raw_key)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", raw_key)
        if (raw_key == key) {
          print key "=" value
          seen = 1
          next
        }
        print
      }
      END {
        if (!seen) print key "=" value
      }
    ' "$file" > "$tmp"
  else
    printf '%s=%s\n' "$key" "$value" > "$tmp"
  fi

  mv "$tmp" "$file"
}

print_current_versions() {
  local desktop_version desktop_code
  desktop_version="$(read_key "$DESKTOP_VERSION_FILE" "VERSION_NAME")"
  desktop_code="$(read_key "$DESKTOP_VERSION_FILE" "VERSION_CODE")"

  echo "Desktop: ${desktop_version:-unset} (${desktop_code:-unset})"
}

queue_change() {
  local file="$1"
  local key="$2"
  local value="$3"
  local current
  current="$(read_key "$file" "$key")"

  if [[ "$current" == "$value" ]]; then
    echo "unchanged: $key=$value"
    return 0
  fi

  echo "set: $key ${current:-unset} -> $value"
  if [[ "$DRY_RUN" == false ]]; then
    write_key "$file" "$key" "$value"
  fi
}

sync_arch_pkgbuild() {
  local version="$1"
  local hyphen_free="${version//-/}"
  local pkgbuild="$ROOT_DIR/dist/arch/PKGBUILD"
  local current_pkgver
  current_pkgver="$(read_key "$pkgbuild" "pkgver")"

  if [[ "$current_pkgver" == "$hyphen_free" ]]; then
    echo "unchanged: PKGBUILD pkgver=$hyphen_free"
    return 0
  fi

  queue_change "$pkgbuild" "pkgver" "$hyphen_free"
  # A version bump restarts the package rebuild counter.
  queue_change "$pkgbuild" "pkgrel" "1"
}

sync_spec_changelog() {
  local version="$1"
  local hyphen_free="${version//-/}"
  local spec="$ROOT_DIR/dist/rpm/nuvio-linux.spec"

  if grep -qF "JJDizz1L - ${hyphen_free}-1" "$spec"; then
    echo "unchanged: spec changelog entry ${hyphen_free}-1"
    return 0
  fi

  echo "set: spec changelog entry ${hyphen_free}-1"
  if [[ "$DRY_RUN" == false ]]; then
    local changelog_date
    changelog_date="$(date '+%a %b %d %Y')"
    local tmp
    tmp="$(mktemp "${TMPDIR:-/tmp}/nuvio-linux-spec.XXXXXX")"
    awk -v entry="* ${changelog_date} JJDizz1L - ${hyphen_free}-1" \
        -v summary="- Version bump to ${version}." '
      { print }
      /^%changelog[[:space:]]*$/ { print entry; print summary }
    ' "$spec" > "$tmp"
    mv "$tmp" "$spec"
  fi
}

sync_metainfo_release() {
  local version="$1"
  local xml="$ROOT_DIR/dist/desktop/io.github.jjdizz1l.NuvioLinux.metainfo.xml"

  if grep -qF "version=\"${version}\"" "$xml"; then
    echo "unchanged: metainfo release ${version}"
    return 0
  fi

  echo "set: metainfo release ${version}"
  if [[ "$DRY_RUN" == false ]]; then
    local release_date
    release_date="$(date +%Y-%m-%d)"
    local tmp
    tmp="$(mktemp "${TMPDIR:-/tmp}/nuvio-linux-meta.XXXXXX")"
    awk -v version="$version" -v date="$release_date" '
      { print }
      /^[[:space:]]*<releases>/ { print "    <release version=\"" version "\" date=\"" date "\"/>" }
    ' "$xml" > "$tmp"
    mv "$tmp" "$xml"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --desktop)
      [[ $# -ge 2 ]] || die "--desktop requires a value"
      DESKTOP_VERSION="$2"
      shift 2
      ;;
    --desktop-code)
      [[ $# -ge 2 ]] || die "--desktop-code requires a value"
      DESKTOP_CODE="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --show)
      SHOW=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

if [[ "$SHOW" == true ]]; then
  print_current_versions
  if [[ -z "$DESKTOP_VERSION$DESKTOP_CODE" ]]; then
    exit 0
  fi
  echo
fi

[[ -n "$DESKTOP_VERSION$DESKTOP_CODE" ]] ||
  die "nothing to change; pass --desktop/--desktop-code or --show"

if [[ -n "$DESKTOP_VERSION" ]]; then
  validate_version "desktop version" "$DESKTOP_VERSION"
fi
if [[ -n "$DESKTOP_CODE" ]]; then
  validate_code "desktop code" "$DESKTOP_CODE"
fi

if [[ -n "$DESKTOP_VERSION$DESKTOP_CODE" ]]; then
  echo "Desktop version file: $DESKTOP_VERSION_FILE"
  [[ -z "$DESKTOP_VERSION" ]] || queue_change "$DESKTOP_VERSION_FILE" "VERSION_NAME" "$DESKTOP_VERSION"
  [[ -z "$DESKTOP_CODE" ]] || queue_change "$DESKTOP_VERSION_FILE" "VERSION_CODE" "$DESKTOP_CODE"
fi

if [[ -n "$DESKTOP_VERSION" ]]; then
  echo
  echo "Packaging metadata:"
  sync_arch_pkgbuild "$DESKTOP_VERSION"
  sync_spec_changelog "$DESKTOP_VERSION"
  sync_metainfo_release "$DESKTOP_VERSION"
fi

echo
print_current_versions
