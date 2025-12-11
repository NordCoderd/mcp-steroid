# intellij-mcp-steroid

An MCP (Model Context Protocol) plugin for IntelliJ IDEA that provides a Kotlin console interface, allowing LLM agents to execute code directly within the IDE's runtime environment.

## Overview

This IntelliJ plugin exposes IDE APIs to LLM agents via Kotlin script execution. Code runs in IntelliJ's own classpath with access to all installed plugins.

**Primary Use Case**: An LLM agent submits Kotlin code that runs inside IntelliJ, accessing project structure, PSI (Program Structure Interface), refactoring tools, VFS (Virtual File System), and any other IDE APIs.

**Target Version**: IntelliJ 2025.3+

## MCP Server Integration

This plugin registers tools with IntelliJ's built-in MCP server (`com.intellij.mcpServer`) via the `mcpToolset` extension point:

```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
  <mcpToolset implementation="com.jonnyzzz.intellij.mcp.SteroidsMcpToolset"/>
</extensions>
```

No separate server or port configuration needed - uses IntelliJ's MCP infrastructure.

## MCP Tools

### `list_projects`
Lists all open projects in the IDE.

**Returns**:
```json
[
  {"name": "my-project", "path": "/path/to/my-project"},
  {"name": "another-project", "path": "/path/to/another-project"}
]
```

### `execute_code`
Compiles and executes Kotlin code in the IDE's runtime context.

**Parameters**:
- `project_name` (required): Name of an open project (from `list_projects`)
- `code` (required): Kotlin code to execute
- `timeout`: Execution timeout in seconds (default: 60)
- `show_review_on_error`: If true, show code in editor even on compilation error

**Execution Model**:
- **Compilation is synchronous** - blocks until compiled
- On success: assigns `execution_id`, starts async execution (or review if enabled)
- On compilation error: returns error immediately (unless `show_review_on_error=true`)
- Use `get_result` to poll for execution output
- Requests are executed sequentially (queued)

**Response**:
```json
{
    "execution_id": "abc-2024-12-10T14-30-25-a1B2c3D4e5",
    "status": "running" | "pending_review" | "compilation_error",
    "errors": [...]
}
```

### Script Entry Point

Scripts **must** call `execute { }` to interact with the IDE. All code must be written as **suspend functions** - never use `runBlocking`:

```kotlin
execute { ctx ->
    // ctx is McpScriptContext - your gateway to the IDE
    ctx.println("Hello from IntelliJ!")

    // Wait for indexes to be ready
    ctx.waitForSmartMode()

    // Access the project
    val project = ctx.project

    // For read/write actions, use IntelliJ's coroutine-aware APIs:
    import com.intellij.openapi.application.readAction
    import com.intellij.openapi.application.writeAction

    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(virtualFile)
    }

    writeAction {
        document.setText("new content")
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
Gets execution output and status via polling.

**Parameters**:
- `execution_id` (required): ID returned from execute_code
- `offset`: Skip first N messages (default: 0)

**Response**:
```json
{
    "execution_id": "abc-2024-12-10T14-30-25-a1B2c3D4e5",
    "status": "running" | "pending_review" | "success" | "error" | "timeout" | "cancelled" | "rejected",
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

## McpScriptContext API

The `McpScriptContext` is provided inside the `execute { }` block:

```kotlin
interface McpScriptContext : Disposable {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** Unique identifier for this execution */
    val executionId: String

    // === Output Methods ===
    fun println(vararg values: Any?)  // Print space-separated values
    fun printJson(obj: Any?)          // Serialize to pretty JSON (Jackson)
    fun logInfo(message: String)
    fun logWarn(message: String)
    fun logError(message: String, throwable: Throwable? = null)

    // === IDE Utilities ===
    suspend fun waitForSmartMode()  // Wait for indexing to complete
}
```

**Note**: `readAction` and `writeAction` are NOT part of McpScriptContext. Use IntelliJ's coroutine-aware APIs directly:

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction

execute { ctx ->
    // Read PSI data
    val psiFile = readAction {
        PsiManager.getInstance(ctx.project).findFile(virtualFile)
    }

    // Modify documents/PSI
    writeAction {
        document.setText("new content")
    }
}
```

### Extended Context (McpScriptContextEx)

For reflection helpers (may be deprecated in future):

```kotlin
interface McpScriptContextEx : McpScriptContext {
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

1. **Code Submission**: MCP tool receives code via `execute_code`
2. **Compilation** (synchronous): Kotlin script engine compiles code
3. **Review** (if enabled): Code opened in editor, waits for human approval
4. **Execution**: The `execute { }` block runs with McpScriptContext inside `supervisorScope`
5. **Output**: Written to file (append-only), polled via `get_result`
6. **Cleanup**: McpScriptContext disposed, resources cleaned up

**Storage Structure** (append-only - files are never deleted):
```
.idea/mcp-run/
├── abc-2024-12-10T14-30-25-a1B2c3D4e5/   # execution_id as directory
│   ├── script.kts                         # Submitted code
│   ├── parameters.json                    # Execution parameters
│   ├── output.jsonl                       # Output log (JSON lines, append-only)
│   ├── result.json                        # Final status
│   ├── review.kts                         # Code shown for review (may have user edits)
│   └── review-result.json                 # Review outcome with user feedback
```

**Execution ID Format**: `{project-hash-3chars}-{YYYY-MM-DD}T{HH-MM-SS}-{payload-hash-10chars}`
- Project hash: first 3 chars of base64url-encoded hash of project name
- Timestamp: ISO-like format without timezone
- Payload hash: first 10 chars of base64url-encoded hash of (code + parameters)

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
execute { ctx ->
    // List methods on any class
    PsiManager::class.java.methods.forEach { method ->
        ctx.println("${method.name}(${method.parameterTypes.joinToString()})")
    }

    // Use helper to describe a class (via McpScriptContextEx)
    ctx.println((ctx as McpScriptContextEx).describeClass("com.intellij.psi.PsiManager"))
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
- `gradle.properties`: IntelliJ platform version
- `settings.gradle.kts`: Project name

## Documentation

- [CLAUDE.md](CLAUDE.md) - Guidance for Claude Code
- [Plan.md](Plan.md) - Implementation plan
- [Suggestions.md](Suggestions.md) - Design suggestions
- [Discussions.md](Discussions.md) - Design decisions
