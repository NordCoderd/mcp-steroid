#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

DRY_RUN="${RELEASE_DRY_RUN:-1}"
RUN_BUILD="1"
RUN_NOTES="1"
RUN_WEBSITE="0"
RUN_PUBLISH="0"
ALLOW_DIRTY="0"
RUN_AGENT_SCRIPT="${RUN_AGENT_SCRIPT:-/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh}"
RELEASE_NOTES_FILE="${RELEASE_NOTES_FILE:-$ROOT_DIR/release/out/release-notes-final.md}"
RELEASE_ZIP_FILE="${RELEASE_ZIP_FILE:-$ROOT_DIR/release/out/plugin-idea-2025.3.zip}"
RELEASE_TAG="${RELEASE_TAG:-}"
VERSION=""

normalize_bool() {
  local name="$1"
  local raw="$2"
  local normalized
  normalized="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$normalized" in
    1|true)
      printf '1\n'
      ;;
    0|false)
      printf '0\n'
      ;;
    *)
      echo "Unsupported $name value: '$raw' (expected true/false or 1/0)" >&2
      exit 2
      ;;
  esac
}

validate_version_file() {
  local version_file="$ROOT_DIR/VERSION"
  if [[ ! -f "$version_file" ]]; then
    echo "Missing VERSION file: $version_file" >&2
    exit 1
  fi
  local version
  version="$(tr -d '[:space:]' < "$version_file")"
  if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Unsupported VERSION format: '$version' (expected X.Y.Z)" >&2
    exit 1
  fi
  VERSION="$version"
}

check_tracked_clean_worktree() {
  if [[ "$ALLOW_DIRTY" == "1" ]]; then
    return 0
  fi

  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Unable to verify worktree cleanliness: not a git repository" >&2
    exit 1
  fi

  if ! git diff --quiet --ignore-submodules -- || ! git diff --cached --quiet --ignore-submodules --; then
    echo "Tracked files are not clean. Commit/stash tracked changes or rerun with --allow-dirty." >&2
    git status --short --untracked-files=no >&2 || true
    exit 1
  fi
}

check_publish_prerequisites() {
  if [[ "$RUN_PUBLISH" != "1" ]]; then
    return 0
  fi
  if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
    echo "Missing release notes file for publish stage: $RELEASE_NOTES_FILE" >&2
    exit 1
  fi
  if [[ ! -f "$RELEASE_ZIP_FILE" ]]; then
    echo "Missing plugin ZIP for publish stage: $RELEASE_ZIP_FILE" >&2
    exit 1
  fi
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI is required for publish stage" >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "gh is not authenticated; run 'gh auth login' before publish stage" >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN="1"
      shift
      ;;
    --no-dry-run)
      DRY_RUN="0"
      shift
      ;;
    --skip-build)
      RUN_BUILD="0"
      shift
      ;;
    --skip-notes)
      RUN_NOTES="0"
      shift
      ;;
    --website)
      RUN_WEBSITE="1"
      shift
      ;;
    --publish)
      RUN_PUBLISH="1"
      shift
      ;;
    --allow-dirty)
      ALLOW_DIRTY="1"
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--dry-run|--no-dry-run] [--skip-build] [--skip-notes] [--website] [--publish] [--allow-dirty]" >&2
      exit 2
      ;;
  esac
done

DRY_RUN="$(normalize_bool "RELEASE_DRY_RUN" "$DRY_RUN")"

if [[ "$DRY_RUN" == "1" ]]; then
  if [[ "$RUN_PUBLISH" == "1" ]]; then
    echo "--publish is disabled in dry-run mode." >&2
  fi
  RUN_PUBLISH="0"
fi

validate_version_file
if [[ -z "$RELEASE_TAG" ]]; then
  RELEASE_TAG="v$VERSION"
fi

echo "Release run configuration:"
echo "  dry_run=$DRY_RUN"
echo "  run_build=$RUN_BUILD"
echo "  run_notes=$RUN_NOTES"
echo "  run_website=$RUN_WEBSITE"
echo "  run_publish=$RUN_PUBLISH"
echo "  allow_dirty=$ALLOW_DIRTY"
echo "  release_tag=$RELEASE_TAG"
echo "  release_notes_file=$RELEASE_NOTES_FILE"
echo "  release_zip_file=$RELEASE_ZIP_FILE"

check_tracked_clean_worktree
check_publish_prerequisites

if [[ "$DRY_RUN" == "1" ]]; then
  RELEASE_DRY_RUN=1 release/scripts/bump-version.sh
else
  RELEASE_DRY_RUN=0 release/scripts/bump-version.sh
fi

if [[ "$RUN_BUILD" == "1" ]]; then
  release/scripts/run-builder.sh bash release/scripts/run-release-build-matrix.sh
fi

if [[ "$RUN_NOTES" == "1" ]]; then
  "$RUN_AGENT_SCRIPT" codex \
    /Users/jonnyzzz/Work/mcp-steroid \
    /Users/jonnyzzz/Work/mcp-steroid/release/prompts/release-notes-collect.md

  "$RUN_AGENT_SCRIPT" codex \
    /Users/jonnyzzz/Work/mcp-steroid \
    /Users/jonnyzzz/Work/mcp-steroid/release/prompts/release-notes-review.md
fi

if [[ "$RUN_WEBSITE" == "1" ]]; then
  "$RUN_AGENT_SCRIPT" codex \
    /Users/jonnyzzz/Work/mcp-steroid \
    /Users/jonnyzzz/Work/mcp-steroid/release/prompts/website-release-page.md
fi

if [[ "$RUN_PUBLISH" == "1" ]]; then
  gh release create "$RELEASE_TAG" "$RELEASE_ZIP_FILE" \
    --repo jonnyzzz/mcp-steroid \
    --notes-file "$RELEASE_NOTES_FILE"
  echo "Publish stage completed."
else
  echo "Publish stage skipped."
fi
