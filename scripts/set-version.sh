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

echo
print_current_versions
