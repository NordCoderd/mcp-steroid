#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

# Regenerates ide-base/wallpaper.jpg from ide-base/wallpaper.svg.
# Source-of-truth is the SVG, then rendered through ImageMagick in Docker.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOCKER_IMAGE="${DOCKER_IMAGE:-mcp-steroid-ide-base-test}"

INPUT_SVG="/work/ide-base/wallpaper.svg"
OUTPUT_IMAGE="/work/ide-base/wallpaper.jpg"

docker run --rm \
  --user root \
  -v "${DOCKER_ROOT}:/work" \
  "${DOCKER_IMAGE}" \
  bash -lc "
    set -euo pipefail
    if ! command -v rsvg-convert >/dev/null 2>&1; then
      apt-get update >/dev/null
      apt-get install -y --no-install-recommends librsvg2-bin >/dev/null
    fi
    convert \"${INPUT_SVG}\" \
      \"${OUTPUT_IMAGE}\"
  "
