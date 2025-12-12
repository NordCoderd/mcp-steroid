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
- **Kotlin**: 2.2.21
- **Java Toolchain**: 21
- **IntelliJ Platform**: 2025.3+ (sinceBuild: 252.1)
- **IntelliJ Platform Gradle Plugin**: 2.10.5
- **MCP Server**: Kotlin MCP SDK 0.8.1 with Ktor (CIO engine)
- **Transport**: HTTP at `http://localhost:<port>/mcp` with CORS support
- **Testing**: IntelliJ 253 pattern with `timeoutRunBlocking`
- **Serialization**: kotlinx.serialization for JSON

## Architecture

This is an IntelliJ Platform plugin with a standalone MCP server using Kotlin MCP SDK:

- **Plugin descriptor**: `src/main/resources/META-INF/plugin.xml`
- **MCP Server**: `server/SteroidsMcpServer.kt` - Ktor-based MCP server with SSE transport
- **Progress Reporting**: `server/ThrottledProgressReporter.kt` - Flow-based 1-second throttled progress
- **Execution**: `ExecutionManager.kt` - manages script execution lifecycle
- **Code Evaluation**: `CodeEvalManager.kt` - handles script compilation and lambda capture
- **Script Execution**: `ScriptExecutor.kt` - runs captured execute blocks with timeout
- **Script Context**: `McpScriptContext.kt` / `McpScriptContextImpl.kt` - runtime context for scripts
- **Script Scope**: `McpScriptScope.kt` - bound to script engine, provides `execute { }` entry point
- **Storage**: `ExecutionStorage.kt` - append-only file-based storage (for logging/debugging)
- **Review**: `ReviewManager.kt` - human review workflow

### Key Design Decisions

1. **Standalone MCP server**: Uses Kotlin MCP SDK with Ktor for SSE transport. No dependency on IntelliJ's built-in MCP plugin. Server URL written to `.idea/mcp-steroids.txt` for discovery.

2. **Synchronous request-response**: Execution happens within MCP request scope. No polling - output returned directly in response.

3. **Throttled progress**: Progress messages sampled at 1-second intervals using Flow to avoid overloading MCP connections.

4. **Coroutines over blocking**: All code in `execute {}` block runs as suspend functions. Never use `runBlocking` in production code. Use `coroutineScope` for script execution.

5. **Read/Write Actions**: Not part of McpScriptContext. LLM-generated code should use IntelliJ's coroutine-aware APIs directly:
   ```kotlin
   import com.intellij.openapi.application.readAction
   import com.intellij.openapi.application.writeAction
   ```

6. **Append-only storage**: Files in `.idea/mcp-run/` are never deleted, only appended to (used for logging/debugging).

7. **Review with feedback**: When user rejects code, they can edit it first. The edited code and unified diff are returned to help LLM understand the feedback.

8. **Fast failure**: Compilation errors and script engine unavailability are reported immediately (no waiting for timeout).

9. **FIFO execution**: Multiple `execute {}` blocks are collected and run in order. Any failure marks the entire job complete.

10. **Disposable lifecycle**: Context has a `disposable` property for resource cleanup. The coroutine completion triggers `Disposer.dispose()`.

11. **Scope disposal**: After script evaluation, the scope is marked disposed to prevent nested `execute { execute { } }` patterns.

12. **Two-phase execution**: First phase compiles script and captures execute blocks (`CodeEvalManager`), second phase runs them with timeout (`ScriptExecutor`).

## Source Structure

```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── server/
│   ├── SteroidsMcpServer.kt               # Ktor MCP server with SSE transport
│   ├── SteroidsMcpServerStartupActivity.kt # Startup hook for server initialization
│   └── ThrottledProgressReporter.kt       # Flow-based progress throttling (1 sec sampling)
├── execution/
│   ├── ExecutionManager.kt        # Manages execution lifecycle, orchestrates review + execution
│   ├── CodeEvalManager.kt         # Compiles scripts, captures execute {} lambdas
│   ├── ScriptExecutor.kt          # Runs captured blocks with timeout and coroutine scope
│   ├── McpScriptScope.kt          # Interface bound to script engine (execute {} entry point)
│   ├── McpScriptContext.kt        # Interface for script context (project, output, utilities)
│   ├── McpScriptContextImpl.kt    # Implementation with output methods, waitForSmartMode, progress
│   └── McpScriptContextEx.kt      # Extended interface with reflection helpers
├── review/
│   ├── ReviewManager.kt           # Human review workflow, diff generation
│   └── McpReviewNotificationProvider.kt  # Editor notification panel
└── storage/
    └── ExecutionStorage.kt        # File-based storage, data classes (OutputMessage, ExecutionResult, etc.)
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
./gradlew test --tests "*McpServerIntegrationTest*"
./gradlew test --tests "*ClaudeCliIntegrationTest*"
```

### Test Files

- **McpServerIntegrationTest.kt** - Tests MCP server service availability

- **ClaudeCliIntegrationTest.kt** - Tests Claude Code CLI integration:
  - Uses Docker to run Claude CLI in isolation
  - Requires Docker and ANTHROPIC_API_KEY
  - Tests MCP server registration and connectivity
  - Note: MCP tools not supported in Claude CLI print mode (-p)

- **CodexCliIntegrationTest.kt** - Tests OpenAI Codex CLI integration:
  - Uses Docker to run Codex CLI in isolation
  - Requires Docker and OPENAI_API_KEY
  - Tests MCP tool discovery and invocation

- **ScriptExecutorTest.kt** - Tests script execution with fast failure semantics:
  - Verifies errors return quickly (not waiting for timeout)
  - Tests compilation errors, runtime errors, missing execute blocks

- **ExecutionManagerTest.kt** - Tests execution manager with progress reporting:
  - Tests successful execution with output collection
  - Tests error handling and timeout scenarios

### Shell Scripts (integration-test/)

- **test-sse-tools.sh** - Tests SSE transport via curl:
  ```bash
  ./integration-test/test-sse-tools.sh [PORT]
  ```

- **run-test.sh** - Automated Claude CLI test
- **manual-test.sh** - Interactive Claude CLI test

### Test Dependencies

The Kotlin plugin (`org.jetbrains.kotlin`) is added as a bundled dependency to enable Kotlin script engine in tests. Without it, `IdeScriptEngineManager.getEngineByFileExtension("kts", null)` returns null.

### Test Patterns

- Tests should complete within 10 seconds (fast failure)
- Use `timeoutRunBlocking(10.seconds)` or similar for coroutine tests
- Script engine not available is a valid test outcome (ERROR status)
- All assertions should handle both SUCCESS and ERROR cases gracefully
- Tests use `executeWithProgress()` API which returns output directly

## Configuration

- `gradle.properties`: Contains `platformVersion` for IntelliJ version
- `build.gradle.kts`: Plugin configuration using `intellijPlatform` DSL
- Registry keys:
  - `mcp.steroids.server.port`: MCP server port (default: 6315, use 0 for dynamic)
  - `mcp.steroids.review.mode`: `ALWAYS` (default), `TRUSTED`, `NEVER`
  - `mcp.steroids.review.timeout`: Review timeout in seconds
  - `mcp.steroids.execution.timeout`: Script execution timeout

## IntelliJ Platform Coding Principles

When contributing to this plugin, follow these IntelliJ Platform best practices:

### Threading Model

1. **Never block the EDT (Event Dispatch Thread)**:
   - UI updates must happen on EDT
   - Long operations must run on background threads
   - Use `Dispatchers.Main` for EDT, `Dispatchers.IO` or `Dispatchers.Default` for background

2. **Read/Write Actions**:
   - Access to PSI and VFS requires read actions
   - Modifications require write actions
   - Use coroutine-aware APIs: `readAction { }` and `writeAction { }` from `com.intellij.openapi.application`
   - See [IntelliJ Threading Rules](https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html)

3. **Smart Mode vs Dumb Mode**:
   - During indexing, IDE is in "dumb mode" - many APIs unavailable
   - Use `DumbService.isDumb(project)` to check
   - Use `DumbService.getInstance(project).smartInvokeLater { }` for operations requiring indices
   - Our `waitForSmartMode()` handles this for scripts

### Coroutine Patterns

1. **Service-injected CoroutineScope**:
   ```kotlin
   @Service(Service.Level.PROJECT)
   class MyService(
       private val project: Project,
       coroutineScope: CoroutineScope  // Injected by platform
   )
   ```

2. **Child scopes for cleanup**:
   ```kotlin
   val childScope = parentScope.childScope("name", SupervisorJob())
   ```

3. **Cancelation via Disposable**:
   ```kotlin
   job.cancelOnDispose(disposable)
   Disposer.register(parent, child)
   ```

4. **Sequential execution**:
   ```kotlin
   Dispatchers.Default.limitedParallelism(1)
   ```

### Disposable Lifecycle

1. **Always register disposables with a parent**:
   ```kotlin
   Disposer.register(parentDisposable, childDisposable)
   ```

2. **Use Disposer.newDisposable() for named disposables**:
   ```kotlin
   val myDisposable = Disposer.newDisposable("my-execution-$id")
   ```

3. **Clean up in dispose()**:
   - Cancel jobs
   - Close resources
   - Unregister listeners

### Project Services

1. **Get services via extension function**:
   ```kotlin
   val storage = project.service<ExecutionStorage>()
   ```

2. **Avoid storing project references statically** - leads to memory leaks

3. **Use `@Service` annotation with correct level**:
   - `Service.Level.PROJECT` for project-scoped services
   - `Service.Level.APP` for application-scoped services

### Error Handling

1. **Use `ControlFlowException` for expected control flow**
2. **Never catch `ProcessCanceledException`** - rethrow it
3. **Log errors appropriately**:
   ```kotlin
   private val log = Logger.getInstance(MyClass::class.java)
   log.info("message")
   log.warn("message", exception)
   log.error("message", exception)
   ```

### References

- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/intellij/)
- [IntelliJ Community Source](https://github.com/JetBrains/intellij-community)
- [Threading Rules](https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html)
- [Coroutines in IntelliJ](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html)
- [Disposer and Disposable](https://plugins.jetbrains.com/docs/intellij/disposers.html)
