# Agent Output Filter

Kotlin-based executable JAR for filtering AI agent output streams. Replaces Python regex-based filters with robust JSON parsing using kotlinx.serialization.

## Overview

This module provides filters to convert NDJSON/text output from AI agents into human-readable console format:

- **Claude Stream-JSON Filter**: Parses Claude's `stream-json` NDJSON events
- **Codex JSON Filter**: Parses Codex CLI `--json` NDJSON events
- **Gemini Filter**: Strips ANSI escape codes from Gemini CLI text output

## Building

```bash
./gradlew :agent-output-filter:build
```

This produces `agent-output-filter/build/libs/agent-output-filter.jar` (executable fat JAR with all dependencies).

## Usage

```bash
# Claude stream-json filter (default)
cat agent-output.ndjson | java -jar agent-output-filter.jar stream-json
cat agent-output.ndjson | java -jar agent-output-filter.jar claude

# Codex JSON filter
codex exec --json ... | java -jar agent-output-filter.jar codex

# Gemini text filter (ANSI stripping)
gemini chat --screen-reader true ... | java -jar agent-output-filter.jar gemini

# Help
java -jar agent-output-filter.jar --help
```

## Features

### Claude Stream-JSON Filter

Handles Claude stream-json events:
- `content_block_start` (tool_use) → `>> {tool_name} (detail)`
- `content_block_delta` (text_delta) → streams text incrementally
- `tool_result` → `<< {summary}` or `<< ERROR {summary}`
- `message_start` → `[model] {model_name}`
- `message_delta` (non-standard stop) → `[stop] {reason}`
- `result` → `[done] cost=$X time=Ys turns=N`
- `error` → `[ERROR {type}] {message}`
- `system` → `[system] {message}`
- Silently skips: `ping`, `content_block_stop`, `message_stop`

Tool details extracted for: `steroid_execute_code`, `read_mcp_resource`, `Bash`, `Read`, `Write`, `Edit`, `Grep`, `Glob`

### Codex JSON Filter

Handles Codex CLI `--json` events:
- `item.started` (command_execution) → `>> {command}`
- `item.started` (tool_call) → `>> {tool_name} (detail)`
- `item.completed` (agent_message) → agent response text
- `item.completed` (command_execution) → command output + exit code
- `item.completed` (tool_call) → `<< {tool_name} [{exec_id}]: {summary}`
- `turn.completed` → `[turn] in={tokens} out={tokens}`
- `error` → `[ERROR {type}] {message}`
- Silently skips: `thread.started`, `turn.started`, etc.

### Gemini Filter

Processes Gemini CLI text output:
- Strips ANSI escape sequences (CSI, OSC, DEC modes, charset selection)
- Filters blank lines, decorative separators (`---`, `===`), spinner dots
- Deduplicates consecutive identical lines (progress updates)
- Highlights tool activity with `>>` prefix (tool calls, execution IDs, file operations)
- Preserves meaningful text and Unicode content

## Architecture

- **OutputFilter** interface: `process(InputStream, OutputStream)`
- **ClaudeStreamJsonFilter**: kotlinx.serialization.json for JSON parsing
- **CodexJsonFilter**: kotlinx.serialization.json for JSON parsing
- **GeminiFilter**: Regex-based ANSI/noise filtering
- **Main.kt**: Entry point with filter selection

All filters:
- Read NDJSON/text line-by-line from stdin
- Output human-readable progress markers to stdout
- Pass through non-JSON lines for debugging visibility
- Gracefully handle malformed JSON (pass through with warning)
- Preserve output flushing for real-time streaming

## Testing

```bash
./gradlew :agent-output-filter:test
```

58 unit tests covering:
- All JSON event types for each filter
- Tool detail extraction (reason, file_path, command, etc.)
- Error handling (malformed JSON, unknown events)
- Edge cases (long strings, truncation, Unicode, ANSI codes)
- Multi-event sequences

## Integration

Used by Docker integration tests (`test-integration/`) to provide real-time console output during agent execution. The JAR is standalone and can be deployed to any environment with Java 21+.

## Implementation Notes

### No Regex JSON Parsing

All JSON parsing uses kotlinx.serialization.json — no string manipulation or regex extraction. This ensures correctness and handles edge cases (escaping, nesting, etc.).

### Tool Detail Extraction

Filters extract relevant details from tool inputs:
- `steroid_execute_code`: `reason` (truncated to 80 chars)
- `read_mcp_resource`: `uri`
- `Bash`: `command` (truncated to 60 chars)
- `Read/Write/Edit`: `file_path`
- `Grep/Glob`: `pattern`

### Output Format

Progress markers use `>>` prefix for tool calls and `<<` prefix for results, consistent across all filters. This makes it easy to visually track tool execution in console logs.

### Error Handling

Malformed JSON lines are passed through unchanged so they're visible for debugging. This prevents silent data loss while maintaining filter robustness.

## Dependencies

- Kotlin 2.2.21
- kotlinx.serialization-json 1.8.0
- JUnit 5 for testing

## License

Same as parent project (mcp-steroid).
