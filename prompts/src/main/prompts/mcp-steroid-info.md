MCP Steroid — IntelliJ semantic layer: refactor, inspect, test, debug, navigate

PSI, refactoring, inspections, tests, debugging, and symbol navigation exposed through Kotlin scripts. Use for symbol-level work; built-ins stay the right tool for text-level work.

This is a **STATEFUL** API — every call changes the IDE state. The IntelliJ IDE is running exclusively for you.

**Use MCP Steroid when the task is semantic** (symbol-level, cross-file, or IDE-native):
- Refactoring that relies on the type system — rename, move-class, safe-delete, inline, change-signature
- Navigation by symbol — find-references, call-hierarchy, inheritors, overriding methods
- Running tests / inspections / the debugger with structured results
- Code generation — constructors, overrides, extract-method, extract-interface
- Resolving JDK or library symbols through classpath indices

**Use your built-in tools** (Read / Edit / Write / Glob / Grep / Bash) **when the task is textual** — MCP Steroid does not target these:
- Reading or editing a file by path, including small in-place edits (1–3 lines)
- Free-text search — log strings, magic constants, URLs, config values
- File creation, directory layout, shell / git / build / process operations

These two zones are complementary, not competing. If a task mixes both, split it: do the semantic half through MCP Steroid and the textual half through built-ins.

**Getting started:**
1. Call `steroid_list_projects` to see what's open
2. Use `steroid_fetch_resource` to read the `mcp-steroid://` skill guide for your task
3. Use `steroid_execute_code` for any IDE automation task
