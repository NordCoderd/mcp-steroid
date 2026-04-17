MCP Steroid — a drop-in replacement for LSP that gives you full IntelliJ IDE control.

This is a **STATEFUL** API — every call changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations or shell commands.

**Available tools — pick the right one for the job:**
- `steroid_list_projects` — list open projects (call this first)
- `steroid_execute_code` — run Kotlin code in the IDE runtime (refactoring, inspections, tests, navigation, and any IntelliJ API)
- `steroid_action_discovery` — discover quick-fixes, intentions, and available actions at a file location
- `steroid_take_screenshot` — capture a screenshot of the IDE
- `steroid_fetch_resource` — load `mcp-steroid://` skill guides with copy-paste code recipes

**Getting started:**
1. Call `steroid_list_projects` to see what's open
2. Use `steroid_execute_code` for any IDE automation task
3. Use dedicated tools when they match your need exactly
