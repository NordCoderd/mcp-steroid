# CLAUDE.md, AGENTS.md

Guidance for Claude Code when working with this repository. **Instructions here override default behavior.**

Never include AI as co-author or mention AI in commit messages.

## MUST DO

- Use IntelliJ MCP for everything where you can
- Never ignore warnings or errors — fix them properly
- No test-only branches (`isUnitTestMode`) — use correct IntelliJ actions (`writeIntentReadAction`, `writeCommandAction`)
- Tests must show reality — a failing test is better than a fake passing test
- No `@Suppress("DEPRECATION")` — find the non-deprecated replacement
- Prefer JSON libraries for JSON parsing/manipulation; only static final JSON constants may be hand-written as raw JSON strings
- **BANNED:** `runCatching{}.onFailure{}` — use `try { } catch (e: Exception) { }` instead. Other `runCatching` uses (`.getOrNull()`, `.getOrDefault()`) are fine
- **BANNED:** Code must never reference or depend on `run-agent.sh` or `docs/run-agent.sh`. These scripts are tools for humans and AI agents to use manually, not for programmatic execution. Code should implement agent integrations directly using CLI flags and arguments. `run-agent.sh` must **never** be installed inside Docker containers (no `COPY run-agent.sh` or `RUN chmod +x ... run-agent.sh` in Dockerfiles)
- **BANNED:** Gradle build files must never reach into another subproject's `build/` directory directly. Use Gradle dependency configurations to share artifacts between subprojects. Fail fast with a clear `require()`/`error()` — no silent fallbacks that hide misconfiguration
- Log new ideas/tasks in TODO* files (TODO.md, TODO-*.md)
- **No infrastructure workarounds**: when tests fail due to infrastructure limitations (missing Docker socket, missing CLI, wrong JDK), fix the infrastructure — mount Docker socket, install Docker CLI, configure JDK. Do NOT add code that detects the limitation and silently skips tests or changes behavior. A failing test that reveals a real problem is better than a passing test that hides it.
- **Prefer Kotlin Coroutines native APIs over Java threading primitives**: use `CompletableDeferred<T>` + `withTimeout(duration) { deferred.await() }` instead of `CountDownLatch`. Use `Channel<T>` for streaming. Use `suspendCancellableCoroutine` for one-shot callbacks. `CountDownLatch` / `Semaphore` / `Object.wait()` are banned in new coroutine code — they block threads and are not cancellation-aware.

## Workflow

### IntelliJ API

Use IntelliJ services instead of `object`/singletons. Prefer:
```
inline val serviceX: ServiceX get() = service()
inline val Project.serviceY: ServiceY get() = service()
```

### Test-First Approach

All bugs: add failing test first, then fix. Integration tests preferred. Never fake tests.

### Feature Checklist

1. Read requirements, ask if ambiguous
2. Write tests, implement feature
3. Run Gradle build/test via MCP (not shell)
4. Deploy: `./gradlew deployPlugin`
5. Test with IntelliJ MCP, run ai-tests/
6. Update docs and TODO* entries, commit

### TODO-Driven Development

Track all tasks in TODO* files. Review them before starting work. Work items one-by-one: add failing test, implement, run Gradle build via MCP, mark done. Create new entries for discovered work.

### Final Verification

1. Gradle `build` via MCP — compiles
2. Gradle `test` via MCP — tests pass
3. ai-tests/ scenarios work
4. Use `steroid_execute_code` to check for warnings/errors before completing work

## IntelliJ and Coroutines

- `runBlockingCancellable` — BGT, blocks thread, cancellation-aware
- `runWithModalProgressBlocking` — EDT, shows modal progress
- `RunSuspend` — low-level Object.wait()/notifyAll()

## Project Overview

IntelliJ MCP Steroid — MCP server plugin exposing IDE APIs to LLM agents via Kotlin code execution.

- **GitHub (public)**: https://github.com/jonnyzzz/mcp-steroid — public issue tracker for epics and public bugs
- **GitHub (internal)**: https://github.com/jonnyzzz/intellij-mcp-steroids — internal repo for internal tasks
- **Docs**: [README.md](../../README.md), [AGENT-STEROID-GUIDE.md](../guides/AGENT-STEROID-GUIDE.md)

## Build

Run Gradle tasks from the IDE via MCP, not shell. Key tasks: `build`, `test`, `buildPlugin`, `runIde`, `verifyPlugin`, `deployPlugin`.

Gradle run config example:
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
// runConfig.settings.scriptParameters = "--tests \"*ExecutionManagerTest*\""
val settings = runManager.createConfiguration(runConfig, factory)
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

### Build Notes

- IDE cached under `.intellijPlatform/ides/IU-2025.3`
- Tests use `localPlugin(...)` for Kotlin/Java plugins to align with IDE classpath

## Technology Stack

Gradle 8.11.1 / Kotlin 2.2.21 / Java 21 / IntelliJ Platform 2025.3+ / Ktor 3.1.0 (CIO+SSE) / kotlinx.serialization

## Architecture

IntelliJ Platform plugin with standalone MCP server (Kotlin MCP SDK + Ktor):

**Core flow**: `SteroidsMcpServer.kt` → `ExecuteCodeToolHandler.kt` → `ExecutionManager.kt` → `CodeEvalManager.kt` (compile) → `ScriptExecutor.kt` (run with timeout)

**Key decisions**: Standalone MCP server (no built-in MCP dependency). Synchronous request-response. Coroutines over blocking. Two-phase execution (compile then run). Fast failure on errors. Disposable lifecycle for cleanup.

### Source Structure

```
src/main/kotlin/com/jonnyzzz/mcpSteroid/
├── server/          # MCP server, tool handlers, skills
├── mcp/             # Core MCP protocol, tool registry
├── execution/       # ExecutionManager, CodeEvalManager, ScriptExecutor, McpScriptContext
├── review/          # Human review workflow
├── storage/         # Append-only file storage
├── vision/          # Screenshot, input dispatch
├── demo/            # Demo mode overlay
├── ocr/             # External OCR process
├── koltinc/         # External kotlinc process
└── updates/         # Update checker
```

## Testing

```kotlin
fun testExample(): Unit = timeoutRunBlocking(30.seconds) { /* coroutine test code */ }
```

Test helper:
```kotlin
private fun testExecParams(code: String, timeout: Int = 30) = ExecCodeParams(
    taskId = "test-task", code = code, reason = "test", timeout = timeout,
    rawParams = buildJsonObject { }
)
```

### Key Test Files

- **McpServerIntegrationTest** — MCP protocol handshake, tool flows, session management
- **CliClaudeIntegrationTest** / **CliCodexIntegrationTest** / **CliGeminiIntegrationTest** — Docker-isolated CLI tests (need API keys)
- **ScriptExecutorTest** — Fast failure semantics, compilation/runtime errors
- **ExecutionManagerTest** — Execution with progress reporting

### Docker Integration Tests (test-integration/)

Run AI agents in Docker with IntelliJ IDEA + MCP Steroid. Prerequisites: Docker, API keys, plugin ZIP built.

```bash
./gradlew :test-integration:test --tests '*DebuggerDemoTest.claude*'
./gradlew :test-integration:test --tests '*DpaiaArenaTest*' -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
```

Key classes: `IdeContainer`, `ConsoleDriver`, `XcvbDriver`, `AiAgentDriver`, `ConsolePumpingContainerDriver`

**Screen layout**: IDE left 2/3, console right 1/3, managed by `LayoutManager`.

#### Agent CLI Flags

Each agent is invoked with specific flags to produce NDJSON output piped through `agent-output-filter`:

| Agent | Output flag | Auto-approve flag | Verbose |
|-------|------------|-------------------|---------|
| **Claude** | `--output-format stream-json` | (uses `--permission-mode bypassPermissions`) | **`--verbose`** |
| **Codex** | `--json` | `--dangerously-bypass-approvals-and-sandbox` | n/a |
| **Gemini** | `--output-format stream-json` | `--approval-mode yolo` | n/a |

The `--verbose` flag is **required** for Claude — without it, tool call details are not emitted.

#### Agent Output Format (ClaudeOutputFilter)

Claude Code 2.1.x changed from streaming events (`content_block_delta`, `tool_use`, `tool_result`) to structured events (`assistant`/`user` with full `message.content` arrays). The `result.result` field is empty in new format; actual output is in `assistant.message.content[].type=text` blocks.

`ClaudeOutputFilter` handles **both formats** simultaneously for backward/forward compatibility.

MCP tool names in new format are fully qualified: `mcp__mcp-steroid__steroid_execute_code`. `toolDetail()` strips the prefix with `substringAfterLast("__")`.

#### Integration Test Strategy

- **Run one heavy test at a time** — running multiple 20-minute IDE tests concurrently causes resource exhaustion (IDE window never appears, timeouts)
- **Infrastructure failures are transient** — "Failed waiting for IntelliJ IDEA window" or "Failed waiting for Project import" are usually environment issues; retry individually with a cooldown
- One-at-a-time run: `./gradlew :test-integration:test --tests '*DebuggerDemoTest.claude*'`

#### Forcing Agents to Output Required Data

Use explicit output markers in prompts to ensure agents include required information in final text (not just internal reasoning):

```kotlin
appendLine("OUTPUT_MARKER: <required content description>")
// e.g.
appendLine("BUG_LINE: <the exact buggy line of code>")
appendLine("FILE_PATHS: <at least one file path ending in .java or .kt that you found>")
```

Agents (especially Gemini) may find the right answer internally but not include it in the final text output — markers force explicit reporting.

#### Known Agent Quirks

- **Gemini exit 137** (SIGKILL): Treat as success when NDJSON confirms success — `DockerGeminiSession` handles this automatically
- **Codex MCP prefix**: Tool names are not MCP-prefixed in Codex output (no `mcp__` prefix)
- **Claude new NDJSON**: Since Claude Code 2.1.x, use structured `assistant`/`user` events; filter handles both old and new formats

### Test naming

- **NO `@ParameterizedTest`** — create explicit `@Test` methods with descriptive names instead (e.g., `` `describeMcp claude`() ``, `` `describeMcp codex`() ``)
- For truly dynamic test cases (runtime-discovered), use `@TestFactory` with `DynamicTest.dynamicTest("descriptive-name") { ... }`
- This ensures IDE test runner, `./gradlew --tests`, and CI reports work optimally

## Key Types

- **ExecCodeParams**: `taskId`, `code`, `reason`, `timeout`, `rawParams`
- **ToolCallResult**: `content` (List<ContentItem>), `isError`. Use `ToolCallResult.builder()`
- **McpScriptContext**: `project`, `params`, `disposable`, `println()`, `printJson()`, `progress()`, `waitForSmartMode()`

## Configuration

Registry keys: `mcp.steroid.server.port`, `.host`, `.review.mode` (ALWAYS/TRUSTED/NEVER), `.review.timeout`, `.execution.timeout`, `.dialog.killer.enabled`, `.demo.enabled`, `.storage.path`, `.kotlinc.parameters`, `.kotlinc.home`

### Kotlinc Version-Mismatch Workaround

When IDE bundles newer Kotlin than the plugin's compiler: set `mcp.steroid.kotlinc.home` to `<IDE>/plugins/Kotlin/kotlinc` via Registry.

### Script Preprocessing (CodeButcher)

`CodeButcher.wrapWithImports()`: extracts user imports, adds defaults, merges, wraps body into suspend method.

## IntelliJ Platform Coding Principles

### Threading

- Never block EDT. Use `Dispatchers.EDT` for UI, `Dispatchers.IO`/`Default` for background
- PSI/VFS access needs read actions; modifications need write actions. Use `readAction { }` / `writeAction { }`
- Dumb mode: use `DumbService.isDumb(project)`, `waitForSmartMode()` handles this for scripts

### Coroutines & Services

```kotlin
@Service(Service.Level.PROJECT)
class MyService(private val project: Project, coroutineScope: CoroutineScope)
```

Use `childScope()` for cleanup, `job.cancelOnDispose(disposable)` for cancellation, `Disposer.register(parent, child)` for lifecycle.

Get services: `project.service<MyService>()` or `service<AppService>()`

### Error Handling

- Never catch `ProcessCanceledException` — rethrow it
- Use `Logger.getInstance(MyClass::class.java)` for logging

## Plugin Deployment

```bash
./gradlew deployPlugin     # Hot-reload (requires Plugin Hot Reload plugin)
./gradlew deployPluginLocallyTo253  # Cold deploy (requires restart)
```

## Adding New MCP Tools

1. Create `@Service(Service.Level.APP)` handler with `register(server: McpServerCore)` method
2. Register in `SteroidsMcpServer.kt`: `service<MyToolHandler>().register(server)`

## IDE Control via execute_code

Invoke IDE actions programmatically. Key pattern:
```kotlin
val action = ActionManager.getInstance().getAction("RestartIde")
val dataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
ActionUtil.invokeAction(action, dataContext, "mcp", null, null)
```

List actions: `ActionManager.getInstance().getActionIds("").filter { it.contains("restart", ignoreCase = true) }`

## Environment Constraints

`timeout`/`gtimeout` not available. Use Gradle timeout mechanisms or Bash tool's `timeout` parameter.

## Commit Guidelines

Atomic commits, descriptive messages (what and why). Test and build before committing.

## Website

Hugo site at `website/`. Build: `cd website && make build`. Dev: `make dev`. See [website/CLAUDE.md](website/CLAUDE.md).

## IntelliJ Source Research

Use `run-agent.sh` from `~/Work/jonnyzzz-ai-coder/` to launch AI agents for researching IntelliJ Platform internals in `~/Work/intellij`. This is for manual investigation only -- code must never reference or depend on `run-agent.sh` programmatically (see BANNED rules above).

## AI Tests

Deploy fresh plugin (`./gradlew deployPlugin`), run prompts from `ai-tests/*.md`, validate outcomes. See `ai-tests/_INSTRUCTIONS.md`.
