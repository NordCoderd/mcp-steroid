#!/bin/sh
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
CONTAINERS="/tmp/containers.txt"
SELF_ID=$(hostname)
touch "$CONTAINERS"
echo "Reaper started. Self ID: $SELF_ID"

do_cleanup() {
    echo "Starting cleanup..."
    sort -u "$CONTAINERS" | while IFS= read -r cid; do
        [ -z "$cid" ] && continue
        [ "$cid" = "$SELF_ID" ] && continue
        echo "Killing: $cid"
        docker kill "$cid" 2>/dev/null || true
        docker rm -f "$cid" 2>/dev/null || true
    done
    echo "Cleanup complete."
}

trap 'do_cleanup; exit 0' TERM INT

echo "Listening on :8080..."
socat -T 3 -u TCP-LISTEN:8080,reuseaddr - | while IFS= read -r line; do
    line=$(printf '%s' "$line" | tr -d '\r')
    case "$line" in
        container=*)
            cid="${line#container=}"
            echo "$cid" >> "$CONTAINERS"
            echo "Registered: $cid"
            ;;
        ping)
            ;;
        *)
            echo "Unknown: $line"
            ;;
    esac
done

echo "Connection closed or timeout."
do_cleanup
echo "Exiting."
