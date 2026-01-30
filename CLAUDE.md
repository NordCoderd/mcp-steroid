# CLAUDE.md, AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**IMPORTANT: Re-read this file often as it changes frequently. Instructions here override default behavior.**

Make sure you never include AI as co-author for commits. You must never mention AI in the commit mesasges too.

## MUST DO
Use IntelliJ MCP for everything where you can, it makes you 100x professional!

You must never ignore warnings or errors, instead, figure out how to fix that the right way.

Do not add test-only branches (for example, `ApplicationManager.getApplication().isUnitTestMode`);
make production and test paths match by using the correct IntelliJ actions (for example,
`writeIntentReadAction` or `writeCommandAction`).

We love tests, and tests must show the reality. A failing test is much must
better than a test that does not check the problems. Make sure you fix tests.

Check your changes to see if there are no new warnings added to the code.

You should log new ideas and tasks as GitHub issues, focus on the main goal. Iterate over tasks later.

## Workflow Best Practices

### IntelliJ API 

Avoid using `object` or singletons in the code, use IntelliJ services instead.

Prefer this instead of static `getInstance()` function
```
inline val serviceX: ServiceX get() = service()
inline val Project.serviceY: ServiceY get() = service()
```

### Test-First Approach

All bugs must be fixed using a test-first approach:
1. First, add a failing test that reproduces the bug
2. Then implement the fix to make the test pass
3. Tests must verify the fix works and prevent regression
4. Integration tests preferred for end-to-end verification

### Documentation as First-Class Citizen

- Documentation is key - maintain .md files
- Update documentation when implementation changes
- Keep README.md and docs/*.md consistent with actual implementation

### Feature Implementation Checklist

- [ ] Read and understand requirements
- [ ] Ask clarifying questions if ambiguous
- [ ] Write tests for new functionality
- [ ] Implement the feature
- [ ] Run Gradle build/test from the IDE via MCP run configurations (do not run tests from the shell)
- [ ] Deploy the plugin locally via `./gradlew deployPlugin`
- [ ] Test your changes with the IntelliJ MCP
- [ ] Review `./ai-tests/_INSTRUCTIONS.md` and add missing
- [ ] Play tasks from `./ai-tests`
- [ ] Update documentation and other .md files in the project
- [ ] Commit with descriptive message
- [ ] Verify tests pass
- [ ] Update related GitHub issues with status, and explain the changes made

### Bug Fix Requirements

1. Reproduce the problem with a test first
2. Fix the test, then verify the fix
3. Ensure no regressions in related functionality
4. Run ./ai-tests to verify the fix works correctly

### Code Quality

- Never ignore warnings or errors - fix them properly
- Check changes for new warnings
- Run compile and tests when work is done
- Avoid over-engineering - only add what's necessary

### Issue-Driven Development

**All tasks must be tracked as GitHub issues.** Use `gh` CLI for all issue management.

When working on this repository, follow this structured workflow:

1. **Log ALL tasks as GitHub issues**
   - Every task, bug, or improvement must have a GitHub issue
   - Use `gh issue create` with clear title and description
   - Include steps to reproduce for bug reports
   - Add acceptance criteria when possible

2. **Review open issues first**
   - Run `gh issue list` to see all open issues
   - Prioritize by impact and dependencies
   - Group related issues for efficient resolution

3. **Use issues to plan work**
   - Leave comments on issues with ideas and suggestions
   - Use `gh issue comment <number> --body "..."` to add thoughts
   - Document design decisions and alternatives considered
   - Update issue description if scope changes

4. **Work on issues one-by-one**
   - Focus on a single issue until it's resolved
   - Use the todo list to track progress within an issue
   - Mark issue as complete only when fully verified

5. **For each issue**:
   - Read and understand the issue description
   - Add a failing test that reproduces the problem
   - Implement the fix/feature
   - Run Gradle `build` in the IDE via MCP to verify
   - Close the issue with `gh issue close <number>`

6. **Create new issues for discovered work**
   - If you find unrelated issues during work, create new GitHub issues
   - Use `gh issue create` with clear title and description
   - Don't scope-creep current issue - defer to new issues

7. **Commit and verify frequently**
   - Commit logical changes together
   - Run tests after each significant change
   - Keep commits focused and atomic

### Testing Requirements

- **Every fixed issue must have a dedicated test** - when an issue is resolved, there should be a clear test that verifies the fix
- **Never fake tests** - it is acceptable to have a failing test; spend effort to fix the code, not to make the test pass artificially
- **Tests must reflect reality** - a failing test is better than a passing test that doesn't check the actual problem

### Code Review with IntelliJ MCP

Before completing work:
1. Use IntelliJ MCP (`steroid_execute_code`) to check for warnings and errors
2. Run code inspections on changed files
3. Resolve all warnings and errors - do not ignore them
4. Verify no new warnings were introduced

### Final Verification

Once all work is done:
1. Run Gradle `build` in the IDE via MCP to ensure everything compiles
2. Run Gradle `test` in the IDE via MCP to verify all tests pass
3. Check `ai-tests/` scenarios and ensure they work correctly
4. Verify the changes don't break existing functionality

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

These CLI snippets are task-name references; when acting as an agent, run Gradle tasks from the IDE via MCP (tests must not be run from the shell).

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

## Running Gradle Tasks via IDE (MCP)

Run all Gradle tests from the IDE using MCP-run configurations; do not run `./gradlew test` in a shell.

Example: create/run a Gradle run configuration for the `test` task (and optionally a single test via `--tests`):

```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType

val runManager = RunManager.getInstance(project)
val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
runConfig.name = "Gradle test (MCP)"
runConfig.settings.externalProjectPath = project.basePath
runConfig.settings.taskNames = listOf("test")
// Single test example:
// runConfig.settings.scriptParameters = "--tests \"*ExecutionManagerTest*\""
val settings = runManager.createConfiguration(runConfig, factory)
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```


To run `build`, set `taskNames = listOf("build")` in the same configuration pattern.
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
- **Code Evaluation**: `CodeEvalManager.kt` - handles script compilation and captures the script body as a runnable block
- **Script Execution**: `ScriptExecutor.kt` - runs the captured script body block with timeout
- **Script Context**: `McpScriptContext.kt` / `McpScriptContextImpl.kt` - runtime context for scripts
- **Storage**: `ExecutionStorage.kt` - append-only file-based storage (for logging/debugging)
- **Review**: `ReviewManager.kt` - human review workflow

### Key Design Decisions

1. **Standalone MCP server**: Uses Kotlin MCP SDK with Ktor for SSE transport. No dependency on IntelliJ's built-in MCP plugin. Description file written to `.idea/mcp-steroid.md`.

2. **Synchronous request-response**: Execution happens within MCP request scope. No polling - output returned directly in response.

3. **Throttled progress**: Progress messages sampled at 1-second intervals using Flow to avoid overloading MCP connections.

4. **Coroutines over blocking**: All code in the script body runs as suspend functions. Never use `runBlocking` in production code. Use `coroutineScope` for script execution.

5. **Read/Write Actions**: Built into McpScriptContext as suspend helpers (`readAction`, `writeAction`, `smartReadAction`). You can still import IntelliJ APIs directly when needed.

6. **Append-only storage**: Files in `.idea/mcp-steroid/` are never deleted, only appended to (used for logging/debugging).

7. **Review with feedback**: When user rejects code, they can edit it first. The edited code and unified diff are returned to help LLM understand the feedback.

8. **Fast failure**: Compilation errors and script engine unavailability are reported immediately (no waiting for timeout).

9. **Single-body execution**: The submitted script body is captured as a single block and executed with timeout handling.

10. **Disposable lifecycle**: Context has a `disposable` property for resource cleanup. The coroutine completion triggers `Disposer.dispose()`.

11. **Scope disposal**: After script evaluation, the scope is marked disposed to prevent nested execution patterns.

12. **Two-phase execution**: First phase compiles script and captures the script body (`CodeEvalManager`), second phase runs it with timeout (`ScriptExecutor`).

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
│   ├── ListWindowsToolHandler.kt          # steroid_list_windows tool
│   ├── OpenProjectToolHandler.kt          # steroid_open_project tool
│   ├── ActionDiscoveryToolHandler.kt      # steroid_action_discovery tool
│   ├── CapabilitiesToolHandler.kt         # steroid_capabilities tool
│   ├── VisionScreenshotToolHandler.kt     # steroid_take_screenshot tool
│   ├── VisionInputToolHandler.kt          # steroid_input tool
│   └── McpProgressReporter.kt             # Progress reporting interface
├── mcp/
│   ├── McpServerCore.kt                   # Core MCP server logic
│   ├── McpToolRegistry.kt                 # Tool registration and dispatch
│   ├── McpBuilders.kt                     # ToolCallResult builder pattern
│   └── McpProtocol.kt                     # MCP protocol types
├── execution/
│   ├── ExecutionManager.kt        # Orchestrates execution, returns ToolCallResult
│   ├── CodeEvalManager.kt         # Compiles scripts, captures script body block
│   ├── ScriptExecutor.kt          # Runs captured blocks with timeout
│   ├── McpScriptContext.kt        # Interface for script context (project, output, utilities)
│   ├── McpScriptContextImpl.kt    # Implementation with output methods, waitForSmartMode
│   └── Diff.kt                    # Unified diff generation for review feedback
├── script/
│   ├── McpSteroidScriptDefinition.kt          # @KotlinScript annotation for .kts files
│   ├── McpSteroidScriptDefinitionsSource.kt   # ScriptDefinitionsSource for K1/K2 modes
│   └── McpSteroidScriptDefinitionsProvider.kt # ScriptDefinitionsProvider bridge API
├── vision/
│   ├── VisionService.kt           # Screenshot capture and input dispatch
│   ├── InputSequence.kt           # Input sequence parsing and execution
│   └── WindowIdUtil.kt            # Window identification utilities
├── ocr/
│   └── OcrProcessClient.kt        # External OCR process communication
├── koltinc/
│   └── KotlincProcessClient.kt    # External kotlinc process communication
├── review/
│   ├── ReviewManager.kt           # Human review workflow, diff generation
│   └── McpReviewNotificationProvider.kt  # Editor notification panel
├── updates/
│   └── UpdateChecker.kt           # Periodic update checker service
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
./gradlew test --tests "*CliClaudeIntegrationTest*"
```

### Test Files

- **McpServerIntegrationTest.kt** - Tests MCP server HTTP handshake and tool flows:
  - Tests MCP protocol initialization and session management
  - Tests tool listing and invocation via HTTP
  - Tests system property reading via `steroid_execute_code`
  - Tests Claude CLI and Codex CLI Accept header compatibility
  - Tests graceful handling of unknown/stale session IDs

- **CliClaudeIntegrationTest.kt** - Tests Claude Code CLI integration:
  - Uses Docker to run Claude CLI in isolation
  - Requires Docker and ANTHROPIC_API_KEY
  - Tests MCP server registration (`mcp add`, `mcp list`, `mcp remove`)
  - Tests tool discovery and invocation
  - Tests system property reading via MCP execute_code
  - Tests documented command-line workflow

- **CliCodexIntegrationTest.kt** - Tests OpenAI Codex CLI integration:
  - Uses Docker to run Codex CLI in isolation
  - Requires Docker and OPENAI_API_KEY
  - Tests MCP server TOML configuration
  - Tests tool discovery and invocation
  - Tests system property reading via MCP execute_code
  - Note: `codex mcp add` only supports stdio servers; HTTP uses TOML config

- **CliGeminiIntegrationTest.kt** - Tests Google Gemini CLI integration:
  - Uses Docker to run Gemini CLI in isolation
  - Requires Docker and GOOGLE_API_KEY
  - Tests MCP server configuration
  - Tests tool discovery and invocation

- **ScriptExecutorTest.kt** - Tests script execution with fast failure semantics:
  - Verifies errors return quickly (not waiting for timeout)
  - Tests compilation errors, runtime errors, missing execute blocks
  - Tests multiple execute blocks (FIFO order)

- **ExecutionManagerTest.kt** - Tests execution manager with progress reporting:
  - Tests successful execution with output collection
  - Tests error handling and timeout scenarios

- **KotlinDaemonManagerTest.kt** - Tests Kotlin daemon management:
  - Tests daemon recovery after "Service is dying" errors
  - Tests retry logic with delays

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
Context provided in the script body:
- `project` - IntelliJ Project
- `params` - Original tool parameters (JsonElement)
- `disposable` - Parent Disposable for cleanup
- `println(vararg)`, `printJson(obj)`, `printException(msg, t)`, `progress(msg)`
- `suspend fun waitForSmartMode()`

## Configuration

- `gradle.properties`: Contains `platformVersion` for IntelliJ version
- `build.gradle.kts`: Plugin configuration using `intellijPlatform` DSL
- Registry keys:
  - `mcp.steroid.server.port`: MCP server port
  - `mcp.steroid.server.host`: MCP server bind address (default: `127.0.0.1`)
  - `mcp.steroid.review.mode`: `ALWAYS` (default), `TRUSTED`, `NEVER`
  - `mcp.steroid.review.timeout`: Review timeout in seconds
  - `mcp.steroid.execution.timeout`: Script execution timeout
  - `mcp.steroid.updates.enabled`: Enable automatic update checks (default: `true`)
  - `mcp.steroid.storage.path`: Override storage path (empty = `.idea/mcp-steroid`)
  - `mcp.steroid.idea.description.enabled`: Generate `.idea/mcp-steroid.md` (default: `true`)

### Script Preprocessing (CodeButcher)

User-submitted scripts are preprocessed by `CodeButcher.wrapWithImports()` before compilation:

1. **Import extraction**: User imports (including `; import ...` patterns) are extracted if present
2. **Default imports**: Standard IntelliJ/Kotlin imports are added
3. **Import merging**: User imports are placed after default imports
4. **Code assembly**: The script body is wrapped into a single suspend method

Imports are optional; if provided, CodeButcher hoists them to the top so the script compiles cleanly.

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

### Hot Reload to Running IDE (Recommended)

Use the `deployPlugin` task to hot-reload the plugin into running IntelliJ instances:

```bash
# Build and hot-reload to all running IDEs
./gradlew deployPlugin
```

**Requirements:**
- The [Plugin Hot Reload](https://plugins.jetbrains.com/plugin/24027-plugin-hot-reload) plugin must be installed in IntelliJ
- IntelliJ must be running with a project open

**How it works:**
1. Builds the plugin ZIP
2. Finds running IDEs by looking for `~/.PID.hot-reload` files
3. POSTs the plugin to each IDE's hot-reload endpoint
4. The IDE unloads the old version and loads the new one without restart

**ALWAYS use this after making changes** - it's much faster than restarting IntelliJ.

### Deploy to Local IntelliJ Instance (Cold Deploy)

For deployments that require IDE restart:

```bash
# Build and deploy to IntelliJ 253
./gradlew deployPluginLocallyTo253

# This deploys to: ~/intellij-253/config/plugins/intellij-mcp-steroid/
```

After cold deploy, restart IntelliJ to load the new plugin version.

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
3. **Test before commit**: Run Gradle `test` in the IDE via MCP to verify changes
4. **Build verification**: Run Gradle `build` in the IDE via MCP to ensure the plugin builds

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

## IDE Control via execute_code

You can invoke IDE actions programmatically via `steroid_execute_code`. This is useful for operations like restarting the IDE, invalidating caches, or triggering any IDE action.

### Available Restart Actions

| Action ID | Description |
|-----------|-------------|
| `RestartIde` | Restart IDE… |
| `InvalidateAndRestart` | Invalidate Caches and Restart |
| `RestartJCEFActionId` | Restart Web Browser (JCEF) |
| `TypeScript.Restart.Service` | Restart TypeScript Service |

### Restarting the IDE

**⚠️ WARNING**: Executing `RestartIde` will restart the IDE, terminating your MCP connection. Only use this when you explicitly need to restart.

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext

val actionManager = ActionManager.getInstance()
val restartAction = actionManager.getAction("RestartIde")

if (restartAction == null) {
    println("RestartIde action not found")
    return
}

// Create data context with project
val dataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .build()

// Invoke the action
println("Restarting IDE...")
ActionUtil.invokeAction(restartAction, dataContext, "mcp", null, null)
```

### Checking if an Action is Available

Before invoking an action, you can check if it's enabled:

```kotlin
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext

val actionManager = ActionManager.getInstance()
val action = actionManager.getAction("RestartIde")

val dataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .build()

val presentation = action?.templatePresentation?.clone() ?: Presentation()
val event = AnActionEvent.createFromDataContext("mcp", presentation, dataContext)

action?.update(event)

println("Action enabled: ${presentation.isEnabled}")
println("Action visible: ${presentation.isVisible}")
```

### Listing All Actions

To discover available actions:

```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val actionManager = ActionManager.getInstance()
val allActionIds = actionManager.getActionIds("")

// Filter for specific actions
val restartActions = allActionIds.filter {
    it.contains("restart", ignoreCase = true)
}

restartActions.forEach { actionId ->
    val action = actionManager.getAction(actionId)
    val text = action?.templatePresentation?.text ?: "N/A"
    println("$actionId -> $text")
}
```

## Writing Tests

For execution tests:

```kotlin
class MyTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        setRegistryPropertyForTest("mcp.steroid.review.mode", "NEVER")
    }

    fun testSomething(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()
        val result = manager.executeWithProgress(
            ExecCodeParams(
                taskId = "test",
                code = "println(\"hello\")",
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

## GitHub Issues Workflow

### Finding and Creating Issues

1. **Review source files using IntelliJ MCP**:
   - Open files in the editor via `steroid_execute_code`
   - Wait for IDE to highlight the file (smart mode is automatic; call waitForSmartMode() only if you trigger indexing)
   - Review warnings, errors, and suggestions from the IDE
   - Check TODO/FIXME comments in code

2. **Create GitHub issues for improvements**:
   ```bash
   gh issue create --repo jonnyzzz/intellij-mcp-steroids \
     --title "Issue title" \
     --body "Description"
   ```

3. **Link issues to code locations**:
   - Reference file paths and line numbers
   - Quote relevant code snippets
   - Tag with appropriate labels

### Working Through Issues

1. **List open issues**:
   ```bash
   gh issue list --repo jonnyzzz/intellij-mcp-steroids
   ```

2. **Work on issues one by one**:
   - Research IntelliJ codebase at `../intellij` for patterns
   - Use IntelliJ MCP to explore APIs
   - Implement the fix
   - Run tests in the IDE via MCP (Gradle run configuration)
   - Deploy: `./gradlew deployPlugin`

3. **Close issues with commits**:
   - Reference issue in commit message: `Fixes #123`

## AI Tests

The `ai-tests/` folder contains test prompts for validating MCP integration.

### Running AI Tests

1. Deploy fresh plugin:
   ```bash
   ./gradlew deployPlugin
   ```

2. Execute test prompts from `ai-tests/*.md` files

3. Validate outcomes against expected results

### Test Files

- `01-code-execution.md` - Basic code execution
- `02-project-info.md` - Project/window information
- `03-screenshots.md` - Screenshot capture
- `04-input-dispatch.md` - Keyboard/mouse input
- `05-action-discovery.md` - Editor actions
- `06-project-opening.md` - Project opening workflow

See `ai-tests/_INSTRUCTIONS.md` for detailed usage.

## Code Review Using IntelliJ MCP

When reviewing code quality:

1. **Open file in editor**:
   ```kotlin
   val file = findProjectFile("src/main/kotlin/path/to/File.kt")
   if (file != null) {
       com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
           .openFile(file, true)
   }
   ```

2. **Wait for analysis**:
   ```kotlin
   // waitForSmartMode() is automatic before your script starts
   // Wait additional time for daemon to complete if needed
   delay(2000)
   ```

3. **Get warnings and errors**:
   ```kotlin
   import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
   import com.intellij.codeInsight.daemon.impl.HighlightInfo
   import com.intellij.lang.annotation.HighlightSeverity

   val file = findProjectPsiFile("src/main/kotlin/path/to/File.kt")
   if (file != null) {
       val highlights = readAction {
           DaemonCodeAnalyzerEx.getInstanceEx(project)
               .getFileLevelHighlights(project, file)
       }
       highlights.forEach { println(it) }
   }
   ```

4. **Create issues for findings**:
   - Group related warnings
   - Include file paths and line numbers
   - Propose fixes where obvious
