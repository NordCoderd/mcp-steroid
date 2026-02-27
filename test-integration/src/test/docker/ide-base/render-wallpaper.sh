#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

# Regenerates ide-base/wallpaper.jpg from ide-base/wallpaper.svg
# and ide-base/logo.png from ide-base/logo.svg.
# Source-of-truth is the SVGs, then rendered through rsvg-convert in Docker.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOCKER_IMAGE="${DOCKER_IMAGE:-mcp-steroid-ide-base-test}"

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

    echo 'Rendering wallpaper.svg -> wallpaper.jpg'
    rsvg-convert /work/ide-base/wallpaper.svg \
      --format=png \
      --output /tmp/wallpaper.png
    convert /tmp/wallpaper.png \
      -quality 95 \
      /work/ide-base/wallpaper.jpg

    echo 'Rendering logo.svg -> logo.png'
    rsvg-convert /work/ide-base/logo.svg \
      --format=png \
      --output /work/ide-base/logo.png

    echo 'Done.'
  "
