#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

STATE_DIR="$ROOT_DIR/release/state"
STATE_FILE="$STATE_DIR/version-bump.env"
VERSION_FILE="$ROOT_DIR/VERSION"
DRY_RUN="${RELEASE_DRY_RUN:-0}"
DRY_RUN_NORMALIZED="$(printf '%s' "$DRY_RUN" | tr '[:upper:]' '[:lower:]')"
IS_DRY_RUN="0"

mkdir -p "$STATE_DIR"

if [[ -f "$STATE_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STATE_FILE"
  echo "Version bump already recorded:"
  echo "  old_version=${OLD_VERSION:-unknown}"
  echo "  new_version=${NEW_VERSION:-unknown}"
  echo "  commit=${COMMIT_SHA:-unknown}"
  exit 0
fi

if ! [[ -f "$VERSION_FILE" ]]; then
  echo "Missing VERSION file: $VERSION_FILE" >&2
  exit 1
fi

current_version="$(tr -d '[:space:]' < "$VERSION_FILE")"
if ! [[ "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Unsupported VERSION format: '$current_version' (expected X.Y.Z)" >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
next_minor=$((minor + 1))
next_version="${major}.${next_minor}.0"

case "$DRY_RUN_NORMALIZED" in
  1|true)
    IS_DRY_RUN="1"
    ;;
  0|false)
    IS_DRY_RUN="0"
    ;;
  *)
    echo "Unsupported RELEASE_DRY_RUN value: '$DRY_RUN' (expected true/false or 1/0)" >&2
    exit 2
    ;;
esac

if [[ "$IS_DRY_RUN" == "1" ]]; then
  echo "[DRY-RUN] Version bump skipped"
  echo "  current_version=$current_version"
  echo "  would_bump_to=$next_version"
  exit 0
fi

echo "$next_version" > "$VERSION_FILE"

git add VERSION
git commit -m "release: bump version to $next_version"

commit_sha="$(git rev-parse --short HEAD)"
{
  echo "OLD_VERSION=$current_version"
  echo "NEW_VERSION=$next_version"
  echo "COMMIT_SHA=$commit_sha"
  echo "TIMESTAMP_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$STATE_FILE"

echo "Version bumped: $current_version -> $next_version"
echo "Commit: $commit_sha"
echo "State: $STATE_FILE"
