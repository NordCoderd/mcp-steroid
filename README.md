# intellij-mcp-steroid

An MCP (Model Context Protocol) plugin for IntelliJ IDEA that provides a Kotlin console interface, allowing LLM agents to execute code directly within the IDE's runtime environment.

## Overview

This IntelliJ plugin exposes IDE APIs to LLM agents via Kotlin script execution. Code runs in IntelliJ's own classpath with access to all installed plugins.

**Primary Use Case**: An LLM agent submits Kotlin code that runs inside IntelliJ, accessing project structure, PSI (Program Structure Interface), refactoring tools, VFS (Virtual File System), and any other IDE APIs.

**Target Version**: IntelliJ 2025.3+

## MCP Server Integration

This plugin runs its own standalone MCP server using the [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk) with Ktor for HTTP transport:

- **Default port**: 63150 (configurable via `mcp.steroids.server.port` registry key, use 0 for dynamic)
- **Transport**: HTTP at `http://localhost:<port>/mcp` with CORS support
- **Host**: Configurable via `mcp.steroids.server.host` registry key (default: 127.0.0.1, use 0.0.0.0 for Docker)
- **Server URL file**: Written to `.idea/mcp-steroids.txt` in each open project folder

The server starts automatically when IntelliJ launches and writes its URL to project folders for easy discovery by MCP clients.

## Agent Skills Support

This plugin implements the [Agent Skills](https://agentskills.io) protocol, making its capabilities discoverable by AI agents. The skill documentation is served at the server root and `/skill.md` endpoints.

### Skill Discovery Endpoints

- `http://localhost:63150/` - Returns SKILL.md content
- `http://localhost:63150/skill.md` - Returns SKILL.md content
- `http://localhost:63150/SKILL.md` - Returns SKILL.md content

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
curl -s http://localhost:63150/skill.md > SKILL.md

# Or with dynamic port, read from mcp-steroids.txt
MCP_URL=$(grep -o 'http://[^/]*' .idea/mcp-steroids.txt | head -1)
curl -s "${MCP_URL}/skill.md" > SKILL.md
```

**Option 3: Setup script for any project**

Create a setup script `setup-intellij-skill.sh`:

```bash
#!/bin/bash
# setup-intellij-skill.sh - Add IntelliJ MCP Steroid skill to current project

set -e

# Default port
PORT=${1:-63150}
SERVER_URL="http://localhost:${PORT}"

# Check if server is running
if ! curl -s "${SERVER_URL}/skill.md" > /dev/null 2>&1; then
    echo "Error: MCP Steroid server not running at ${SERVER_URL}"
    echo "Make sure IntelliJ IDEA is running with the MCP Steroid plugin installed."
    exit 1
fi

# Download skill
curl -s "${SERVER_URL}/skill.md" > SKILL.md
echo "Downloaded SKILL.md from ${SERVER_URL}"

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
./setup-intellij-skill.sh        # Use default port 63150
./setup-intellij-skill.sh 6315   # Use custom port
```

### Verifying Skill Setup

```bash
# Check skill is accessible
cat SKILL.md | head -20

# Verify server is running and responding
curl -s http://localhost:63150/ | head -20
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
# Add the IntelliJ MCP server (default port 63150)
claude mcp add --transport http intellij-steroid http://localhost:63150/mcp

# Verify connection
claude mcp list
# Should show: intellij-steroid: http://localhost:63150/mcp (HTTP) - ✓ Connected

# Use the tools
claude -p "List all open projects using steroid_list_projects"
```

If you're using a **dynamic port** (registry key set to 0), check `.idea/mcp-steroids.txt` for the actual URL:

```bash
# Read the actual URL from the project
MCP_URL=$(cat .idea/mcp-steroids.txt)
claude mcp add --transport http intellij-steroid "$MCP_URL"
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

# IntelliJ MCP Steroid server (default port 63150)
[mcp_servers.intellij-steroid]
url = "http://localhost:63150/mcp"
```

Or create it with a single command:

```bash
# Create config directory and file
mkdir -p ~/.codex && cat > ~/.codex/config.toml << 'EOF'
[features]
rmcp_client = true

[mcp_servers.intellij-steroid]
url = "http://localhost:63150/mcp"
EOF

# Use the tools
codex exec "List all open projects using steroid_list_projects"
```

For dynamic ports, read the URL from `.idea/mcp-steroids.txt`:
```bash
MCP_URL=$(tail -1 .idea/mcp-steroids.txt)
mkdir -p ~/.codex && cat > ~/.codex/config.toml << EOF
[features]
rmcp_client = true

[mcp_servers.intellij-steroid]
url = "$MCP_URL"
EOF
```

To remove the server, delete the `[mcp_servers.intellij-steroid]` section from the config file.

### Gemini CLI

Add the MCP server using the command line:

```bash
# Add the IntelliJ MCP server (default port 63150)
gemini mcp add intellij-steroid http://localhost:63150/mcp --transport http --scope user

# Verify connection
gemini mcp list
# Should show: ✓ intellij-steroid: http://localhost:63150/mcp (http) - Connected

# Use the tools
gemini "List all open projects using steroid_list_projects"
```

For dynamic ports, read the URL from `.idea/mcp-steroids.txt`:
```bash
MCP_URL=$(tail -1 .idea/mcp-steroids.txt)
gemini mcp add intellij-steroid "$MCP_URL" --transport http --scope user
```

To remove the server:
```bash
gemini mcp remove intellij-steroid
```

### Direct HTTP (curl)

You can also interact with the server directly via HTTP:

```bash
# Initialize session
curl -X POST http://localhost:63150/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}'

# List tools (use the Mcp-Session-Id from the response above)
curl -X POST http://localhost:63150/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Call steroid_list_projects
curl -X POST http://localhost:63150/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"steroid_list_projects"}}'
```

**Session recovery**: If a request with `Mcp-Session-Id` returns HTTP 404, the session has expired or the server restarted. Re-send an `initialize` request without a session header (and refresh the server URL if needed).

## MCP Tools

All tools are prefixed with `steroid_` to distinguish them from IntelliJ's built-in MCP tools.

### `steroid_list_projects`
Lists all open projects in the IDE.

**Returns**:
```json
{
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

### `steroid_execute_code`
Compiles and executes Kotlin code in the IDE's runtime context.

**Parameters**:
- `project_name` (required): Name of an open project (from `steroid_list_projects`)
- `code` (required): Kotlin code to execute
- `task_id` (required): Your task identifier to group related executions
- `reason` (required): Human readable reason for the execution
- `timeout`: Execution timeout in seconds (default: 60)
- `required_plugins` (optional): List of required plugin IDs (example: `com.intellij.database`)

**Execution Model**:
- **Synchronous request-response** - the tool returns when execution completes
- **Progress notifications** - sent during execution (via MCP progress protocol)
- **No polling required** - output is returned directly in the response
- Returns `execution_id` for use with `steroid_execute_feedback`

**Response**: Plain text output from the script, or error message on failure.

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

Scripts **must** call `execute { }` to interact with the IDE. All code must be written as **suspend functions** - never use `runBlocking`:

```kotlin
execute {
    // McpScriptContext is the receiver (`this`)
    println("Hello from IntelliJ!")

    // Report progress (throttled to 1 message per second)
    progress("Starting analysis...")

    // Wait for indexes to be ready
    waitForSmartMode()

    // Access the project
    val projectRef = project

    progress("Indexes ready, processing...")

    // For read/write actions, use IntelliJ's coroutine-aware APIs (import readAction/writeAction)

    val psiFile = readAction {
        PsiManager.getInstance(projectRef).findFile(virtualFile)
    }

    writeAction {
        document.setText("new content")
    }

    println("Done!")
}
```

**Predefined Imports** (auto-imported):
```kotlin
import com.intellij.openapi.project.*
import com.intellij.openapi.application.*
import com.intellij.openapi.application.readAction   // For read actions
import com.intellij.openapi.application.writeAction  // For write actions
import com.intellij.openapi.vfs.*
import com.intellij.openapi.editor.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.command.*
import com.intellij.psi.*
import kotlinx.coroutines.*
```

## McpScriptContext API

The `McpScriptContext` is provided inside the `execute { }` block.

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt)

Key methods:
- `project` - Access the IntelliJ Project
- `params` - Original tool execution parameters (JSON)
- `disposable` - Parent Disposable for resource cleanup
- `println(vararg values)` - Print space-separated values
- `printJson(obj)` - Serialize to pretty JSON (Jackson)
- `printException(msg, throwable)` - Report an error without failing execution
- `progress(message)` - Report progress (throttled to 1/sec)
- `takeIdeScreenshot(fileName)` - Capture IDE screenshot and return image content (artifacts saved as `screenshot.png`, `screenshot-tree.md`, `screenshot-meta.json`; fileName ignored)
- `waitForSmartMode()` - Wait for indexing to complete

**Note**: `readAction` and `writeAction` are NOT part of McpScriptContext. Use IntelliJ's coroutine-aware APIs directly:

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction

execute {
    // Read PSI data
    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(virtualFile)
    }

    // Modify documents/PSI
    writeAction {
        document.setText("new content")
    }
}
```

### Disposable Hierarchy

The context provides a `disposable` property for scripts that need to register cleanup:

```kotlin
execute {
    // Access the execution's parent Disposable
    val execDisposable = disposable

    // Register your own cleanup
    val myResource = Disposer.newDisposable("my-resource")
    Disposer.register(execDisposable, myResource)

    // myResource will be disposed when execution completes (success, error, or timeout)
}
```

The context is disposed automatically:
- After all execute blocks complete successfully
- If any execute block throws an exception
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
   - Captures all `execute { }` lambdas (FIFO order)
   - Compilation errors are reported immediately (no timeout waiting)
5. **Script Execution** (`ScriptExecutor`):
   - Runs inside `coroutineScope { withContext(Dispatchers.IO) { withTimeout { } } }`
   - Execute blocks run in FIFO order
   - Progress messages sent via MCP progress notifications (throttled to 1/second)
   - Context is disposed when execution completes
6. **Response**: Output returned directly in MCP tool response
7. **Cleanup**: Disposable disposed via `Disposer.dispose()`, resources cleaned up

### Fast Failure

- **Script engine not available**: Returns ERROR immediately
- **Compilation errors**: Returns ERROR immediately with details (no timeout waiting)
- **Missing execute {} block**: Returns ERROR immediately
- **Runtime errors**: Returns ERROR with stack trace
- **Timeout**: Coroutine cancelled, Disposable disposed via Disposer

### Multiple Execute Blocks

Scripts can have multiple `execute { }` calls - they are collected and run in FIFO order:

```kotlin
execute { println("First") }
execute { println("Second") }
execute { println("Third") }
// Outputs: First, Second, Third (in order)
```

### Scope Disposal

After script evaluation completes:
- The execute scope is marked as disposed
- Calling `execute { }` from within an execute block is rejected
- This prevents patterns like: `execute { execute { } }`

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
.idea/mcp-steroids.txt                     # Contains MCP server URL (e.g., http://localhost:63150/mcp)
```

**Execution ID Format**: `{YYYYMMDD}T{HHMMSS}-{task-id}`
- Timestamp: ISO-like format without timezone
- Task ID: sanitized version of the task_id parameter

## Code Review Mode

By default, all submitted code is opened in the IDE editor for human review before execution.

**Configuration** (IntelliJ Registry):
- `mcp.steroids.review.mode`: `ALWAYS` (default), `TRUSTED`, `NEVER`
  - `ALWAYS`: Every script requires human approval
  - `TRUSTED`: Auto-approve all (trust MCP callers)
  - `NEVER`: Auto-execute all (development only)
- `mcp.steroids.review.timeout`: Seconds to wait for review (default: 300)
- `mcp.steroids.execution.timeout`: Script execution timeout (default: 60)

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

## Runtime Reflection for API Discovery

**LLM agents should use reflection to discover available APIs at runtime:**

```kotlin
execute {
    // List methods on any class
    PsiManager::class.java.methods.forEach { method ->
        println("${method.name}(${method.parameterTypes.joinToString()})")
    }

    // Explore class hierarchy
    println(PsiManager::class.java.superclass)
    println(PsiManager::class.java.interfaces.toList())
}
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
   execute {
       // Reading PSI requires a read action
       val psiFile = readAction {
           PsiManager.getInstance(project).findFile(virtualFile)
       }

       // Modifying documents requires a write action
       writeAction {
           document.setText("new content")
       }
   }
   ```

2. **Smart Mode**: Many APIs require indices to be built. Use `waitForSmartMode()`:
   ```kotlin
   execute {
       waitForSmartMode()  // Wait for indexing

       // Now safe to use index-dependent APIs
       val classes = readAction {
           JavaPsiFacade.getInstance(project)
               .findClasses("com.example.MyClass", GlobalSearchScope.allScope(project))
       }
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
   execute {
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
   }
   ```

3. **Using Disposables for cleanup**:
   ```kotlin
   execute {
       val myListener = object : FileEditorManagerListener {
           override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
               println("Opened: ${file.name}")
           }
       }

       // Register with the context's disposable for automatic cleanup
       project.messageBus.connect(disposable)
           .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, myListener)
   }
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

# Run specific test class
./gradlew test --tests "*McpServerIntegrationTest*"
./gradlew test --tests "*ClaudeCliIntegrationTest*"
./gradlew test --tests "*ScriptExecutorTest*"
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
  - Tests TOML configuration and tool invocation
  - Includes system property test verifying end-to-end MCP execution
  - Note: `codex mcp add` only supports stdio servers; HTTP uses TOML config
- `ScriptExecutorTest.kt` - Tests script execution with fast failure semantics
- `ExecutionManagerTest.kt` - Tests execution manager with progress reporting
- `SteroidsMcpToolsetTest.kt` - Tests MCP tool execution flow

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
