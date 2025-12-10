# intellij-mcp-steroid

An MCP (Model Context Protocol) Server for IntelliJ IDEA that provides a Kotlin console interface, allowing LLM agents to execute code directly within the IDE's runtime environment.

## Overview

This IntelliJ plugin exposes IDE APIs to LLM agents via Kotlin script execution. Code runs in IntelliJ's own classpath with access to all installed plugins.

**Primary Use Case**: An LLM agent submits Kotlin code that runs inside IntelliJ, accessing project structure, PSI (Program Structure Interface), refactoring tools, VFS (Virtual File System), and any other IDE APIs.

## MCP Server Integration

### Option A: IntelliJ's Built-in MCP Server (Preferred)

IntelliJ 2024.3+ includes a built-in MCP server plugin (`com.intellij.mcpServer`). This plugin registers tools via the `mcpToolset` extension point:

```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
  <mcpToolset implementation="com.jonnyzzz.intellij.mcp.SteroidsMcpToolset"/>
</extensions>
```

### Option B: REST Endpoint

For compatibility with older IntelliJ versions or standalone use:
- Endpoint: `/api/steroids-mcp` on IntelliJ's built-in HTTP server
- Port: typically 63342 (range 63342-63361)

See [STDIO_PROXY.md](STDIO_PROXY.md) for connecting stdio-based MCP clients.

## API

### `execute_code`
Compiles and executes Kotlin code in the IDE's runtime context.

**Parameters**:
- `project_path` (required): Path to the project directory (must match an open project)
- `code` (required): Kotlin code to execute
- `timeout`: Execution timeout in seconds (default: 60)
- `show_review_on_error`: If true, show code in editor even on compilation error

**Execution Model**:
- **Blocks during compilation**
- On success: assigns `execution_id`, starts async execution (or review if enabled)
- On compilation error: returns error immediately (unless `show_review_on_error=true`)
- Use `get_result` to get execution output
- Requests are executed sequentially (queued)

**Response**:
```json
{
    "execution_id": "abc/20241210/143025-x7k9m2p1",
    "status": "running" | "pending_review" | "compilation_error",
    "errors": [...]
}
```

### Script Entry Point

Scripts **must** call `execute { }` to interact with the IDE:

```kotlin
execute { ctx ->
    // ctx is McpScriptContext - your gateway to the IDE
    ctx.println("Hello from IntelliJ!")

    // Wait for indexes to be ready
    ctx.waitForSmartMode()

    // Access the project
    val project = ctx.project

    // Read/write actions for PSI
    ctx.readAction {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    }

    ctx.writeAction {
        psiFile.add(newElement)
    }
}
```

**Predefined Imports** (auto-imported):
```kotlin
import com.intellij.openapi.project.*
import com.intellij.openapi.application.*
import com.intellij.openapi.vfs.*
import com.intellij.openapi.editor.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.command.*
import com.intellij.psi.*
import kotlinx.coroutines.*
```

### `get_result`
Gets execution output and status. Can stream via SSE.

**Parameters**:
- `execution_id` (required): ID returned from execute_code
- `stream`: If true, use SSE to stream output until completion (default: false)
- `offset`: Skip first N messages (default: 0)

**Response**:
```json
{
    "execution_id": "abc/20241210/143025-x7k9m2p1",
    "status": "running" | "pending_review" | "success" | "error" | "timeout" | "cancelled",
    "output": [
        {"ts": 1733840000000, "type": "out", "msg": "Hello from IntelliJ!"},
        {"ts": 1733840000100, "type": "log", "level": "info", "msg": "Processing..."}
    ],
    "errors": [...],
    "exception": {...}
}
```

### `cancel_execution`
Cancels a running execution or pending review.

**Parameters**:
- `execution_id` (required): ID returned from execute_code

### `read_slot` / `write_slot`
Named storage slots for persisting data between executions. Slots are project-scoped.

**Parameters**:
- `project_path` (required): Path to the project directory
- `slot_name`: Name of the slot
- `value` (write only): String value to store

## McpScriptContext API

The `McpScriptContext` is provided inside the `execute { }` block:

```kotlin
interface McpScriptContext : Disposable {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** CoroutineScope bound to this context's lifecycle */
    val coroutineScope: CoroutineScope

    // === Output Methods ===
    fun println(message: Any?)
    fun print(message: Any?)
    fun printJson(obj: Any?)  // Serialize to JSON
    fun logInfo(message: String)
    fun logWarn(message: String)
    fun logError(message: String, throwable: Throwable? = null)

    // === Slot Storage ===
    fun readSlot(name: String): String?
    fun writeSlot(name: String, value: String)

    // === IDE Utilities ===
    suspend fun waitForSmartMode()  // Wait for indexing to complete
    suspend fun <T> readAction(block: () -> T): T
    suspend fun <T> writeAction(block: () -> T): T

    // === Reflection Helpers ===
    fun listServices(): List<String>
    fun listExtensionPoints(): List<String>
    fun describeClass(className: String): String
}
```

### Disposable Hierarchy

`McpScriptContext` implements `Disposable`. Use it as a parent for any resources that should be cleaned up when the script execution completes:

```kotlin
execute { ctx ->
    val myResource = Disposer.newDisposable()
    Disposer.register(ctx, myResource)

    // myResource will be disposed when execution completes
}
```

## Code Execution Architecture

1. **Code Submission**: MCP server receives code via `execute_code`
2. **Compilation** (blocking): Kotlin script engine compiles code
3. **Review** (if enabled): Code opened in editor, waits for human approval
4. **Execution**: The `execute { }` block runs with McpScriptContext
5. **Output**: Written to file, streamed via `get_result`
6. **Cleanup**: McpScriptContext disposed, resources cleaned up

**Storage Structure**:
```
.idea/mcp-run/
├── abc/                           # Project hash (3 chars)
│   └── 20241210/                  # Date folder
│       └── 143025-x7k9m2p1/       # HHMMSS-random
│           ├── script.kt          # Submitted code
│           ├── parameters.json    # Execution parameters
│           ├── output.jsonl       # Output log (JSON lines)
│           └── result.json        # Final status
```

**Execution ID Format**: `{project-hash}/{YYYYMMDD}/{HHMMSS}-{random8}`

## Code Review Mode

By default, all submitted code is opened in the IDE editor for human review before execution.

**Configuration** (IntelliJ Registry):
- `mcp.steroids.review.mode`: `ALWAYS` (default), `TRUSTED`, `NEVER`
- `mcp.steroids.review.timeout`: Seconds to wait for review (default: 300)
- `mcp.steroids.execution.timeout`: Script execution timeout (default: 60)

**Workflow**:
1. Code submitted → opened in editor with review panel
2. Human reviews, can add comments/edits
3. Approve: code executes, result returned
4. Reject: rejection message returned with any edits/comments

All requests are logged to disk regardless of review mode.

## Runtime Reflection for API Discovery

**LLM agents should use reflection to discover available APIs at runtime:**

```kotlin
execute { ctx ->
    // List methods on any class
    PsiManager::class.java.methods.forEach { method ->
        ctx.println("${method.name}(${method.parameterTypes.joinToString()})")
    }

    // Use helper to describe a class
    ctx.println(ctx.describeClass("com.intellij.psi.PsiManager"))
}
```

## IntelliJ API Reference

- **Source Code**: https://github.com/intellij-community
- **Documentation**: https://plugins.jetbrains.com/docs/intellij/

Key packages:
- `com.intellij.openapi.project` - Project management
- `com.intellij.openapi.vfs` - Virtual File System
- `com.intellij.psi` - Program Structure Interface
- `com.intellij.openapi.application` - Application services
- `com.intellij.openapi.editor` - Editor APIs

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

## Configuration

- `build.gradle.kts`: Build configuration
- `gradle.properties`: IntelliJ platform version (`platformVersion=2024.2.4`)
- `settings.gradle.kts`: Project name

## Documentation

- [CLAUDE.md](CLAUDE.md) - Guidance for Claude Code
- [Plan.md](Plan.md) - Implementation plan
- [Suggestions.md](Suggestions.md) - Design suggestions
- [Discussions.md](Discussions.md) - Design decisions
- [STDIO_PROXY.md](STDIO_PROXY.md) - Stdio proxy setup
