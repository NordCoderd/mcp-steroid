#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

# Regenerates ide-base/wallpaper.jpg from ide-agent/wallpaper.jpg.
# Keeps the "MCP Steroid" title untouched, redraws other text in JetBrains Mono,
# and keeps lines centered relative to screen center.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOCKER_IMAGE="${DOCKER_IMAGE:-mcp-steroid-ide-base-test}"

INPUT_IMAGE="/work/ide-agent/wallpaper.jpg"
OUTPUT_IMAGE="/work/ide-base/wallpaper.jpg"
TAGLINE_TEXT="Give AI the whole IDE, not just the files"
TEXT="follow @jonnyzzz and https://linkedin.com/in/jonnyzzz"

docker run --rm \
  -v "${DOCKER_ROOT}:/work" \
  "${DOCKER_IMAGE}" \
  bash -lc "
    set -euo pipefail
    convert -list font | grep -q 'Font: JetBrains-Mono-Regular'
    convert \"${INPUT_IMAGE}\" \
      \( +clone -crop 2200x90+820+2020 \) -geometry +820+1820 -composite \
      \( +clone -crop 2200x90+820+2020 \) -geometry +820+1900 -composite \
      \( -background none -fill '#666666' -font JetBrains-Mono-Regular -pointsize 40 label:\"${TAGLINE_TEXT}\" \) \
        -gravity North -geometry +0+1830 -composite \
      \( -background none -fill '#888888' -font JetBrains-Mono-Regular -pointsize 32 label:\"${TEXT}\" \) \
        -gravity North -geometry +0+2070 -composite \
      \"${OUTPUT_IMAGE}\"
  "
