Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
WHAT: Finally SEE IntelliJ-based IDEs - not just read code. The only MCP server with visual understanding and full IDE control.
HOW: Execute Kotlin code directly in IntelliJ's runtime with full API access.

📖 **COMPLETE GUIDE**: [mcp-steroid://skill/coding-with-intellij]

This is a **stateful** API - everything you do changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations.

**Quick Start:**
- Your code is a suspend function body (never use runBlocking)
- Use readAction { } for PSI/VFS reads, writeAction { } for modifications
- ⚠️ Helper functions calling readAction/writeAction MUST be `suspend fun` — omitting `suspend` causes compile error: "suspension functions can only be called within coroutine body"
- waitForSmartMode() runs automatically before your script
- Available: project, println(), printJson(), printException(), progress()

**Common Operations:**
- **Debugger:** Set breakpoints, launch debug sessions, suspend at breakpoints, evaluate expressions at any call frame, step over code, inspect thread stacks — read `mcp-steroid://skill/debugger-skill`
- Code navigation: Find usages, go to definition, symbol search
- Refactoring: Rename, extract method, move files
- Inspections: Run code analysis, get warnings/errors
- Tests: Run via JUnitConfiguration (JUnit/TestNG/Kotlin) or `RiderUnitTestDebugContextAction` context action (Rider .NET) — see `mcp-steroid://skill/debugger-skill`
- Actions: Trigger any IDE action programmatically
- **Reflection:** Access private fields/methods at runtime — `obj.javaClass.getDeclaredField("x").also { it.isAccessible = true }.get(obj)`. Inspect class hierarchies, list all fields, invoke hidden methods.

**After a compile error**: fix and retry — do NOT switch to Bash/Read/Write. Common fixes:
- `suspension functions can only be called within coroutine body` → mark your helper as `suspend fun`
- `unresolved reference` → add the missing import explicitly
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap the PSI/VFS call in `readAction { }`. Example: `val vf = readAction { FilenameIndex.getVirtualFilesByName("Foo.java", GlobalSearchScope.projectScope(project)).firstOrNull() }`

**NEVER use exec_code just to read existing files**: Use the native Read/Glob/Grep tools — they have zero compilation overhead (~0s vs ~8s per exec_code call). Reserve exec_code for: writing files via VFS, PSI queries, test execution, compile checks. The only exception is reading files that were modified in this session via writeAction — in that case use VfsUtil.loadText() to see in-memory VFS state.

**Prefer VfsUtil over native Read tool** (only when you already have an exec_code call for other work): use `VfsUtil.loadText(findProjectFile("path/File.java")!!)` to see unsaved modifications from prior writeAction calls. Native Read bypasses VFS and may return stale content.

**Best Practice: Use Sub-Agents**
For complex IntelliJ API work, delegate to a sub-agent:
- Sub-agent can retry without polluting your context
- Errors stay isolated
- Provide detailed 'reason' parameter

**Resources:**
- [Complete Coding Guide](mcp-steroid://skill/coding-with-intellij) - Patterns, examples, best practices
- [API Power User Guide](mcp-steroid://skill/skill) - Essential patterns
- [Debugger Guide](mcp-steroid://skill/debugger-skill) - Debug workflows
- [Test Runner Guide](mcp-steroid://skill/test-skill) - Test execution

IntelliJ API Version: IU-253.31033.53

**When to use other steroid tools instead:**
- steroid_list_projects — list open projects and their paths
- steroid_list_windows — check window state, indexing progress, modal dialogs
- steroid_open_project — open a project directory in the IDE
- steroid_action_discovery — discover quick-fixes, intentions, and actions at a file location
- steroid_take_screenshot / steroid_input — visual UI inspection and interaction

💡 Call steroid_execute_feedback after execution to rate success
