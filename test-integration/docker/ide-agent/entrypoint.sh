#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

echo "[entrypoint] Starting Xvfb..."
Xvfb :99 -screen 0 1920x1080x24 -ac &
XVFB_PID=$!
sleep 1

echo "[entrypoint] Starting fluxbox..."
fluxbox &
sleep 1

# Optional video recording
if [ -n "${VIDEO_OUTPUT:-}" ]; then
    echo "[entrypoint] Starting video recording to $VIDEO_OUTPUT..."
    ffmpeg -f x11grab -video_size 1920x1080 -framerate 10 -i :99 \
        -c:v libx264 -preset ultrafast -crf 28 \
        "$VIDEO_OUTPUT" &
    FFMPEG_PID=$!
fi

echo "[entrypoint] Starting IntelliJ IDEA..."
/opt/idea/bin/idea nosplash "$HOME/project" &
IDE_PID=$!

echo "[entrypoint] Waiting for MCP server to become ready on port 6315..."
MCP_INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'

for i in $(seq 1 120); do
    if curl -s -f -X POST http://localhost:6315/mcp \
        -H "Content-Type: application/json" \
        -d "$MCP_INIT" > /dev/null 2>&1; then
        echo "[entrypoint] MCP server is ready! (attempt $i)"
        touch /tmp/ide-ready
        break
    fi
    sleep 2
done

if [ ! -f /tmp/ide-ready ]; then
    echo "[entrypoint] WARNING: MCP server did not become ready within timeout"
fi

echo "[entrypoint] Waiting for IDE process..."
wait $IDE_PID || true
