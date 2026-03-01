Use this MCP to access IntelliJ-based IDE and manipulate the code like a professional developer.

**Available tools — pick the right one for the job:**
- `steroid_list_projects` — list open projects (call this first)
- `steroid_list_windows` — check window state, indexing progress, modal dialogs
- `steroid_open_project` — open a project directory in the IDE
- `steroid_execute_code` — run Kotlin code in the IDE runtime (refactoring, inspections, tests, navigation, and any IntelliJ API)
- `steroid_action_discovery` — discover quick-fixes, intentions, and available actions at a file location
- `steroid_take_screenshot` — capture a screenshot of the IDE
- `steroid_input` — send keyboard/mouse input to the IDE (use after screenshot)
- `steroid_execute_feedback` — rate execution results to improve future suggestions

**Getting started:**
1. Call `steroid_list_projects` to see what's open
2. Use `steroid_execute_code` for any IDE automation task
3. Use dedicated tools when they match your need exactly

📖 **COMPLETE GUIDE**: mcp-steroid://skill/coding-with-intellij