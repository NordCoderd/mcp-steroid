# Implementation Plan

This plan reflects decisions from [Discussions.md](Discussions.md).

**Target Version**: IntelliJ 2025.3+ (sinceBuild: 252.1)

**Status**: ✅ V1 Implementation Complete (incremental improvements ongoing)

---

## Implementation Status

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: Project Setup | ✅ Complete | Plugin descriptor, package structure |
| Phase 2: Core Execution | ✅ Complete | Two-phase execution (CodeEvalManager + ScriptExecutor) |
| Phase 3: Storage | ✅ Complete | Append-only file storage |
| Phase 4: MCP Toolset | ✅ Complete | All tools implemented |
| Phase 5: Code Review | ✅ Complete | Editor notification panel, diff generation |
| Phase 6: Testing | ✅ Complete | Unit and integration tests |

---

## Incremental Roadmap (Post V1)

These items extend V1 without changing the core execution model.

- Vision tools: screenshot + input actions with per-execution artifacts.
  - `VisionScreenshotToolHandler`, `VisionInputToolHandler`, `ListWindowsToolHandler`
- Action discovery: context-sensitive action/intentions enumeration.
  - `ActionDiscoveryToolHandler`
- Session recovery notice: unknown sessions create a new session and return a notice header.
  - `McpHttpTransport`
- OCR helper app: external `ocr-tesseract` process invoked via `OcrProcessClient`.
  - `ocr-tesseract/`, `OcrProcessClient`
- CLI integration hardening: multi-step exec flow and MCP list validation.
  - `CliClaudeIntegrationTest`, `CliCodexIntegrationTest`
- Script execution availability smoke test to catch engine regressions quickly.
  - `ScriptExecutionAvailabilityTest`

---

## Key Architecture Decisions

### MCP Integration

Uses standalone Kotlin MCP SDK with Ktor HTTP transport. The server runs independently of IntelliJ's built-in MCP plugin.

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/server/`](src/main/kotlin/com/jonnyzzz/intellij/mcp/server/)

Key files:
- `SteroidsMcpServer.kt` - Ktor-based MCP server
- `ExecuteCodeToolHandler.kt` - Handles `steroid_execute_code` tool
- `ExecuteFeedbackToolHandler.kt` - Handles `steroid_execute_feedback` tool
- `ListProjectsToolHandler.kt` - Handles `steroid_list_projects` tool

### Script Execution Model

**Script body API**:

1. Script body (suspend function) - user submits plain Kotlin statements
2. `McpScriptContext` - full API, available as the receiver

```kotlin
// User writes:
println("Hello!")
```

The script body is wrapped into a single runnable block. `execute { }` remains available for backward compatibility but is not required.

### Classloader

Use `IdeScriptEngineManager` with `AllPluginsLoader.INSTANCE` (automatic, no config needed).

### Review Mode

- Enabled by default (`ALWAYS`)
- `TRUSTED` = trust all MCP callers, auto-approve
- Configurable via IntelliJ Registry
- All requests logged regardless of mode

### Response Model

IntelliJ MCP tools return complete `McpToolCallResult` objects (no streaming at tool level).
Use polling via `get_result` to retrieve execution output.

---

## Phase 1: Project Setup

### 1.1 Update build.gradle.kts

```kotlin
plugins {
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        localPlugin(kotlinPluginPath)
        localPlugin(javaPluginPath)
        testFramework(TestFrameworkType.Platform)
    }
}
```

`kotlinPluginPath` and `javaPluginPath` are resolved from the local IDE distribution; see `build.gradle.kts`.

### 1.2 Update plugin.xml

```xml
<idea-plugin>
    <id>com.jonnyzzz.mcpSteroid-steroid</id>
    <name>IntelliJ MCP Steroid</name>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.lang</depends>
    <depends>com.intellij.mcpServer</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Review panel -->
        <editorNotificationProvider
            implementation="com.jonnyzzz.mcpSteroid.review.McpReviewNotificationProvider"/>

        <!-- Project services -->
        <projectService serviceImplementation="com.jonnyzzz.mcpSteroid.execution.ExecutionManager"/>
        <projectService serviceImplementation="com.jonnyzzz.mcpSteroid.storage.ExecutionStorage"/>

        <!-- Registry keys -->
        <registryKey key="mcp.steroids.review.mode" defaultValue="ALWAYS"
            description="Review mode: ALWAYS, TRUSTED, NEVER"/>
        <registryKey key="mcp.steroids.review.timeout" defaultValue="300"
            description="Review timeout in seconds"/>
        <registryKey key="mcp.steroids.execution.timeout" defaultValue="60"
            description="Execution timeout in seconds"/>
    </extensions>

    <extensions defaultExtensionNs="com.intellij.mcpServer">
        <mcpToolset implementation="com.jonnyzzz.mcpSteroid.SteroidsMcpToolset"/>
    </extensions>
</idea-plugin>
```

### 1.3 Package Structure (Actual Implementation)

```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── server/
│   ├── SteroidsMcpServer.kt               # Ktor MCP server with HTTP transport
│   ├── ExecuteCodeToolHandler.kt          # steroid_execute_code tool
│   ├── ExecuteFeedbackToolHandler.kt      # steroid_execute_feedback tool
│   ├── ListProjectsToolHandler.kt         # steroid_list_projects tool
│   ├── PluginReloadToolHandler.kt         # Plugin reload tools
│   └── McpProgressReporter.kt             # Progress reporting interface
├── mcp/
│   ├── McpServerCore.kt                   # Core MCP server logic
│   ├── McpToolRegistry.kt                 # Tool registration and dispatch
│   ├── McpBuilders.kt                     # ToolCallResult builder
│   └── McpProtocol.kt                     # MCP protocol types
├── execution/
│   ├── ExecutionManager.kt                # Orchestrates execution workflow
│   ├── CodeEvalManager.kt                 # Script compilation, lambda capture
│   ├── ScriptExecutor.kt                  # Executes captured blocks with timeout
│   ├── McpScriptScope.kt                  # Interface bound to script engine
│   ├── McpScriptContext.kt                # Context interface for scripts
│   ├── McpScriptContextImpl.kt            # Implementation
│   └── Diff.kt                            # Unified diff generation
├── storage/
│   └── ExecutionStorage.kt                # Append-only file storage
└── review/
    ├── ReviewManager.kt                   # Human review workflow
    └── McpReviewNotificationProvider.kt   # Editor notification panel
```

---

## Phase 2: Core Execution Engine

### 2.1 McpScriptScope Interface

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptScope.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptScope.kt)

Bound to script engine as "execute" function receiver with single method `execute(block: suspend McpScriptContext.() -> Unit)`.

### 2.2 McpScriptContext Interface

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt)

Key members:
- `project`, `params`, `disposable`, `isDisposed`
- `println(vararg values)`, `printJson(obj)`, `printException(msg, t)`, `progress(msg)`
- `suspend fun waitForSmartMode()`

**Note**: `readAction` and `writeAction` are NOT part of McpScriptContext. Scripts should import and use IntelliJ's coroutine-aware APIs directly.

### 2.3 McpScriptContextImpl

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContextImpl.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContextImpl.kt)

Implementation that:
- Tracks disposed state via AtomicBoolean
- Outputs via `ExecutionResultBuilder` interface
- Implements `waitForSmartMode()` using `DumbService.smartInvokeLater`

### 2.4 Two-Phase Execution

The implementation splits execution into two components:

**CodeEvalManager** - Handles compilation and lambda capture:
- **See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/CodeEvalManager.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/CodeEvalManager.kt)
- Uses `IdeScriptEngineManager` to get Kotlin script engine
- Wraps code with predefined imports

**ScriptExecutor** - Runs captured blocks with timeout:
- **See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/ScriptExecutor.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/ScriptExecutor.kt)
- Creates `McpScriptContextImpl` with disposable lifecycle
- Runs blocks in FIFO order with `withTimeout`
- Disposes context when execution completes

### 2.5 ExecutionManager (Project Service)

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/ExecutionManager.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/ExecutionManager.kt)

Orchestrates the execution workflow:
- Creates execution ID and stores parameters
- Coordinates with `ReviewManager` for human approval
- Delegates to `ScriptExecutor` for actual execution
- Returns `ToolCallResult` directly (synchronous request-response)

---

## Phase 3: Storage

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/storage/ExecutionStorage.kt`](src/main/kotlin/com/jonnyzzz/intellij/mcp/storage/ExecutionStorage.kt)

Append-only file storage under `.idea/mcp-run/`:
- `writeNewExecution(ExecCodeParams)` - Creates new execution directory
- `appendExecutionEvent(executionId, text)` - Appends to output.jsonl
- `writeCodeExecutionData(executionId, name, data)` - Writes arbitrary files
- `writeCodeReviewFile(executionId, params)` - Creates review.kts for human review

Execution ID format: `{YYYYMMDD}T{HHMMSS}-{sanitized-task-id}`

---

## Phase 4: MCP Tools

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/server/`](src/main/kotlin/com/jonnyzzz/intellij/mcp/server/)

Tools are registered in `SteroidsMcpServer.kt`:

| Tool | Handler | Description |
|------|---------|-------------|
| `steroid_list_projects` | `ListProjectsToolHandler` | List all open projects |
| `steroid_execute_code` | `ExecuteCodeToolHandler` | Execute Kotlin code |
| `steroid_execute_feedback` | `ExecuteFeedbackToolHandler` | Provide execution feedback |
| `steroid_plugin_info` | `PluginReloadToolHandler` | Get plugin info |
| `steroid_plugin_reload` | `PluginReloadToolHandler` | Reload plugin |

**Note**: No polling model - `steroid_execute_code` returns output directly in response.

---

## Phase 5: Code Review

**See**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/review/`](src/main/kotlin/com/jonnyzzz/intellij/mcp/review/)

- `ReviewManager.kt` - Human review workflow with `CompletableDeferred`
- `McpReviewNotificationProvider.kt` - Editor notification panel with Approve/Reject buttons

Features:
- Opens code in editor for human review
- Supports user edits (generates unified diff on rejection)
- Timeout configurable via Registry
- Review mode: ALWAYS (default), TRUSTED, NEVER

---

## Phase 6: Testing

**See**: [`src/test/kotlin/com/jonnyzzz/intellij/mcp/`](src/test/kotlin/com/jonnyzzz/intellij/mcp/)

Test files:
- `SteroidsMcpToolsetTest.kt` - MCP execution flow tests
- `execution/ExecutionManagerTest.kt` - ExecutionManager tests
- `execution/ScriptExecutorTest.kt` - Fast failure and timeout tests
- `execution/McpScriptContextTest.kt` - Context API tests
- `mcp/McpServerCoreTest.kt` - MCP protocol tests

Test utilities:
- Use `timeoutRunBlocking(30.seconds)` for coroutine tests
- Set `mcp.steroids.review.mode` to `NEVER` for tests
- Tests should handle both SUCCESS and ERROR (script engine may not be available)

---

## Implementation Order

1. **Phase 1**: Project setup, plugin.xml, package structure
2. **Phase 2**: McpScriptScope, McpScriptContext, ScriptExecutor
3. **Phase 3**: ExecutionStorage with new ID format
4. **Phase 4**: SteroidsMcpToolset
5. **Phase 6**: Tests
6. **Phase 5**: Code review (can defer)

---

## Key Decisions Summary

| Topic | Decision |
|-------|----------|
| MCP Integration | Standalone Kotlin MCP SDK with Ktor HTTP transport |
| Target Version | IntelliJ 2025.3+ (sinceBuild: 252.1) |
| Entry Point | Script body |
| Script Engine | IdeScriptEngineManager + AllPluginsLoader |
| Execution Architecture | Two-phase: CodeEvalManager (compile) + ScriptExecutor (run) |
| CoroutineScope | Service-injected, Dispatchers.IO + withTimeout |
| McpScriptContext | Has disposable property, NOT Disposable itself |
| Read/Write Actions | NOT part of context, use IntelliJ's coroutine-aware APIs |
| Review Mode | ALWAYS default, TRUSTED = trust all callers |
| Execution ID | `{YYYYMMDD}T{HHMMSS}-{task-id}` |
| Response Model | Synchronous request-response (no polling) |
| Result Type | `ToolCallResult` with content list and isError flag |
| Language | Kotlin only (v1) |
| Slots/Commands | Deferred to v2 |
| Compilation | Synchronous in CodeEvalManager, before execution phase |
