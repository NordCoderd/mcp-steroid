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
RUN_AGENT_SCRIPT="${RUN_AGENT_SCRIPT:-/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh}"

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
}

check_publish_prerequisites() {
  if [[ "$RUN_PUBLISH" != "1" ]]; then
    return 0
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
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--dry-run|--no-dry-run] [--skip-build] [--skip-notes] [--website] [--publish]" >&2
      exit 2
      ;;
  esac
done

if [[ "$DRY_RUN" == "1" ]]; then
  RUN_PUBLISH="0"
fi

echo "Release run configuration:"
echo "  dry_run=$DRY_RUN"
echo "  run_build=$RUN_BUILD"
echo "  run_notes=$RUN_NOTES"
echo "  run_website=$RUN_WEBSITE"
echo "  run_publish=$RUN_PUBLISH"

validate_version_file
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
  echo "Publish stage is not yet wired to concrete artifact/tag parameters."
  echo "Run gh release create manually with validated notes and stable ZIP."
else
  echo "Publish stage skipped."
fi
