#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

# Minimal entrypoint — keeps the container alive.
# All startup logic (Xvfb, fluxbox, ffmpeg, IDEA) is orchestrated
# by IdeContainerSession via docker exec for better control.

echo "[entrypoint] Container started, waiting for orchestration..."
sleep infinity
