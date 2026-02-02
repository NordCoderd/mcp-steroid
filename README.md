# intellij-mcp-steroid

An MCP (Model Context Protocol) plugin for IntelliJ IDEA that provides a Kotlin console interface, allowing LLM agents to execute code directly within the IDE's runtime environment.

## Overview

This IntelliJ plugin exposes IDE APIs to LLM agents via Kotlin script execution. Code runs in IntelliJ's own classpath with access to all installed plugins.

**Primary Use Case**: An LLM agent submits Kotlin code that runs inside IntelliJ, accessing project structure, PSI (Program Structure Interface), refactoring tools, VFS (Virtual File System), and any other IDE APIs.

**Target Version**: IntelliJ 2025.3+

## Demo Videos

<table>
  <tr>
    <td align="center">
      <a href="https://www.youtube.com/watch?v=6ByedA15n8Q">
        <img src="https://img.youtube.com/vi/6ByedA15n8Q/maxresdefault.jpg" width="400" alt="Main Demo"/>
        <br/><b>Main Demo</b>
      </a>
    </td>
    <td align="center">
      <a href="https://www.youtube.com/watch?v=6p6B5sxgXX8">
        <img src="https://img.youtube.com/vi/6p6B5sxgXX8/maxresdefault.jpg" width="400" alt="Code Execution"/>
        <br/><b>Code Execution</b>
      </a>
    </td>
  </tr>
  <tr>
    <td align="center" colspan="2">
      <a href="https://www.youtube.com/watch?v=dz95tSD9Z-c">
        <img src="https://img.youtube.com/vi/dz95tSD9Z-c/maxresdefault.jpg" width="400" alt="Advanced Features"/>
        <br/><b>Advanced Features</b>
      </a>
    </td>
  </tr>
</table>

## MCP Server Integration

This plugin runs its own standalone MCP server using the [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk) with Ktor for HTTP transport:

- **Transport**: HTTP at `<server-url>/mcp` with CORS support
- **Host/port**: Configurable via `mcp.steroid.server.host` and `mcp.steroid.server.port`
- **Server URL file**: Written to `.idea/mcp-steroid.md` in each open project folder

The server starts automatically when IntelliJ launches and writes its URL to project folders for easy discovery by MCP clients.

Use this in the examples below:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
MCP_BASE_URL=${MCP_URL%/mcp}
```

## NPX Proxy (Experimental)

This repository includes an NPX-based MCP proxy that aggregates multiple running IntelliJ MCP Steroid servers into a single MCP endpoint. See `npx/README.md` for usage and `npx/specs.md` for the validated specification.

## Agent Skills Support

This plugin implements the [Agent Skills](https://agentskills.io) protocol, making its capabilities discoverable by AI agents. The skill documentation is served at the server root and `/skill.md` endpoints.

### Skill Discovery Endpoints

- `$MCP_BASE_URL/` - Returns SKILL.md content
- `$MCP_BASE_URL/skill.md` - Returns SKILL.md content
- `$MCP_BASE_URL/SKILL.md` - Returns SKILL.md content

### Setting Up Agent Skills in Any Project

To make the IntelliJ MCP Steroid skill available in your project:

**Option 1: Symlink to SKILL.md (Recommended)**

```bash
# From your project root, create a symlink to the skill
# This requires the MCP Steroid plugin repository to be cloned locally

ln -s /path/to/intellij-mcp-steroids/SKILL.md SKILL.md
```

**Option 2: Download SKILL.md from the running server**

```bash
# Download the skill documentation from the running MCP server
MCP_URL=$(cat .idea/mcp-steroid.md)
MCP_BASE_URL=${MCP_URL%/mcp}
curl -s "$MCP_BASE_URL/skill.md" > SKILL.md
```

**Option 3: Setup script for any project**

Create a setup script `setup-intellij-skill.sh`:

```bash
#!/bin/bash
# setup-intellij-skill.sh - Add IntelliJ MCP Steroid skill to current project

set -e

# Resolve server URL from argument or project file
MCP_URL=${1:-$(cat .idea/mcp-steroid.md 2>/dev/null)}
if [ -z "$MCP_URL" ]; then
    echo "Error: MCP_URL not provided and .idea/mcp-steroid.md not found"
    echo "Open IntelliJ with MCP Steroid and use the URL from .idea/mcp-steroid.md"
    exit 1
fi
MCP_BASE_URL="${MCP_URL%/mcp}"

# Check if server is running
if ! curl -s "${MCP_BASE_URL}/skill.md" > /dev/null 2>&1; then
    echo "Error: MCP Steroid server not running at ${MCP_BASE_URL}"
    echo "Make sure IntelliJ IDEA is running with the MCP Steroid plugin installed."
    exit 1
fi

# Download skill
curl -s "${MCP_BASE_URL}/skill.md" > SKILL.md
echo "Downloaded SKILL.md from ${MCP_BASE_URL}"

# Optionally add to .gitignore if not already there
if [ -f .gitignore ] && ! grep -q "^SKILL.md$" .gitignore; then
    echo "SKILL.md" >> .gitignore
    echo "Added SKILL.md to .gitignore"
fi

echo "Done! IntelliJ MCP Steroid skill is now available in this project."
```

Usage:
```bash
chmod +x setup-intellij-skill.sh
./setup-intellij-skill.sh                  # Uses .idea/mcp-steroid.md
./setup-intellij-skill.sh "$MCP_URL"       # Pass explicit MCP URL
```

### Verifying Skill Setup

```bash
# Check skill is accessible
cat SKILL.md | head -20

# Verify server is running and responding
MCP_URL=$(cat .idea/mcp-steroid.md)
MCP_BASE_URL=${MCP_URL%/mcp}
curl -s "$MCP_BASE_URL/" | head -20
```

### Using the Skill with AI Agents

Once the skill is set up, AI agents can:

1. **Read the SKILL.md** to understand available capabilities
2. **Connect to the MCP server** using the documented endpoints
3. **Execute IntelliJ API calls** via `steroid_execute_code`

The skill documentation includes:
- Quickstart flow for getting started
- Complete API reference for all MCP tools
- Code examples for common operations (PSI, VFS, refactoring)
- Best practices and troubleshooting guide

## Connecting LLM Agents

### Claude Code CLI

Add the MCP server using the command line (no config files needed):

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
claude mcp add --transport http intellij-steroid "$MCP_URL"

# Verify connection
claude mcp list
# Should show: intellij-steroid: <url> (HTTP) - ✓ Connected

# Use the tools
claude -p "List all open projects using steroid_list_projects"
```

To remove the server:
```bash
claude mcp remove intellij-steroid
```

### OpenAI Codex CLI

Codex CLI uses a TOML config file for HTTP-based MCP servers. Create or edit `~/.codex/config.toml`:

```toml
# Enable HTTP-based MCP client
[features]
rmcp_client = true

[mcp_servers.intellij-steroid]
url = "<mcp-url>"
```

Or create it with a single command:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
mkdir -p ~/.codex && cat > ~/.codex/config.toml << EOF
[features]
rmcp_client = true

[mcp_servers.intellij-steroid]
url = "$MCP_URL"
EOF

# Use the tools
codex exec "List all open projects using steroid_list_projects"
```

To remove the server, delete the `[mcp_servers.intellij-steroid]` section from the config file.

### Gemini CLI

Add the MCP server using the command line:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
gemini mcp add intellij-steroid "$MCP_URL" --transport http --scope user

# Verify connection
gemini mcp list
# Should show: ✓ intellij-steroid: <url> (http) - Connected

# Use the tools
gemini "List all open projects using steroid_list_projects"
```

To remove the server:
```bash
gemini mcp remove intellij-steroid
```

### Direct HTTP (curl)

You can also interact with the server directly via HTTP:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)

# Initialize session
curl -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}'

# List tools (use the Mcp-Session-Id from the response above)
curl -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Call steroid_list_projects
curl -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"steroid_list_projects"}}'
```

**Session recovery**: If a request arrives with an unknown `Mcp-Session-Id` (IDE restart), the server creates a new session and returns a fresh `Mcp-Session-Id` header plus `Mcp-Session-Notice` explaining the reset. Clients should update the stored session ID and continue without re-registering the server.

## MCP Tools

All tools are prefixed with `steroid_` to distinguish them from IntelliJ's built-in MCP tools.

### `steroid_list_projects`
Lists all open projects in the IDE and reports IDE metadata.

**Returns**:
```json
{
  "ide": {
    "name": "IntelliJ IDEA",
    "version": "2025.3.2",
    "build": "IU-253.30387.160"
  },
  "projects": [
    {"name": "my-project", "path": "/path/to/my-project"},
    {"name": "another-project", "path": "/path/to/another-project"}
  ]
}
```

### `steroid_capabilities`
Lists IDE capabilities such as installed plugins and registered languages.

**Parameters**:
- `include_disabled_plugins` (optional): Include disabled plugins in the response (default: false)

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this when multiple windows are open to pick the right `project_name` and `window_id` for screenshot/input tools.

**Returns**:
- `projectName`, `projectPath` (may be null for non-project windows)
- `title`, `isActive`, `isVisible`
- `bounds` (x, y, width, height)
- `windowId` (use for `steroid_take_screenshot` targeting)

### `steroid_action_discovery`
Discover available editor actions, quick-fixes, and gutter actions for a file and caret context.

**Parameters**:
- `project_name` (required): Name of an open project (from `steroid_list_projects`)
- `file_path` (required): Absolute or project-relative path to the file
- `caret_offset` (optional): Caret offset within the file (default: 0)
- `action_groups` (optional): Action group IDs to expand (default: editor popup + gutter)
- `max_actions_per_group` (optional): Cap actions returned per group (default: 200)

**Response**: JSON payload describing actions, intentions, gutter icons, and language context.

### `steroid_take_screenshot`
Capture a screenshot of the IDE frame and return image content.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters**:
- `project_name` (required): Name of an open project (from `steroid_list_projects`)
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the screenshot is needed
- `window_id` (optional): Window id from `steroid_list_windows` to target a specific window

**Artifacts (saved under the execution folder):**
- `screenshot.png`
- `screenshot-tree.md`
- `screenshot-meta.json`

**Response**: Includes `image/png` content plus text output. The response includes `window_id` for use with `steroid_input` (also stored in screenshot metadata).

### `steroid_input`
Send input events (keyboard + mouse) using a sequence string.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters**:
- `project_name` (required): Name of an open project (from `steroid_list_projects`)
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the input is needed
- `screenshot_execution_id` (required): Execution ID from `steroid_take_screenshot` or `takeIdeScreenshot()`
- `sequence` (required): Comma-separated or newline-separated input sequence (commas inside values are allowed unless they look like `, <step>:`; commas are optional when using newlines)

**Sequence examples**:
- `stick:ALT, delay:400, press:F4, type:hurra`
- `click:CTRL+Left@120,200`
- `click:Right@screen:400,300`

**Notes**:
- Comma separators are detected by `, <step>:` patterns, so avoid typing `, delay:` etc in text.
- Trailing commas before a newline are ignored.
- Use `#` for comments until the end of the line.
- Targets default to screenshot coordinates; use `screen:` for absolute screen pixels.
- Input focuses the screenshot window before dispatching events.

### `steroid_open_project`
Open a project in the IDE. This tool initiates the project opening process and returns quickly.

**IMPORTANT**: This tool does NOT wait for the project to fully open. The project opening process may show dialogs (such as "Trust Project", project type selection, etc.) that require interaction. Use `steroid_take_screenshot` and `steroid_input` tools to interact with any dialogs that appear.

**Parameters**:
- `project_path` (required): Absolute path to the project directory to open
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project path before opening (skips trust dialog). Default: true

**Workflow**:
1. Call `steroid_open_project` with the project path
2. If `trust_project=true`, the project will be trusted automatically (no trust dialog)
3. Call `steroid_take_screenshot` to see the current IDE state
4. If dialogs are shown, use `steroid_input` to interact with them
5. Call `steroid_list_projects` to verify the project is open

### `steroid_execute_code`
**Execute code with IntelliJ's brain, not just text files.**

This tool gives AI agents access to IntelliJ's semantic code model - the same indexed intelligence that makes IntelliJ the world's most productive IDE.

**Why use this over file operations:**

| Task | File-Based Approach | With steroid_execute_code |
|------|-------------------|--------------------------|
| Find usages | grep (text matches, false positives) | Query semantic index (exact, fast) |
| Rename function | regex replace (misses dynamic refs) | Automated refactoring (handles most cases) |
| Check for errors | Parse text, guess at types | Run IntelliJ inspections (see real errors) |
| Understand class | Read one file | Traverse full hierarchy, see all relationships |

*Performance scales with project size - most operations complete in sub-second time for typical projects.*

**What you get:**
- **PSI (Program Structure Interface)**: Semantic understanding beyond syntax trees
- **Automated refactorings**: Extract method, rename, move class - IntelliJ-quality transformations
- **Code inspections**: Run the same checks IntelliJ runs
- **Project model**: Modules, dependencies, source roots - already indexed
- **Find usages**: Query the index, not grep

**Parameters**: `project_name`, `code` (Kotlin suspend function body), `task_id`, `reason`, `timeout` (optional), `required_plugins` (optional)

**Execution model**: Synchronous request-response, progress notifications via MCP protocol, returns `execution_id`

**Response**: Text output with execution results or error message

**📚 Complete coding guide**: `mcp-steroid://coding-with-intellij` (MCP resource with API reference, patterns, examples)

### `steroid_execute_feedback`
Provide feedback on the result of a script execution.

**Parameters**:
- `project_name` (required): Project where execution occurred
- `task_id` (required): Same task_id used in `steroid_execute_code`
- `execution_id` (required): The execution_id returned from execution
- `success_rating` (required): Rate from 0.00 (failure) to 1.00 (success)
- `explanation` (required): What worked, what didn't, what you'll try next
- `code` (optional): The code snippet that was executed

This feedback helps track execution history and identify patterns for improvement.

### Script Entry Point

The `code` parameter is a **suspend function body** (no `execute {}` wrapper needed). Key points:
- `waitForSmartMode()` called automatically before your script starts
- Use `readAction`/`writeAction` for PSI/VFS access
- Built-in helpers: `println()`, `progress()`, `printException()`, `takeIdeScreenshot()`
- Default imports provided (optional to override)

**See the comprehensive coding guide:** `mcp-steroid://coding-with-intellij` (MCP resource)

## McpScriptContext API

The `McpScriptContext` is provided as the receiver of the script body.

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt)

### Core Properties
- `project` - Access the IntelliJ Project
- `params` - Original tool execution parameters (JSON)
- `disposable` - Parent Disposable for resource cleanup
- `isDisposed` - Check if the context is disposed

### Output Methods
- `println(vararg values)` - Print space-separated values
- `printJson(obj)` - Serialize to pretty JSON (Jackson)
- `printException(msg, throwable)` - Report an error without failing execution
- `progress(message)` - Report progress (throttled to 1/sec)
- `takeIdeScreenshot(fileName)` - Capture IDE screenshot (artifacts saved as `screenshot.png`, `screenshot-tree.md`, `screenshot-meta.json`)

### IDE Utilities
- `waitForSmartMode()` - Wait for indexing to complete
- `isDaemonRunning()` - Check if daemon code analyzer is currently running
- `waitForDaemonAnalysis(file, timeout)` - Wait for highlighting to complete on a file
- `getHighlightsWhenReady(file, minSeverityValue, timeout)` - Get highlights after analysis completes (note: requires IDE window focus)
- `runInspectionsDirectly(file, includeInfoSeverity)` - **Recommended**: Run inspections bypassing daemon cache, works regardless of window focus
- `doNotCancelOnModalityStateChange()` - Disable automatic cancellation when modal dialogs appear

### Built-in Helpers (NO IMPORTS NEEDED)
- `readAction { }` - Execute under read lock
- `writeAction { }` - Execute under write lock
- `smartReadAction { }` - waitForSmartMode() + readAction in one call
- `projectScope()` - GlobalSearchScope for project files
- `allScope()` - GlobalSearchScope for project + libraries
- `findFile(path)` - Find VirtualFile by absolute path
- `findPsiFile(path)` - Find PsiFile by absolute path
- `findProjectFile(relativePath)` - Find file relative to project
- `findProjectPsiFile(relativePath)` - Find PsiFile relative to project

**Note**: `readAction`, `writeAction`, and `smartReadAction` are built into McpScriptContext - no imports needed! They delegate to IntelliJ's coroutine-aware APIs.

```kotlin
    // Read PSI data - no import needed!
    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(virtualFile)
    }

    // Modify documents/PSI - no import needed!
    writeAction {
        document.setText("new content")
    }

    // Wait for smart mode + read in one call - no import needed!
    val classes = smartReadAction {
        KotlinClassShortNameIndex.get("MyClass", project, projectScope())
    }
```

### Disposable Hierarchy

The context provides a `disposable` property for scripts that need to register cleanup:

```kotlin
    // Access the execution's parent Disposable
    val execDisposable = disposable

    // Register your own cleanup
    val myResource = Disposer.newDisposable("my-resource")
    Disposer.register(execDisposable, myResource)

    // myResource will be disposed when execution completes (success, error, or timeout)
```

The context is disposed automatically:
- After script execution completes successfully
- If the script throws an exception
- If execution times out
- If execution is cancelled

## Code Execution Architecture

### Execution Flow

1. **MCP Request**: `steroid_execute_code` tool receives code
2. **ExecutionManager**: Orchestrates the workflow within MCP request scope
3. **Review Phase** (if enabled): Code opened in editor via `ReviewManager`, waits for human approval
4. **Code Evaluation** (`CodeEvalManager`):
   - Script Engine Check: Fast fail if Kotlin script engine not available
   - Compilation Phase: Kotlin script engine compiles and evaluates the code
   - Wraps the script body into a single runnable block
   - Compilation errors are reported immediately (no timeout waiting)
5. **Script Execution** (`ScriptExecutor`):
   - Runs inside `coroutineScope { withContext(Dispatchers.IO) { withTimeout { } } }`
   - Script body runs inside a single execution block
   - Progress messages sent via MCP progress notifications (throttled to 1/second)
   - Context is disposed when execution completes
6. **Response**: Output returned directly in MCP tool response
7. **Cleanup**: Disposable disposed via `Disposer.dispose()`, resources cleaned up

### Fast Failure

- **Script engine not available**: Returns ERROR immediately
- **Compilation errors**: Returns ERROR immediately with details (no timeout waiting)
- **Runtime errors**: Returns ERROR with stack trace
- **Timeout**: Coroutine cancelled, Disposable disposed via Disposer

### Script Body Execution

Scripts are executed as a single suspend body. Multiple top-level statements run in order:

```kotlin
println("First")
println("Second")
println("Third")
```

### Scope Disposal

After script execution completes:
- The context is marked as disposed and output APIs stop accepting calls

**Storage Structure** (append-only - files are never deleted, used for logging/debugging):
```
.idea/mcp-run/
├── 20241210T143025-my_task/              # execution_id as directory
│   ├── script.kts                         # Submitted code
│   ├── params.json                        # Execution parameters
│   ├── reason.txt                         # Human readable reason
│   ├── execution-id.txt                   # Execution ID
│   ├── output.jsonl                       # Output log (JSON lines, append-only)
│   ├── error.txt                          # Error message (if failed)
│   ├── review.kts                         # Code shown for review (may have user edits)
│   └── kotlinc.txt                        # Compiler output (if any)
```

**Server URL file**:
```
.idea/mcp-steroid.md                     # Contains MCP server URL (read this file; do not assume a port)
```

**Execution ID Format**: `{YYYYMMDD}T{HHMMSS}-{task-id}`
- Timestamp: ISO-like format without timezone
- Task ID: sanitized version of the task_id parameter

## Code Review Mode

By default, all submitted code is opened in the IDE editor for human review before execution.

**Configuration** (IntelliJ Registry):
- `mcp.steroid.review.mode`: `ALWAYS` (default), `TRUSTED`, `NEVER`
  - `ALWAYS`: Every script requires human approval
  - `TRUSTED`: Auto-approve all (trust MCP callers)
  - `NEVER`: Auto-execute all (development only)
- `mcp.steroid.review.timeout`: Seconds to wait for review (default: 300)
- `mcp.steroid.execution.timeout`: Script execution timeout (default: 60)

**Workflow**:
1. Code submitted → opened in editor with review panel
2. Human reviews, can edit code to add comments or corrections
3. Approve: code executes, result returned
4. Reject: rejection message returned with edited code and unified diff

**User Feedback on Rejection**:
When code is rejected, the LLM receives:
- The original code
- The edited code (with user's comments/corrections)
- A unified diff showing exactly what the user changed
- Whether the code was modified

This allows the LLM to understand user feedback and adjust its approach.

All requests are logged to disk regardless of review mode.

## Demo Mode

Demo Mode provides a visual overlay window that shows real-time progress of MCP command executions. This is ideal for creating demo videos, presentations, or debugging.

### Features

- **Visual overlay**: Positioned centrally over the active IntelliJ project frame
- **Console log**: Shows the last 15 lines of execution output with JetBrains Mono font
- **Animated status**: Displays a rotating "Running..." message with 20 synonyms
- **Warm color theme**: Orange-brown gradient inspired by devrig.dev
- **Click-through design**: ESC key or X button to dismiss
- **Multi-monitor support**: Follows the active project frame

### Configuration

Enable via IntelliJ Registry (`Help > Find Action > Registry...`):

| Registry Key | Default | Description |
|--------------|---------|-------------|
| `mcp.steroid.demo.enabled` | `false` | Enable Demo Mode overlay |
| `mcp.steroid.demo.minDisplayTime` | `3000` | Minimum display time in milliseconds |
| `mcp.steroid.demo.maxLines` | `15` | Maximum log lines to display |
| `mcp.steroid.demo.opacity` | `85` | Background opacity (0-100) |
| `mcp.steroid.demo.focusFrame` | `true` | Bring project frame to front when overlay appears |

### Usage

1. Open Registry: `Help > Find Action > Registry...`
2. Search for `mcp.steroid.demo.enabled`
3. Check the checkbox to enable
4. Execute any MCP command - the overlay will appear showing progress

The overlay automatically:
- Fades in when execution starts
- Updates with log output and progress messages
- Shows an animated "Running..." status with spinner
- Fades out when execution completes (after minimum display time)

## Runtime Reflection for API Discovery

**LLM agents should use reflection to discover available APIs at runtime:**

```kotlin
    // List methods on any class
    PsiManager::class.java.methods.forEach { method ->
        println("${method.name}(${method.parameterTypes.joinToString()})")
    }

    // Explore class hierarchy
    println(PsiManager::class.java.superclass)
    println(PsiManager::class.java.interfaces.toList())
```

## IntelliJ API Reference

- **Source Code**: https://github.com/intellij-community
- **Documentation**: https://plugins.jetbrains.com/docs/intellij/

Key packages:
- `com.intellij.openapi.project` - Project management
- `com.intellij.openapi.vfs` - Virtual File System
- `com.intellij.psi` - Program Structure Interface
- `com.intellij.openapi.application` - Application services, read/write actions
- `com.intellij.openapi.editor` - Editor APIs

### IntelliJ Coding Principles for Scripts

When writing scripts for execution, follow these IntelliJ Platform best practices:

#### Threading Model

1. **Read/Write Actions are required** for PSI and VFS access:
   ```kotlin
       // Reading PSI requires a read action
       val psiFile = readAction {
           PsiManager.getInstance(project).findFile(virtualFile)
       }

       // Modifying documents requires a write action
       writeAction {
           document.setText("new content")
       }
   ```

2. **Smart Mode**: Many APIs require indices to be built. `waitForSmartMode()` is called automatically before your script starts; call it again only if you trigger indexing mid-script:
   ```kotlin
       // Now safe to use index-dependent APIs
       val classes = readAction {
           JavaPsiFacade.getInstance(project)
               .findClasses("com.example.MyClass", GlobalSearchScope.allScope(project))
       }
   ```

#### Common Patterns

1. **Getting services**:
   ```kotlin
   // Project services
   val fileEditorManager = FileEditorManager.getInstance(project)
   val psiManager = PsiManager.getInstance(project)

   // Application services
   val vfsManager = VirtualFileManager.getInstance()
   val app = ApplicationManager.getApplication()
   ```

2. **Working with files**:
   ```kotlin
       // Find a file
       val vFile = LocalFileSystem.getInstance()
           .findFileByPath("/path/to/file.kt")

       // Get PSI
       val psiFile = readAction {
           PsiManager.getInstance(project).findFile(vFile!!)
       }

       // Modify
       writeAction {
           val document = FileDocumentManager.getInstance().getDocument(vFile!!)
           document?.setText("new content")
       }
   ```

3. **Using Disposables for cleanup**:
   ```kotlin
       val myListener = object : FileEditorManagerListener {
           override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
               println("Opened: ${file.name}")
           }
       }

       // Register with the context's disposable for automatic cleanup
       project.messageBus.connect(disposable)
           .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, myListener)
   ```

#### Error Handling

- Scripts run in a supervised coroutine scope
- Exceptions are caught and reported in the response
- Use `printException(msg, throwable)` to report errors without failing execution
- Avoid catching `ProcessCanceledException` - let it propagate

## Testing

### Unit Tests

```bash
# Run all tests
./gradlew test

# Run a single test: ScriptExecutorTest verifies fast-failure semantics and FIFO execute ordering
./gradlew test --tests "*ScriptExecutorTest*"

# Run other specific test classes
./gradlew test --tests "*McpServerIntegrationTest*"
./gradlew test --tests "*ClaudeCliIntegrationTest*"
./gradlew test --tests "*ExecutionManagerTest*"
```

### Integration Tests

The project includes integration tests that verify MCP server functionality:

**Test Files:**
- `McpServerIntegrationTest.kt` - Tests MCP server HTTP handshake, session management, and tool invocation
  - Includes system property test verifying MCP server runs in IDE JVM
- `ClaudeCliIntegrationTest.kt` - Tests Claude Code CLI integration (requires Docker, ANTHROPIC_API_KEY)
  - Tests MCP server registration, tool discovery, and documented workflow
  - Includes system property test verifying end-to-end MCP execution
- `CodexCliIntegrationTest.kt` - Tests OpenAI Codex CLI integration (requires Docker, OPENAI_API_KEY)
  - Includes a three-step execute_code flow (exec → forget sessions → exec) and MCP list validation
  - Tests TOML configuration and tool invocation
  - Includes system property test verifying end-to-end MCP execution
  - Note: `codex mcp add` only supports stdio servers; HTTP uses TOML config
- `ScriptExecutorTest.kt` - Tests script execution with fast failure semantics
- `ExecutionManagerTest.kt` - Tests execution manager with progress reporting
- `SteroidsMcpToolsetTest.kt` - Tests MCP tool execution flow
- `ScriptExecutionAvailabilityTest.kt` - Fast-fail smoke test to detect broken execute_code engine
- `OcrProcessClientTest.kt` - Runs OCR extraction against bundled test images via the `ocr-tesseract` helper app
- `KotlincProcessClientTest.kt` - Runs bundled kotlinc with `--version` to verify the compiler executable
- `KotlincCommandLineBuilderIntegrationTest.kt` - Compiles Kotlin sources into a jar using bundled kotlinc
- `ScriptClassLoaderFactoryTest.kt` - Verifies IDE classpath discovery and exec code classloader wiring

**Shell Scripts** (in `integration-test/`):
- `test-sse-tools.sh` - Tests HTTP transport directly via curl
- `run-test.sh` - Automated integration test with Claude CLI
- `manual-test.sh` - Interactive Claude CLI test

See [integration-test/README.md](integration-test/README.md) for details.

## Building and Running

```bash
# Build the plugin
./gradlew build

# Run the plugin in IntelliJ sandbox
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# Run tests
./gradlew test
```

## Build Notes

- IDE distributions are cached under `.intellijPlatform/ides/IU-2025.3` (`intellijPlatform.caching.ides.enabled = true`).
- The build moves `plugins/fullLine/lib/modules/intellij.fullLine.yaml.jar` to `.bak` inside the local IDE cache to avoid plugin-structure warnings; the Gradle cache is not modified.
- The OCR helper app is built from the `ocr-tesseract` subproject and bundled under `ocr-tesseract/` inside the plugin distribution.

## Configuration

- `build.gradle.kts`: Build configuration
- `gradle.properties`: IntelliJ platform version
- `settings.gradle.kts`: Project name

## Documentation

- [SKILL.md](SKILL.md) - Agent Skills documentation (for AI agents)
- [CLAUDE.md](CLAUDE.md) - Guidance for Claude Code
- [Plan.md](Plan.md) - Implementation plan
- [Suggestions.md](Suggestions.md) - Design suggestions
- [Discussions.md](Discussions.md) - Design decisions
