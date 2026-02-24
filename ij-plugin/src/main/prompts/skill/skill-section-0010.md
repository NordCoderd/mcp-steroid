---
name: mcp-steroid
description: Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to IntelliJ Platform APIs. Use when you need to search code, run refactorings, access PSI (parsed code model), query project structure, run inspections, or perform any IDE operation. Prefer this over file-based operations - the IDE has indexed everything.
license: Apache-2.0
compatibility: Requires IntelliJ IDEA 2025.3+ with the MCP Steroid plugin installed
metadata:
  author: jonnyzzz
  category: development
---

# MCP Steroid - IDE API Access for AI Agents

Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to the IntelliJ Platform API.

## Important Notes for AI Agents

**Learning Curve**: Writing working code for IntelliJ APIs may require several attempts. This is normal! The API is vast and powerful. Keep trying - each attempt teaches you more about the available APIs. Use `printException()` to see stack traces when errors occur.

**Persistence Pays Off**: If your first attempt fails, analyze the error, adjust your approach, and try again. The power you gain from direct IDE access is worth the effort.

**Comparison to LSP**: This MCP server provides functionality similar to LSP (Language Server Protocol) tools, but uses IntelliJ's native APIs instead. IntelliJ APIs are often more powerful and feature-rich than standard LSP, offering:
- Deeper code understanding via PSI (Program Structure Interface)
- Access to IDE-specific features (inspections, refactorings, intentions)
- Full project model with module dependencies
- Platform-specific indices for fast code search

## Quickstart Flow

```
1. steroid_list_projects → get list of open projects
2. Pick a project_name from the list
3. steroid_capabilities → list installed plugins and languages (optional)
4. steroid_execute_code → run Kotlin code with that project
5. steroid_execute_feedback → report success/failure for tracking
```

**Example session:**
```
→ steroid_list_projects
← {"ide":{"name":"IntelliJ IDEA","version":"2025.3.2","build":"IU-253.30387.160"},"projects":[{"name":"my-app","path":"/path/to/my-app"}]}

→ steroid_execute_code(project_name="my-app", code="println(project.name)", ...)
← "my-app"

→ steroid_execute_feedback(project_name="my-app", task_id="...", execution_id="...", success_rating=1.0, explanation="Got project name")
```

## When to Use This Skill

**ALWAYS prefer IntelliJ APIs over file-based operations:**

| Instead of...                   | Use IntelliJ API               |
|---------------------------------|--------------------------------|
| Reading files with `cat`/`read` | VFS and PSI APIs               |
| Searching with `grep`/`find`    | Find Usages, Structural Search |
| Manual text replacement         | Automated refactorings         |
| Guessing code structure         | Query project model directly   |

The IDE has indexed everything. It knows the code better than any file search.

## Available Tools

### `steroid_list_projects`
List all open projects. Returns IDE metadata and project names for use with `steroid_execute_code`.

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the correct `project_name` and `window_id` for screenshot/input tools.

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the correct `project_name` and `window_id` for screenshot/input tools.

### `steroid_capabilities`
List IDE capabilities such as installed plugins and registered languages.

**Parameters:**
- `include_disabled_plugins` (optional): Include disabled plugins in the response (default: false)

### `steroid_action_discovery`
Discover available editor actions, quick-fixes, and gutter actions for a file and caret context.

**Parameters:**
- `project_name` (required): Target project name
- `file_path` (required): Absolute or project-relative path to the file
- `caret_offset` (optional): Caret offset within the file (default: 0)
- `action_groups` (optional): Action group IDs to expand (default: editor popup + gutter)
- `max_actions_per_group` (optional): Cap actions returned per group (default: 200)

### `steroid_take_screenshot`
Capture a screenshot of the IDE frame and return image content.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): Target project name
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the screenshot is needed
- `window_id` (optional): Window id from `steroid_list_windows` to target a specific window

**Artifacts (saved under the execution folder):**
- `screenshot.png`
- `screenshot-tree.md`
- `screenshot-meta.json`

Use the returned `execution_id` as `screenshot_execution_id` for `steroid_input`. The response includes `window_id` (also stored in `screenshot-meta.json`).

### `steroid_input`
Send input events (keyboard + mouse) using a sequence string.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): Target project name
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the input is needed
- `screenshot_execution_id` (required): Execution ID from `steroid_take_screenshot` or `takeIdeScreenshot()`
- `sequence` (required): Comma-separated or newline-separated input sequence (commas inside values are allowed unless they look like `, <step>:`; commas are optional when using newlines)

**Sequence examples:**
- `stick:ALT, delay:400, press:F4, type:hurra`
- `click:CTRL+Left@120,200`
- `click:Right@screen:400,300`

**Notes:**
- Comma separators are detected by `, <step>:` patterns, so avoid typing `, delay:` etc in text.
- Trailing commas before a newline are ignored.
- Use `#` for comments until the end of the line.
- Targets default to screenshot coordinates; use `screen:` for absolute screen pixels.
- Input focuses the screenshot window before dispatching events.
- Input focuses the screenshot window before dispatching events.

### `steroid_execute_code`
**Execute code with IntelliJ's brain, not just text files.**

Give your AI agent a senior developer's toolkit: semantic code understanding, automated refactorings, and IDE intelligence that LSP can't provide.

**Why use this over file operations:**
- **See relationships**, not just text: Find all usages instantly, traverse class hierarchies, query the semantic model
- **Refactor correctly**: Rename functions project-wide, extract methods that maintain types, move classes that update imports
- **Catch errors early**: Run the same inspections IntelliJ runs, see type mismatches, detect code smells
- **Understand structure**: Access project model, module dependencies, source roots - the IDE has indexed everything

**Performance note**: Operations complete in sub-second time for typical projects. Large codebases may take longer.

**Quick example:**
```kotlin
// Find a class and all its usages - indexed, accurate, fast
smartReadAction {
    val classes = KotlinClassShortNameIndex.get("UserService", project, projectScope())
    val usages = ReferencesSearch.search(classes.first(), projectScope()).findAll()
    println("Found ${usages.size} usages")
}
```

**Parameters:** `project_name`, `code` (Kotlin suspend function body), `task_id`, `reason`, `timeout` (optional)

**Returns:** Execution output with `execution_id` for feedback

**📚 Complete guide:** `mcp-steroid://skill/coding-with-intellij` (API reference, patterns, examples, best practices)

### `steroid_execute_feedback`
Rate execution results. Use after `steroid_execute_code`.

### `steroid_open_project`
Open a project in the IDE. This tool initiates the project opening process and returns quickly.

**IMPORTANT**: This tool does NOT wait for the project to fully open. The project opening process may show dialogs (such as "Trust Project", project type selection, etc.) that require interaction. Use `steroid_take_screenshot` and `steroid_input` tools to interact with any dialogs that appear.

**Parameters:**
- `project_path` (required): Absolute path to the project directory to open
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project path before opening (skips trust dialog). Default: false
- `force_new_frame` (optional): If true, always open in a new window. Default: false

**Workflow:**
1. Call `steroid_open_project` with the project path
2. If `trust_project=true`, the project will be trusted automatically (no trust dialog)
3. Call `steroid_take_screenshot` to see the current IDE state
4. If dialogs are shown, use `steroid_input` to interact with them
5. Call `steroid_list_projects` to verify the project is open

## MCP Resources (Use Them)
