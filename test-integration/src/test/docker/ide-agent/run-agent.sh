#!/bin/bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
# Unified agent runner inside ide-agent Docker image.
# Usage: run-agent.sh [agent] [cwd] [prompt_file]
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNS_DIR="${RUNS_DIR:-/tmp/agent-runs}"
MESSAGE_BUS="${MESSAGE_BUS:-$BASE_DIR/MESSAGE-BUS.md}"
export RUNS_DIR
export MESSAGE_BUS

AGENT="${1:-codex}"
CWD="${2:-$PWD}"
PROMPT_FILE="${3:-$BASE_DIR/prompt.md}"

PROMPT_FILE="$(cd "$(dirname "$PROMPT_FILE")" && pwd)/$(basename "$PROMPT_FILE")"
if [ ! -f "$PROMPT_FILE" ]; then
  echo "Prompt file not found: $PROMPT_FILE" >&2
  exit 1
fi

RUN_ID="run_$(date -u +%Y%m%d-%H%M%S)-$$"
RUN_DIR="$RUNS_DIR/$RUN_ID"
mkdir -p "$RUN_DIR"
cp "$BASE_DIR/run-agent.sh" "$RUN_DIR/run-agent.sh" || true

echo "RUN_ID=$RUN_ID"
echo "RUN_DIR=$RUN_DIR"

STDOUT_FILE="$RUN_DIR/agent-stdout.txt"
STDERR_FILE="$RUN_DIR/agent-stderr.txt"
STREAM_FILE="$RUN_DIR/agent-stream.txt"
PID_FILE="$RUN_DIR/pid.txt"
CWD_FILE="$RUN_DIR/cwd.txt"

cp "$PROMPT_FILE" "$RUN_DIR/prompt.md"
: >"$STDOUT_FILE"
: >"$STDERR_FILE"
: >"$STREAM_FILE"

CMDLINE=""
case "$AGENT" in
  codex)
    CMDLINE="codex exec --dangerously-bypass-approvals-and-sandbox -C \"$CWD\" - < \"$RUN_DIR/prompt.md\""
    (
      cd "$CWD"
      codex exec --dangerously-bypass-approvals-and-sandbox -C "$CWD" - <"$RUN_DIR/prompt.md" \
        > >(tee -a "$STDOUT_FILE" "$STREAM_FILE") \
        2> >(tee -a "$STDERR_FILE" "$STREAM_FILE" >&2)
    ) &
    ;;
  claude)
    CMDLINE="claude -p --input-format text --output-format text --tools default --permission-mode bypassPermissions < \"$RUN_DIR/prompt.md\""
    (
      cd "$CWD"
      claude -p --input-format text --output-format text --tools default --permission-mode bypassPermissions <"$RUN_DIR/prompt.md" \
        > >(tee -a "$STDOUT_FILE" "$STREAM_FILE") \
        2> >(tee -a "$STDERR_FILE" "$STREAM_FILE" >&2)
    ) &
    ;;
  gemini)
    CMDLINE="gemini --approval-mode auto_edit < \"$RUN_DIR/prompt.md\""
    if [ -z "${GEMINI_API_KEY:-}" ] && [ -f "$HOME/.vertes" ]; then
      export GEMINI_API_KEY="$(cat "$HOME/.vertes")"
    fi
    if [ -z "${GEMINI_API_KEY:-}" ] && [ -f "$HOME/.vertex" ]; then
      export GEMINI_API_KEY="$(cat "$HOME/.vertex")"
    fi
    if [ -n "${GEMINI_API_KEY:-}" ] && [ -z "${GOOGLE_API_KEY:-}" ]; then
      export GOOGLE_API_KEY="$GEMINI_API_KEY"
    fi
    (
      cd "$CWD"
      gemini --approval-mode auto_edit <"$RUN_DIR/prompt.md" \
        > >(tee -a "$STDOUT_FILE" "$STREAM_FILE") \
        2> >(tee -a "$STDERR_FILE" "$STREAM_FILE" >&2)
    ) &
    ;;
  *)
    echo "Unknown agent: $AGENT" >&2
    exit 2
    ;;
esac

AGENT_PID=$!
echo "$AGENT_PID" > "$PID_FILE"
echo "PID=$AGENT_PID"

cat > "$CWD_FILE" <<EOF
RUN_ID=$RUN_ID
CWD=$CWD
AGENT=$AGENT
CMD=$CMDLINE
PROMPT=$RUN_DIR/prompt.md
STDOUT=$STDOUT_FILE
STDERR=$STDERR_FILE
STREAM=$STREAM_FILE
PID=$AGENT_PID
EOF

wait "$AGENT_PID"
EXIT_CODE=$?
rm -f "$PID_FILE"
echo "EXIT_CODE=$EXIT_CODE" >> "$CWD_FILE"
exit "$EXIT_CODE"
