# Design Plans

This is a short index of current design plans. Detailed decisions live in `Plan.md`, `Suggestions.md`, and `Discussions.md`.

## Near-Term Focus
- Keep MCP transport stable and compatible with CLI clients.
- Continue improving session recovery signaling and client guidance.
- Expand language-agnostic action discovery to support more IDE actions.
- Maintain vision tooling reliability (screenshots + input).
- Harden OCR pipeline via the external helper app.

## Planned Enhancements
- Structured error envelope (planned; see `Suggestions.md` and `Plan.md`).
- Expanded action discovery for multiple language contexts.
- Richer screenshot metadata (component trees, additional targets).
- Additional OCR annotations and improved text region extraction.

## Testing & Validation
- CLI integration tests must exercise real tool calls.
- Execution availability must fail fast when scripts cannot run.
- Vision + OCR tests are incremental; see `TODO.md` for headless constraints.

## References
- `Plan.md`: phased roadmap
- `Suggestions.md`: open questions and design decisions
- `Discussions.md`: Q&A logs and rationale
- `TODO.md`: tactical items and reminders
