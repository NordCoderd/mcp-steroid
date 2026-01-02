# Testing Strategy

This document summarizes the testing approach. For exact commands and test lists, see `README.md`.

## Principles
- Tests must assert real behavior, not just output formatting.
- Integration tests should verify actual MCP tool calls.
- Avoid test-only branches in production code.

## Test Layers
- Unit tests: core protocol, session management, execution helpers.
- Integration tests: MCP HTTP transport, tool invocation, and session behavior.
- CLI tests: Docker-based Codex/Claude flows to validate real MCP usage.
- OCR tests: run the bundled `ocr-tesseract` helper app against test images.

## Key Coverage
- `ScriptExecutionAvailabilityTest` catches broken script engine quickly.
- CLI tests include multi-step exec flows and MCP list validation.
- Session handling tests cover unknown-session recovery.

## Running Tests
See `README.md` for the recommended test commands and CI notes.
