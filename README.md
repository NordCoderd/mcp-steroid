# intellij-mcp-steroid

An MCP (Model Context Protocol) Server for IntelliJ IDEA that provides a Kotlin/Groovy console interface, allowing LLM agents to execute code directly within the IDE's runtime environment.

## Overview

This IntelliJ plugin starts an MCP server that exposes the IDE's internal APIs to LLM agents. The key capability is executing Kotlin or Groovy code in the context of IntelliJ's own classpath, giving agents programmatic access to the IDE's full functionality.

**Primary Use Case**: An LLM agent submits code that runs inside IntelliJ, accessing project structure, PSI (Program Structure Interface), refactoring tools, VFS (Virtual File System), and any other IDE APIs.

## MCP Server API

The MCP server uses HTTP/TCP transport on port **11993** (default, configurable via IntelliJ Registry). If the port is busy, the server automatically allocates another available port.

### Connecting via stdio

For standard MCP clients that expect stdio transport, use a proxy:
```bash
# Example: socat proxy from stdio to TCP
socat - TCP:localhost:11993
```

### Available Tools

### `list_projects`
Lists all currently open projects with their directories.

**Response**: Array of `{ name: string, path: string }`

### `execute_code`
Executes Kotlin or Groovy code in the IDE's runtime context.

**Parameters**:
- `project_path` (required): Path to the project directory (must match an open project)
- `language`: `"kotlin"` (default) or `"groovy"`
- `code`: The code to execute
- `plugins`: List of plugin IDs to include in classpath (default: all enabled plugins)
- `timeout`: Execution timeout in seconds (default: 60)

**Execution Model**:
- Code runs as a suspend function in the IDE process
- The call blocks until completion, timeout, or cancellation
- Output is streamed as it appears
- Compilation and runtime errors are reported separately with details
- Requests are executed sequentially (queued)

**Entry Point Semantics**:
```kotlin
// The submitted code must define a suspend `main` function that receives the context
suspend fun main(ctx: McpScriptContext) {
    // Your code here
    // Access the current project via ctx.project
    // Output via ctx.println(), ctx.printJson(), ctx.log()
    // Register new MCP commands via ctx.registerCommand(...)
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

Package declaration is optional.

### `cancel_execution`
Cancels a running code execution.

**Parameters**:
- `execution_id`: ID returned from execute_code

### `read_slot` / `write_slot`
Named storage slots for persisting data between executions. Slots are project-scoped.

**Parameters**:
- `project_path` (required): Path to the project directory
- `slot_name`: Name of the slot
- `value` (write only): String or JSON value to store

**Note**: Slots store strings only. For structured data, serialize to JSON. Objects cannot be stored directly due to classloader isolation.

### `list_commands`
Lists dynamically registered MCP commands (registered via `McpScriptContext.registerCommand()`).

### `call_command`
Invokes a dynamically registered command.

## McpScriptContext API

The `McpScriptContext` class is provided to executed code and offers:

```kotlin
interface McpScriptContext {
    /** The IntelliJ Project this execution is associated with */
    val project: com.intellij.openapi.project.Project

    // === Output Methods ===

    /** Print text output (returned to LLM) */
    fun println(message: Any?)
    fun print(message: Any?)

    /** Serialize object to JSON and output (uses Gson/Jackson) */
    fun printJson(obj: Any?)

    /** Log messages (info/warn/error levels) */
    fun logInfo(message: String)
    fun logWarn(message: String)
    fun logError(message: String, throwable: Throwable? = null)

    // === Slot Storage ===

    /** Read from a named slot (project-scoped) */
    fun readSlot(name: String): String?

    /** Write to a named slot (project-scoped) */
    fun writeSlot(name: String, value: String)

    // === Dynamic Commands ===

    /** Register a new MCP command that persists until plugin restart */
    fun registerCommand(
        name: String,
        description: String,
        handler: suspend (parameters: String) -> String
    )

    /** Unregister a previously registered command */
    fun unregisterCommand(name: String)

    // === Reflection Helpers ===

    /** List all registered services */
    fun listServices(): List<String>

    /** List all extension points */
    fun listExtensionPoints(): List<String>

    /** Describe a class (methods, fields, annotations) */
    fun describeClass(className: String): String
}
```

**Threading**: Scripts run as suspend functions. For UI operations or write actions:
```kotlin
suspend fun main(ctx: McpScriptContext) {
    // Read action (thread-safe read)
    readAction {
        val psiFile = PsiManager.getInstance(ctx.project).findFile(virtualFile)
    }

    // Write action (modifies PSI/VFS)
    writeAction {
        psiFile.add(newElement)
    }

    // EDT operations (UI)
    withContext(Dispatchers.EDT) {
        // UI code here
    }
}
```

## Code Execution Architecture

1. **Code Submission**: MCP server receives code via `execute_code` tool
2. **Review** (if enabled): Code opened in editor, waits for human approval
3. **Storage**: Code saved to `.idea/mcp-run/<timestamp>-<hash>.kt`
4. **Classloader Creation**: Fresh classloader per execution with:
   - IntelliJ platform classes as parent
   - Specified plugin dependencies (default: all enabled plugins)
5. **Compilation**: Kotlin compiler (bundled) compiles code in the classloader context
6. **Execution**: `suspend fun main(McpScriptContext)` invoked via `runBlocking`
7. **Output Streaming**: Output streamed to LLM as it appears
8. **Cleanup**: Classloader released for GC after execution
9. **Result**: Compilation errors, runtime errors, or output returned with clear status

**Error Response Structure**:
```json
{
    "status": "compilation_error" | "runtime_error" | "success" | "timeout" | "cancelled",
    "output": "streamed output...",
    "errors": [
        {"line": 5, "message": "Unresolved reference: foo", "severity": "error"}
    ],
    "exception": {"type": "NullPointerException", "message": "...", "stackTrace": "..."}
}
```

## IntelliJ API Reference

For LLM agents working with this MCP server, refer to the IntelliJ Platform SDK:
- **Source Code**: https://github.com/intellij-community
- **Documentation**: https://plugins.jetbrains.com/docs/intellij/

Key packages:
- `com.intellij.openapi.project` - Project management
- `com.intellij.openapi.vfs` - Virtual File System
- `com.intellij.psi` - Program Structure Interface (code model)
- `com.intellij.openapi.application` - Application-level services
- `com.intellij.openapi.editor` - Editor APIs

## Runtime Reflection for API Discovery

**LLM agents should use reflection to discover available APIs at runtime.** IntelliJ's API surface is vast and varies by version and installed plugins. Instead of assuming an API exists, introspect first:

```kotlin
fun main(ctx: McpScriptContext) {
    // List methods on any class
    PsiManager::class.java.methods.forEach { method ->
        println("${method.name}(${method.parameterTypes.joinToString()})")
    }

    // Discover extension points
    Extensions.getRootArea().extensionPoints.forEach { ep ->
        println("Extension: ${ep.name}")
    }
}
```

Benefits:
- Self-documenting: explore what's actually available
- Version-agnostic: works across IntelliJ versions
- Plugin-aware: discovers APIs from installed plugins
- Reduces hallucination: agent sees real API, not imagined one

## Code Review Mode

By default, all submitted code is opened in the IDE editor for human review before execution. The MCP call blocks until the human decides.

**Review modes**:
- `ALWAYS` (default): All code requires human approval
- `TRUSTED`: Auto-approve code matching trusted patterns
- `NEVER`: Auto-execute all code (use with caution)

**Workflow**:
1. Code submitted → opened in editor with review panel
2. Human reviews, can add comments/edits
3. Approve: code executes, result returned
4. Reject: rejection message returned, includes any edits/comments human made

See [Suggestions.md](Suggestions.md) for details on the review workflow and third-party verification integration.

## Special Case: IntelliJ Project Detection

When the MCP server detects it's running within an IntelliJ-based IDE project (intellij-community or derived), it provides enhanced context to the LLM including:
- Available internal APIs specific to IDE development
- Plugin extension points
- Testing utilities for plugin development

## Project Structure

This is an IntelliJ IDEA plugin project built with:
- **Gradle**: 8.11.1 (latest stable)
- **Kotlin**: 2.1.0 (latest stable)
- **IntelliJ Platform**: 2024.2.4 (configurable via `gradle.properties`)
- **Java Toolchain**: 21

## Building and Running

```bash
# Build the plugin
./gradlew build

# Run the plugin in IntelliJ (starts MCP server on port 11993)
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# Run tests
./gradlew test
```

## Configuration

- `build.gradle.kts`: Main build script with explicit plugin configuration
- `settings.gradle.kts`: Project name configuration
- `gradle.properties`: Build properties and IntelliJ platform version
- `gradle/wrapper/`: Gradle wrapper for consistent builds

To target a different IntelliJ Platform version, update `platformVersion` in `gradle.properties`.

## Documentation

- [CLAUDE.md](CLAUDE.md) - Guidance for Claude Code
- [Plan.md](Plan.md) - Implementation plan
- [Suggestions.md](Suggestions.md) - Open questions and design suggestions
- [Discussions.md](Discussions.md) - Design discussions and decisions
