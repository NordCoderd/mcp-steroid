# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ MCP Steroid - an MCP server plugin for IntelliJ IDEA that exposes IDE APIs to LLM agents via Kotlin code execution.

## Key Documentation

- [README.md](README.md) - Full API documentation and architecture
- [Plan.md](Plan.md) - Implementation plan and phases
- [Suggestions.md](Suggestions.md) - Open questions and design decisions
- [Discussions.md](Discussions.md) - Design discussions and decisions from Q&A sessions

## Build Commands

```bash
# Build the plugin
./gradlew build

# Run plugin in a sandboxed IntelliJ instance
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin
```

## Technology Stack

- **Gradle**: 8.11.1 with Kotlin DSL
- **Kotlin**: 2.1.0
- **Java Toolchain**: 21
- **IntelliJ Platform**: 2025.3+ (configured in `gradle.properties`)
- **IntelliJ Platform Gradle Plugin**: 2.1.0
- **Testing**: IntelliJ 253 pattern with `timeoutRunBlocking`

## Architecture

This is an IntelliJ Platform plugin using the MCP toolset architecture:

- **Plugin descriptor**: `src/main/resources/META-INF/plugin.xml`
- **MCP Toolset**: `SteroidsMcpToolset.kt` - registers with `com.intellij.mcpServer`
- **Execution**: `ExecutionManager.kt` - manages script execution lifecycle
- **Script Context**: `McpScriptContext.kt` / `McpScriptContextImpl.kt` - runtime context for scripts
- **Storage**: `ExecutionStorage.kt` - append-only file-based storage
- **Review**: `ReviewManager.kt` - human review workflow

### Key Design Decisions

1. **Coroutines over blocking**: All code in `execute {}` block runs as suspend functions. Never use `runBlocking` in production code. Use `coroutineScope` for script execution.

2. **Read/Write Actions**: Not part of McpScriptContext. LLM-generated code should use IntelliJ's coroutine-aware APIs directly:
   ```kotlin
   import com.intellij.openapi.application.readAction
   import com.intellij.openapi.application.writeAction
   ```

3. **Append-only storage**: Files in `.idea/mcp-run/` are never deleted, only appended to.

4. **Review with feedback**: When user rejects code, they can edit it first. The edited code and unified diff are returned to help LLM understand the feedback.

5. **Fast failure**: Compilation errors and script engine unavailability are reported immediately (no waiting for timeout).

6. **FIFO execution**: Multiple `execute {}` blocks are collected and run in order. Any failure marks the entire job complete.

7. **Disposable lifecycle**: Context has a `disposable` property for resource cleanup. The coroutine completion triggers `Disposer.dispose()`.

8. **Scope disposal**: After script evaluation, the scope is marked disposed to prevent nested `execute { execute { } }` patterns.

## Source Structure

```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── SteroidsMcpToolset.kt          # MCP tool definitions (list_projects, execute_code, etc.)
├── execution/
│   ├── ExecutionManager.kt        # Manages execution lifecycle
│   ├── McpScriptContext.kt        # Interface for script context
│   ├── McpScriptContextImpl.kt    # Implementation with output methods
│   └── ScriptExecutor.kt          # Kotlin script compilation and execution
├── review/
│   ├── ReviewManager.kt           # Human review workflow, diff generation
│   └── McpReviewNotificationProvider.kt  # Editor notification panel
└── storage/
    └── ExecutionStorage.kt        # File-based storage, data classes
```

## Testing

Tests use IntelliJ 253 best practices:

```kotlin
fun testExample(): Unit = timeoutRunBlocking(30.seconds) {
    // coroutine test code
}
```

Run specific test class:
```bash
./gradlew test --tests "*ExecutionManagerTest*"
```

### Test Dependencies

The Kotlin plugin (`org.jetbrains.kotlin`) is added as a bundled dependency to enable Kotlin script engine in tests. Without it, `IdeScriptEngineManager.getEngineByFileExtension("kts", null)` returns null.

### Test Patterns

- Tests should complete within 10 seconds (fast failure)
- Use `timeoutRunBlocking(10.seconds)` or similar for coroutine tests
- Script engine not available is a valid test outcome (ERROR status)
- All assertions should handle both SUCCESS and ERROR cases gracefully

## Configuration

- `gradle.properties`: Contains `platformVersion` for IntelliJ version
- `build.gradle.kts`: Plugin configuration using `intellijPlatform` DSL
- Registry keys: `mcp.steroids.review.mode`, `mcp.steroids.review.timeout`, `mcp.steroids.execution.timeout`
