# intellij-mcp-steroid

An MCP (Model Context Protocol) Server for IntelliJ IDEA that provides a Kotlin/Groovy console interface, allowing LLM agents to execute code directly within the IDE's runtime environment.

## Overview

This IntelliJ plugin starts an MCP server that exposes the IDE's internal APIs to LLM agents. The key capability is executing Kotlin or Groovy code in the context of IntelliJ's own classpath, giving agents programmatic access to the IDE's full functionality.

**Primary Use Case**: An LLM agent submits code that runs inside IntelliJ, accessing project structure, PSI (Program Structure Interface), refactoring tools, VFS (Virtual File System), and any other IDE APIs.

## MCP Server API

The MCP server listens on port **11993** and provides the following tools:

### `list_projects`
Lists all currently open projects with their directories.

**Response**: Array of `{ name: string, path: string }`

### `execute_code`
Executes Kotlin or Groovy code in the IDE's runtime context.

**Parameters**:
- `project_path` (required): Path to the project directory (must match an open project)
- `language`: `"kotlin"` (default) or `"groovy"`
- `code`: The code to execute

**Execution Model**:
- Code runs as a blocking function in the IDE process
- The call completes when the function exits
- Output and errors are captured and returned
- Compilation errors are reported with line numbers

**Entry Point Semantics**:
```kotlin
// The submitted code must define a `main` function that receives the context
fun main(ctx: McpScriptContext) {
    // Your code here
    // Access the current project via ctx.project
    // Register new MCP commands via ctx.registerCommand(...)
}
```

### `read_slot` / `write_slot`
Named storage slots for persisting data between executions.

**Parameters**:
- `project_path` (required): Path to the project directory
- `slot_name`: Name of the slot
- `value` (write only): String value to store

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

    /** Register a new MCP command that persists and runs in background */
    fun registerCommand(
        name: String,
        description: String,
        parameters: Map<String, ParameterSpec>,
        handler: (Map<String, Any?>) -> Any?
    )

    /** Unregister a previously registered command */
    fun unregisterCommand(name: String)

    /** Read from a named slot */
    fun readSlot(name: String): String?

    /** Write to a named slot */
    fun writeSlot(name: String, value: String)
}
```

## Code Execution Architecture

1. **Code Submission**: MCP server receives code via `execute_code` tool
2. **Storage**: Code is saved to `.idea/mcp-run/` folder in the project
3. **Classloader Creation**: A dedicated classloader is created with:
   - IntelliJ platform classes as parent
   - Configurable plugin dependencies (via `list_plugins` / `add_plugin_dependency` tools)
4. **Compilation**: Code is compiled using Java Compiler API (`javax.tools.JavaCompiler`) running in the execution classloader
5. **Execution**: Compiled classes are loaded and `main(McpScriptContext)` is invoked
6. **Result**: Output, return value, or errors are returned to the caller

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
