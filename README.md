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

### `steroid_execute_code`
Compiles and executes Kotlin code in the IDE's runtime context.

**Parameters**:
- `project_name` (required): Name of an open project (from `steroid_list_projects`)
- `code` (required): Kotlin code to execute
- `timeout`: Execution timeout in seconds (default: 60)
- `show_review_on_error`: If true, show code in editor even on compilation error

**Execution Model**:
- **Compilation is synchronous** - blocks until compiled
- On success: assigns `execution_id`, starts async execution (or review if enabled)
- On compilation error: returns error immediately (unless `show_review_on_error=true`)
- Use `steroid_get_result` to poll for execution output
- Requests are executed sequentially (queued)

**Response**:
```json
{
    "execution_id": "abc-2024-12-10T14-30-25-a1B2c3D4e5",
    "status": "SUBMITTED" | "RUNNING" | "SUCCESS" | "ERROR",
    "error_message": "..."
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
import com.intellij.openapi.application.readAction   // For read actions
import com.intellij.openapi.application.writeAction  // For write actions
import com.intellij.openapi.vfs.*
import com.intellij.openapi.editor.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.command.*
import com.intellij.psi.*
import kotlinx.coroutines.*
```

### `steroid_get_result`
Gets execution output and status via polling.

**Parameters**:
- `execution_id` (required): ID returned from `steroid_execute_code`
- `offset`: Skip first N messages (default: 0)

**Status values**:
- `SUBMITTED`: Script is being compiled/prepared
- `RUNNING`: Script is executing
- `PENDING_REVIEW`: Waiting for user to approve/reject
- `SUCCESS`: Completed successfully
- `ERROR`: Failed with error
- `REJECTED`: User rejected the code
- `TIMEOUT`: Execution or review timed out
- `CANCELLED`: Execution was cancelled
- `NOT_FOUND`: Execution ID not found

**Response**:
```json
{
    "execution_id": "abc-2024-12-10T14-30-25-a1B2c3D4e5",
    "status": "SUCCESS",
    "output": [
        {"ts": 1733840000000, "type": "out", "msg": "Hello from IntelliJ!"},
        {"ts": 1733840000100, "type": "log", "level": "info", "msg": "Processing..."}
    ],
    "error_message": null,
    "exception_info": null
}
```

### `steroid_cancel_execution`
Cancels a running execution or pending review.

**Parameters**:
- `execution_id` (required): ID returned from `steroid_execute_code`

**Response**:
```json
{
    "cancelled": true,
    "message": "Execution cancelled"
}
```

## McpScriptContext API

The `McpScriptContext` is provided inside the `execute { }` block:

```kotlin
interface McpScriptContext {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** Unique identifier for this execution */
    val executionId: String

    /** Parent Disposable for resource cleanup - use coroutineScope {} for coroutine API */
    val disposable: Disposable

    /** Check if the context has been disposed */
    val isDisposed: Boolean

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

The context provides a `disposable` property for scripts that need to register cleanup:

```kotlin
execute { ctx ->
    // Access the execution's parent Disposable
    val execDisposable = ctx.disposable

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

1. **Code Submission**: MCP tool receives code via `execute_code`
2. **ExecutionManager**: Orchestrates the workflow, manages state
3. **Review Phase** (if enabled): Code opened in editor via `ReviewManager`, waits for human approval
4. **Code Evaluation** (`CodeEvalManager`):
   - Script Engine Check: Fast fail if Kotlin script engine not available
   - Compilation Phase: Kotlin script engine compiles and evaluates the code
   - Captures all `execute { }` lambdas (FIFO order)
   - Logs the number of captured blocks
   - Compilation errors are reported immediately (no timeout waiting)
5. **Script Execution** (`ScriptExecutor`):
   - Runs inside `coroutineScope { withContext(Dispatchers.IO) { withTimeout { } } }`
   - Execute blocks run in FIFO order
   - Any failure marks the entire job as complete
   - Context is disposed when execution completes (success, error, or timeout)
6. **Output**: Written to file (append-only), polled via `get_result`
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
execute { ctx -> ctx.println("First") }
execute { ctx -> ctx.println("Second") }
execute { ctx -> ctx.println("Third") }
// Outputs: First, Second, Third (in order)
```

### Scope Disposal

After script evaluation completes:
- The execute scope is marked as disposed
- Calling `execute { }` from within an execute block is rejected
- This prevents patterns like: `execute { execute { } }`

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

### IntelliJ Coding Principles for Scripts

When writing scripts for execution, follow these IntelliJ Platform best practices:

#### Threading Model

1. **Read/Write Actions are required** for PSI and VFS access:
   ```kotlin
   execute { ctx ->
       // Reading PSI requires a read action
       val psiFile = readAction {
           PsiManager.getInstance(ctx.project).findFile(virtualFile)
       }

       // Modifying documents requires a write action
       writeAction {
           document.setText("new content")
       }
   }
   ```

2. **Smart Mode**: Many APIs require indices to be built. Use `ctx.waitForSmartMode()`:
   ```kotlin
   execute { ctx ->
       ctx.waitForSmartMode()  // Wait for indexing

       // Now safe to use index-dependent APIs
       val classes = readAction {
           JavaPsiFacade.getInstance(ctx.project)
               .findClasses("com.example.MyClass", GlobalSearchScope.allScope(ctx.project))
       }
   }
   ```

#### Common Patterns

1. **Getting services**:
   ```kotlin
   // Project services
   val fileEditorManager = FileEditorManager.getInstance(ctx.project)
   val psiManager = PsiManager.getInstance(ctx.project)

   // Application services
   val vfsManager = VirtualFileManager.getInstance()
   val app = ApplicationManager.getApplication()
   ```

2. **Working with files**:
   ```kotlin
   execute { ctx ->
       // Find a file
       val vFile = LocalFileSystem.getInstance()
           .findFileByPath("/path/to/file.kt")

       // Get PSI
       val psiFile = readAction {
           PsiManager.getInstance(ctx.project).findFile(vFile!!)
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
   execute { ctx ->
       val myListener = object : FileEditorManagerListener {
           override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
               ctx.println("Opened: ${file.name}")
           }
       }

       // Register with the context's disposable for automatic cleanup
       ctx.project.messageBus.connect(ctx.disposable)
           .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, myListener)
   }
   ```

#### Error Handling

- Scripts run in a supervised coroutine scope
- Exceptions are caught and reported as ERROR status
- Use `ctx.logError()` for expected error conditions
- Avoid catching `ProcessCanceledException` - let it propagate

## Testing

### Unit Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "*McpServerIntegrationTest*"
./gradlew test --tests "*ClaudeCliIntegrationTest*"
./gradlew test --tests "*SteroidsMcpToolsetTest*"
```

### Integration Tests

The project includes integration tests that verify MCP server functionality:

**Test Files:**
- `McpServerIntegrationTest.kt` - Tests MCP server using Ktor client and MCP SDK
- `ClaudeCliIntegrationTest.kt` - Tests Claude Code CLI integration (requires `claude` command)
- `SteroidsMcpToolsetTest.kt` - Direct toolset tests

**Shell Scripts** (in `integration-test/`):
- `test-sse-tools.sh` - Tests SSE transport directly via curl
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

## Configuration

- `build.gradle.kts`: Build configuration
- `gradle.properties`: IntelliJ platform version
- `settings.gradle.kts`: Project name

## Documentation

- [CLAUDE.md](CLAUDE.md) - Guidance for Claude Code
- [Plan.md](Plan.md) - Implementation plan
- [Suggestions.md](Suggestions.md) - Design suggestions
- [Discussions.md](Discussions.md) - Design decisions
