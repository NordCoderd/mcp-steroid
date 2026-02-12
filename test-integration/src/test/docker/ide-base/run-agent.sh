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

# Deploy inline Codex JSON filter for human-readable console output
CODEX_FILTER="$RUN_DIR/codex-json-filter.py"
cat > "$CODEX_FILTER" <<'PYEOF'
#!/usr/bin/env python3
import sys, json
def tool_detail(name, inp):
    if name == 'steroid_execute_code':
        reason = inp.get('reason', '')
        if reason: return ' (' + reason + ')'
    elif name == 'read_mcp_resource':
        uri = inp.get('uri', '')
        if uri: return ' (' + uri + ')'
    return ''
def truncate(text, max_len=200):
    return text[:max_len] + '...' if len(text) > max_len else text
for line in sys.stdin:
    line = line.rstrip('\n\r')
    if not line: continue
    if not line.lstrip().startswith('{'):
        print(line, flush=True); continue
    try: obj = json.loads(line)
    except (json.JSONDecodeError, ValueError):
        print(line, flush=True); continue
    try:
        t = obj.get('type', '')
        item = obj.get('item', {})
        item_type = item.get('type', '') if isinstance(item, dict) else ''
        if t == 'item.completed' and item_type == 'agent_message':
            text = item.get('text', '')
            if text:
                for p in text.split('\n'):
                    p = p.rstrip()
                    if p: print(p, flush=True)
        elif t == 'item.completed' and item_type == 'command_execution':
            output = item.get('output', '')
            if output:
                for p in output.split('\n'):
                    p = p.rstrip()
                    if p: print('  ' + truncate(p), flush=True)
            ec = item.get('exit_code')
            if ec is not None and ec != 0:
                print('>> exit ' + str(ec), flush=True)
        elif t == 'item.completed' and item_type in ('tool_call', 'function_call', 'mcp_tool_call'):
            name = item.get('name', item.get('function', {}).get('name', '?'))
            output = item.get('output', item.get('result', ''))
            label = '<< ' + name
            eid = item.get('id', '')
            if eid: label += ' [' + eid + ']'
            if output: label += ': ' + truncate(str(output).replace('\n', ' '), 120)
            print(label, flush=True)
        elif t == 'item.started' and item_type == 'command_execution':
            cmd = item.get('command', '')
            if cmd: print('>> ' + cmd, flush=True)
        elif t == 'item.started' and item_type in ('tool_call', 'function_call', 'mcp_tool_call'):
            name = item.get('name', item.get('function', {}).get('name', '?'))
            inp = item.get('input', item.get('arguments', {}))
            if isinstance(inp, str):
                try: inp = json.loads(inp)
                except Exception: inp = {}
            detail = tool_detail(name, inp) if isinstance(inp, dict) else ''
            print('>> ' + name + detail, flush=True)
        elif t == 'turn.completed':
            usage = obj.get('usage', {})
            it = usage.get('input_tokens', 0); ot = usage.get('output_tokens', 0)
            if it or ot: print('[turn] in=' + str(it) + ' out=' + str(ot), flush=True)
        elif t == 'error':
            error = obj.get('error', obj.get('message', ''))
            if isinstance(error, dict):
                msg = error.get('message', str(error))
                et = error.get('type', error.get('code', ''))
                if et: print('[ERROR ' + str(et) + '] ' + msg, flush=True)
                else: print('[ERROR] ' + msg, flush=True)
            else: print('[ERROR] ' + str(error), flush=True)
    except (KeyError, TypeError, AttributeError): pass
PYEOF

# Deploy inline Claude stream-json filter for human-readable console output
CLAUDE_FILTER="$RUN_DIR/stream-json-filter.py"
cat > "$CLAUDE_FILTER" <<'PYEOF'
#!/usr/bin/env python3
import sys, json
def tool_detail(name, inp):
    if name == 'steroid_execute_code':
        reason = inp.get('reason', '')
        if reason:
            if len(reason) > 80: reason = reason[:77] + '...'
            return ' (' + reason + ')'
    elif name == 'read_mcp_resource':
        uri = inp.get('uri', '')
        if uri: return ' (' + uri + ')'
    elif name in ('Bash', 'bash'):
        cmd = inp.get('command', '')
        if cmd:
            if len(cmd) > 60: cmd = cmd[:57] + '...'
            return ' (' + cmd + ')'
    elif name in ('Read', 'read'):
        path = inp.get('file_path', '')
        if path: return ' (' + path + ')'
    elif name in ('Edit', 'edit', 'Write', 'write'):
        path = inp.get('file_path', '')
        if path: return ' (' + path + ')'
    elif name in ('Grep', 'grep'):
        pat = inp.get('pattern', '')
        if pat: return ' (' + pat + ')'
    elif name in ('Glob', 'glob'):
        pat = inp.get('pattern', '')
        if pat: return ' (' + pat + ')'
    return ''
def tool_result_summary(obj):
    content = obj.get('content', '')
    if isinstance(content, str):
        for line in content.split('\n'):
            line = line.strip()
            if line:
                return line[:100] + '...' if len(line) > 100 else line
    elif isinstance(content, list):
        for block in content:
            if isinstance(block, dict) and block.get('type') == 'text':
                for line in block.get('text', '').split('\n'):
                    line = line.strip()
                    if line:
                        return line[:100] + '...' if len(line) > 100 else line
    return ''
for line in sys.stdin:
    line = line.rstrip('\n\r')
    if not line: continue
    if not line.lstrip().startswith('{'):
        print(line, flush=True); continue
    try: obj = json.loads(line)
    except (json.JSONDecodeError, ValueError):
        print(line, flush=True); continue
    try:
        t = obj.get('type', '')
        if t == 'content_block_delta':
            delta = obj.get('delta', {})
            dt = delta.get('type', '')
            if dt == 'text_delta':
                text = delta.get('text', '')
                if text: sys.stdout.write(text); sys.stdout.flush()
        elif t == 'content_block_start':
            cb = obj.get('content_block', {})
            if cb.get('type') == 'tool_use':
                name = cb.get('name', '?')
                inp = cb.get('input', {})
                detail = tool_detail(name, inp)
                print('\n>> ' + name + detail, flush=True)
        elif t == 'tool_use':
            name = obj.get('name', '?')
            inp = obj.get('input', {})
            detail = tool_detail(name, inp)
            print('>> ' + name + detail, flush=True)
        elif t == 'tool_result':
            is_error = obj.get('is_error', False)
            summary = tool_result_summary(obj)
            prefix = '<< ERROR' if is_error else '<<'
            parts = [prefix]
            if summary: parts.append(summary)
            print(' '.join(parts), flush=True)
        elif t == 'message_start':
            msg = obj.get('message', {})
            model = msg.get('model', '')
            if model: print('[model] ' + model, flush=True)
        elif t == 'message_delta':
            delta = obj.get('delta', {})
            sr = delta.get('stop_reason', '')
            if sr and sr != 'end_turn': print('[stop] ' + sr, flush=True)
        elif t == 'result':
            cost = obj.get('cost_usd', 0)
            duration = obj.get('duration_ms', 0)
            dur_s = duration / 1000.0 if duration else 0
            total_cost = obj.get('total_cost_usd', 0)
            turns = obj.get('num_turns', 0)
            parts = []
            if cost: parts.append('cost=$' + format(cost, '.4f'))
            if total_cost and total_cost != cost: parts.append('total=$' + format(total_cost, '.4f'))
            if dur_s: parts.append('time=' + format(dur_s, '.1f') + 's')
            if turns: parts.append('turns=' + str(turns))
            if parts: print('[done] ' + ' '.join(parts), flush=True)
            else: print('[done]', flush=True)
        elif t == 'error':
            error = obj.get('error', {})
            if isinstance(error, dict):
                msg = error.get('message', str(error))
                et = error.get('type', '')
                if et: print('[ERROR ' + et + '] ' + msg, flush=True)
                else: print('[ERROR] ' + msg, flush=True)
            else: print('[ERROR] ' + str(error), flush=True)
        elif t == 'system':
            msg_text = obj.get('message', '')
            if msg_text: print('[system] ' + str(msg_text), flush=True)
    except (KeyError, TypeError, AttributeError): pass
PYEOF

case "$AGENT" in
  codex)
    CMDLINE="codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --json -C \"$CWD\" - < \"$RUN_DIR/prompt.md\""
    (
      cd "$CWD"
      codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --json -C "$CWD" - <"$RUN_DIR/prompt.md" \
        > >(tee -a "$STDOUT_FILE" | python3 "$CODEX_FILTER" | tee -a "$STREAM_FILE") \
        2> >(tee -a "$STDERR_FILE" "$STREAM_FILE" >&2)
    ) &
    ;;
  claude)
    CMDLINE="claude -p --input-format text --output-format stream-json --verbose --tools default --permission-mode bypassPermissions < \"$RUN_DIR/prompt.md\""
    (
      cd "$CWD"
      # Raw NDJSON goes to agent-stdout.txt for post-processing (extractStreamJsonResult).
      # Filtered human-readable text goes to agent-stream.txt and console via the Python filter.
      claude -p --input-format text --output-format stream-json --verbose --tools default --permission-mode bypassPermissions <"$RUN_DIR/prompt.md" \
        > >(tee -a "$STDOUT_FILE" | python3 "$CLAUDE_FILTER" | tee -a "$STREAM_FILE") \
        2> >(tee -a "$STDERR_FILE" "$STREAM_FILE" >&2)
    ) &
    ;;
  gemini)
    CMDLINE="gemini --screen-reader true --sandbox-mode none --approval-mode yolo < \"$RUN_DIR/prompt.md\""
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
      gemini --screen-reader true --sandbox-mode none --approval-mode yolo <"$RUN_DIR/prompt.md" \
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
