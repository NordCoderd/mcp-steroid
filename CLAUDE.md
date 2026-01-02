# CLAUDE.md, AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## MUST DO
Use IntelliJ for everything where you can, it makes you 100x professional!

You must never ignore warnings or errors, instead, figure out
how to fix that the right way.

Do not add test-only branches (for example, `ApplicationManager.getApplication().isUnitTestMode`);
make production and test paths match by using the correct IntelliJ actions (for example,
`writeIntentReadAction` or `writeCommandAction`).

We love tests, and tests must show the reality. A failing test is much must
better than a test that does not check the problems. Make sure you fix tests.

Check you changes to see if there are no new warnings added to the code.

## IntelliJ and Coroutines

Main APIs for synchronous return from blocking code:

1. runBlockingCancellable - The recommended approach for BGT (background thread). Blocks the current thread, executes coroutine, returns result synchronously. Cancellation-aware.
2. runWithModalProgressBlocking - For EDT usage. Shows modal progress, pumps event queue while waiting, returns synchronously.
3. RunSuspend - Low-level utility using Object.wait()/notifyAll() for custom bridging scenarios.


## Project Overview

IntelliJ MCP Steroid - an MCP server plugin for IntelliJ IDEA that exposes IDE APIs to LLM agents via Kotlin code execution.

## Key Documentation

- [README.md](README.md) - Full API documentation and architecture
- [AGENT-STEROID.md](AGENT-STEROID.md) - **IntelliJ API usage guide for LLM agents** - read this to become a power user
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

## Build Notes

- IDE distributions are cached under `.intellijPlatform/ides/IU-2025.3` (`intellijPlatform.caching.ides.enabled = true`).
- The build moves `plugins/fullLine/lib/modules/intellij.fullLine.yaml.jar` to `.bak` inside the local IDE cache to avoid plugin-structure warnings; the Gradle cache is not modified.
- Tests use `localPlugin(...)` for the Kotlin and Java plugins to align with the IDE classpath.

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

13. **Script definitions for IDE highlighting**: Uses `ScriptDefinitionsSource` extension point (K1/K2 compatible) to provide proper code highlighting for `.kts` files in the IDE.

## Source Structure

```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── server/
│   ├── SteroidsMcpServer.kt               # Ktor MCP server with HTTP transport
│   ├── SteroidsMcpServerStartupActivity.kt # Startup hook for server initialization
│   ├── ExecuteCodeToolHandler.kt          # steroid_execute_code tool
│   ├── ExecuteFeedbackToolHandler.kt      # steroid_execute_feedback tool
│   ├── ListProjectsToolHandler.kt         # steroid_list_projects tool
│   ├── PluginReloadToolHandler.kt         # Plugin reload tools
│   └── McpProgressReporter.kt             # Progress reporting interface
├── mcp/
│   ├── McpServerCore.kt                   # Core MCP server logic
│   ├── McpToolRegistry.kt                 # Tool registration and dispatch
│   ├── McpBuilders.kt                     # ToolCallResult builder pattern
│   └── McpProtocol.kt                     # MCP protocol types
├── execution/
│   ├── ExecutionManager.kt        # Orchestrates execution, returns ToolCallResult
│   ├── CodeEvalManager.kt         # Compiles scripts, captures execute {} lambdas
│   ├── ScriptExecutor.kt          # Runs captured blocks with timeout
│   ├── McpScriptScope.kt          # Interface bound to script engine (execute {} entry point)
│   ├── McpScriptContext.kt        # Interface for script context (project, output, utilities)
│   ├── McpScriptContextImpl.kt    # Implementation with output methods, waitForSmartMode
│   └── Diff.kt                    # Unified diff generation for review feedback
├── script/
│   ├── McpSteroidScriptDefinition.kt          # @KotlinScript annotation for .kts files
│   ├── McpSteroidScriptDefinitionsSource.kt   # ScriptDefinitionsSource for K1/K2 modes
│   └── McpSteroidScriptDefinitionsProvider.kt # ScriptDefinitionsProvider bridge API
├── reload/
│   ├── PluginReloadHelper.kt      # Utilities for checking plugin state and reload capability
│   └── PluginReloader.kt          # Schedules and performs plugin reload operations
├── review/
│   ├── ReviewManager.kt           # Human review workflow, diff generation
│   └── McpReviewNotificationProvider.kt  # Editor notification panel
└── storage/
    └── ExecutionStorage.kt        # Append-only file storage (no status tracking)
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

- **McpServerIntegrationTest.kt** - Tests MCP server HTTP handshake and tool flows:
  - Tests MCP protocol initialization and session management
  - Tests tool listing and invocation via HTTP
  - Tests system property reading via `steroid_execute_code`
  - Tests Claude CLI and Codex CLI Accept header compatibility
  - Tests graceful handling of unknown/stale session IDs

- **ClaudeCliIntegrationTest.kt** - Tests Claude Code CLI integration:
  - Uses Docker to run Claude CLI in isolation
  - Requires Docker and ANTHROPIC_API_KEY
  - Tests MCP server registration (`mcp add`, `mcp list`, `mcp remove`)
  - Tests tool discovery and invocation
  - Tests system property reading via MCP execute_code
  - Tests documented command-line workflow

- **CodexCliIntegrationTest.kt** - Tests OpenAI Codex CLI integration:
  - Uses Docker to run Codex CLI in isolation
  - Requires Docker and OPENAI_API_KEY
  - Tests MCP server TOML configuration
  - Tests tool discovery and invocation
  - Tests system property reading via MCP execute_code
  - Note: `codex mcp add` only supports stdio servers; HTTP uses TOML config

- **ScriptExecutorTest.kt** - Tests script execution with fast failure semantics:
  - Verifies errors return quickly (not waiting for timeout)
  - Tests compilation errors, runtime errors, missing execute blocks
  - Tests multiple execute blocks (FIFO order)

- **ExecutionManagerTest.kt** - Tests execution manager with progress reporting:
  - Tests successful execution with output collection
  - Tests error handling and timeout scenarios

- **SteroidsMcpToolsetTest.kt** - Tests the MCP tool execution flow:
  - Tests code execution via `ExecutionManager.executeWithProgress`
  - Tests output collection and error handling

- **DynamicPluginsTest.kt** - Tests DynamicPlugins API integration:
  - Tests plugin discovery via PluginManagerCore
  - Tests DynamicPlugins.checkCanUnloadWithoutRestart API
  - Tests PathManager log path accessibility
  - Tests PluginReloadHelper utility methods

### Shell Scripts (integration-test/)

- **test-sse-tools.sh** - Tests SSE transport via curl:
  ```bash
  ./integration-test/test-sse-tools.sh [PORT]
  ```

- **run-test.sh** - Automated Claude CLI test
- **manual-test.sh** - Interactive Claude CLI test

### Test Dependencies

The Kotlin and Java plugins are loaded from the local IntelliJ distribution via `localPlugin(...)` to keep the test classpath aligned with the IDE and avoid bundled plugin scan warnings. Without the Kotlin plugin, `IdeScriptEngineManager.getEngineByFileExtension("kts", null)` returns null.

### Test Patterns

- Tests should complete within 10 seconds (fast failure)
- Use `timeoutRunBlocking(10.seconds)` or similar for coroutine tests
- Script engine not available is a valid test outcome (check `ToolCallResult.isError`)
- All assertions should handle both success and error cases gracefully
- Tests use `ExecutionManager.executeWithProgress(ExecCodeParams)` which returns `ToolCallResult`

### Test Helper Pattern

For tests, create `ExecCodeParams` using a helper:

```kotlin
private fun testExecParams(code: String, timeout: Int = 30) = ExecCodeParams(
    taskId = "test-task",
    code = code,
    reason = "test",
    timeout = timeout,
    rawParams = buildJsonObject { }
)

// Usage
val result = manager.executeWithProgress(testExecParams(code))
assertTrue(!result.isError)
```

## Key Types

### ExecCodeParams
Parameters for `steroid_execute_code` tool:
- `taskId` - Groups related executions
- `code` - Kotlin code to execute
- `reason` - Human readable reason
- `timeout` - Execution timeout in seconds
- `rawParams` - Original JSON parameters

### ToolCallResult
MCP-native result type:
- `content` - List of `ContentItem` (text, images, etc.)
- `isError` - Whether execution failed
- Use `ToolCallResult.builder()` to construct

### ExecutionResultBuilder
Interface for collecting output during execution:
- `logMessage(message)` - Add text to output
- `logProgress(message)` - Report progress
- `logException(message, throwable)` - Report error with stack trace
- `reportFailed(message)` - Mark execution as failed

### McpScriptContext
Context provided inside `execute { }` blocks:
- `project` - IntelliJ Project
- `params` - Original tool parameters (JsonElement)
- `disposable` - Parent Disposable for cleanup
- `println(vararg)`, `printJson(obj)`, `printException(msg, t)`, `progress(msg)`
- `suspend fun waitForSmartMode()`

## Configuration

- `gradle.properties`: Contains `platformVersion` for IntelliJ version
- `build.gradle.kts`: Plugin configuration using `intellijPlatform` DSL
- Registry keys:
  - `mcp.steroids.server.port`: MCP server port (default: 6315, use 0 for dynamic)
  - `mcp.steroids.review.mode`: `ALWAYS` (default), `TRUSTED`, `NEVER`
  - `mcp.steroids.review.timeout`: Review timeout in seconds
  - `mcp.steroids.execution.timeout`: Script execution timeout

### Script Preprocessing (CodeButcher)

User-submitted scripts are preprocessed by `CodeButcher.wrapWithImports()` before compilation:

1. **Import extraction**: User imports (including `; import ...` patterns) are extracted from code
2. **Default imports**: Standard IntelliJ/Kotlin imports are added
3. **Import merging**: User imports are placed after default imports, before the `execute` binding
4. **Code assembly**: The rest of user code follows

This preprocessing is critical - imports MUST appear at the top of Kotlin scripts. Placing imports after code statements causes "incomplete code" errors.

### Kotlin Daemon Management

The `KotlinDaemonManager` service handles Kotlin daemon lifecycle issues:

**"Service is dying" errors**: The daemon can enter a dying state during shutdown while still being discoverable. The plugin detects this and can retry or force-restart the daemon.

**Daemon directory locations**:
- macOS: `~/Library/Application Support/kotlin/daemon`
- Windows: `%LOCALAPPDATA%/kotlin/daemon`
- Linux: `~/.kotlin/daemon`

**Note**: Earlier documentation incorrectly attributed "incomplete code" errors to daemon classpath issues. The actual cause was the script preprocessing bug (import merging) - now fixed in `CodeButcher.kt`.


## IntelliJ Platform Coding Principles

When contributing to this plugin, follow these IntelliJ Platform best practices:

### Threading Model

1. **Never block the EDT (Event Dispatch Thread)**:
   - UI updates must happen on EDT
   - Long operations must run on background threads
   - Use `Dispatchers.EDT` for EDT, `Dispatchers.IO` or `Dispatchers.Default` for background

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
   // Project-level service
   val storage = project.service<ExecutionStorage>()

   // Application-level service (no project needed)
   val skillRef = service<SkillReference>()
   val mcpServer = service<SteroidsMcpServer>()
   ```

2. **Avoid storing project references statically** - leads to memory leaks

3. **Use `@Service` annotation with correct level**:
   - `Service.Level.PROJECT` for project-scoped services
   - `Service.Level.APP` for application-scoped services

4. **Application services can reference other app services**:
   ```kotlin
   @Service(Service.Level.APP)
   class SkillReference {
       // Lazy access to another app service
       private val mcpServer: SteroidsMcpServer
           get() = SteroidsMcpServer.getInstance()

       val skillUrl: String
           get() = "http://localhost:${mcpServer.port}/skill.md"

       companion object {
           fun getInstance(): SkillReference = service()
       }
   }
   ```

5. **Pattern: Service with companion getInstance()**:
   ```kotlin
   @Service(Service.Level.APP)
   class MyService {
       // ... service implementation

       companion object {
           fun getInstance(): MyService = service()
       }
   }

   // Usage
   val svc = MyService.getInstance()
   // or
   val svc = service<MyService>()
   ```

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

## Environment Constraints

### Command Line Tools Not Available

The following commands are **NOT available** on this system:
- `timeout` - Do not use for command timeouts
- `gtimeout` - Do not use for command timeouts

For long-running commands, use Gradle's built-in timeout mechanisms or the Bash tool's `timeout` parameter instead.

## Kotlin Script Definitions (IDE Highlighting)

The plugin provides script definitions to enable proper code highlighting for `.kts` files in the IDE. This ensures IntelliJ APIs are recognized without error highlighting.

### Architecture

The Kotlin plugin supports two extension points for script definitions:

1. **`org.jetbrains.kotlin.scriptDefinitionsSource`** - Primary extension point for both K1 and K2 modes
2. **`org.jetbrains.kotlin.scriptDefinitionsProvider`** - Bridge API (loaded by `BridgeScriptDefinitionsContributor`)

**Important**: In K2 mode (IntelliJ 2025.3+), `scriptDefinitionsProvider` extensions registered via optional dependency configs may not load properly. Always implement `ScriptDefinitionsSource` directly for K2 compatibility.

### Implementation Files

- **`McpSteroidScriptDefinitionsSource.kt`** - Primary implementation of `ScriptDefinitionsSource`:
  - Provides `ScriptDefinition` with default imports, classpath, and `execute` binding
  - Works in both K1 and K2 modes
  - Registered via `kotlin-scripting.xml`

- **`McpSteroidScriptDefinition.kt`** - `@KotlinScript` annotated class:
  - Defines `McpSteroidScriptCompilationConfiguration` with imports and dependencies
  - Uses `dependenciesFromClassContext(McpScriptContext::class, wholeClasspath = true)` for full IntelliJ classpath

- **`McpSteroidScriptDefinitionsProvider.kt`** - Bridge API implementation:
  - Returns `McpSteroidScript` class name and plugin classpath
  - Kept for K1 mode compatibility

### Extension Registration

`src/main/resources/META-INF/kotlin-scripting.xml`:
```xml
<extensions defaultExtensionNs="org.jetbrains.kotlin">
    <scriptDefinitionsSource
            id="McpSteroidScriptDefinitionsSource"
            implementation="com.jonnyzzz.intellij.mcp.script.McpSteroidScriptDefinitionsSource"/>
    <scriptDefinitionsProvider
            implementation="com.jonnyzzz.intellij.mcp.script.McpSteroidScriptDefinitionsProvider"/>
</extensions>
```

### Default Imports Provided

Scripts automatically have access to:
- `com.intellij.openapi.project.*`
- `com.intellij.openapi.application.*` (including `readAction`, `writeAction`)
- `com.intellij.openapi.vfs.*`
- `com.intellij.openapi.editor.*`
- `com.intellij.openapi.fileEditor.*`
- `com.intellij.openapi.command.*`
- `com.intellij.psi.*`
- `kotlinx.coroutines.*`

### Troubleshooting Script Highlighting

If `.kts` files show errors:
1. Ensure Kotlin plugin is installed and enabled
2. Restart IDE after plugin installation/update
3. Check `Help > Diagnostic Tools > Activity Log` for script definition loading errors
4. Verify plugin is loaded: `Settings > Plugins > Installed > MCP Steroid`

## Plugin Deployment

### Deploy to Local IntelliJ Instance

```bash
# Build and deploy to IntelliJ 253
./gradlew deployPluginLocallyTo253

# This deploys to: ~/intellij-253/config/plugins/intellij-mcp-steroid/
```

## Committing Changes

When work is complete, commit changes with descriptive messages:

```bash
# Check status
git status
git diff

# Stage and commit
git add -A
git commit -m "$(cat <<'EOF'
Brief description of changes

- Detailed point 1
- Detailed point 2

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

### Commit Guidelines

1. **Atomic commits**: Each commit should represent a single logical change
2. **Descriptive messages**: Explain what and why, not just how
3. **Test before commit**: Run `./gradlew test` to verify changes
4. **Build verification**: Run `./gradlew build` to ensure the plugin builds

## Adding New MCP Tools

To add a new MCP tool:

1. **Create a handler class** in `server/` package:
   ```kotlin
   @Service(Service.Level.APP)
   class MyToolHandler {
       fun register(server: McpServerCore) {
           server.toolRegistry.registerTool(
               name = "steroid_my_tool",
               description = "Description for LLM",
               inputSchema = buildJsonObject { /* JSON schema */ },
               ::handle
           )
       }

       private suspend fun handle(context: ToolCallContext): ToolCallResult {
           // Access parameters via context.params.arguments
           // Return ToolCallResult.builder().addTextContent("...").build()
       }
   }
   ```

2. **Register in SteroidsMcpServer.kt**:
   ```kotlin
   service<MyToolHandler>().register(server)
   ```

3. **Use NoOpProgressReporter** for tests that don't need MCP progress:
   ```kotlin
   val result = manager.executeWithProgress(params, NoOpProgressReporter)
   ```

## Writing Tests

For execution tests:

```kotlin
class MyTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
    }

    fun testSomething(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()
        val result = manager.executeWithProgress(
            ExecCodeParams(
                taskId = "test",
                code = "execute { println(\"hello\") }",
                reason = "test",
                timeout = 30,
                rawParams = buildJsonObject { }
            ),
            NoOpProgressReporter
        )
        // Check result.isError and result.content
    }
}
```
