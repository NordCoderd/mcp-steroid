# Implementation Plan

This plan reflects decisions from [Discussions.md](Discussions.md).

**Target Version**: IntelliJ 2025.3+ (sinceBuild: 252.1)

**Status**: ✅ V1 Implementation Complete

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

## Key Architecture Decisions

### MCP Integration

Uses IntelliJ's built-in MCP server plugin (`com.intellij.mcpServer`):

```kotlin
class SteroidsMcpToolset : McpToolset {
    @McpTool
    @McpDescription("Execute Kotlin code in IDE context")
    suspend fun execute_code(
        @McpDescription("Project name") projectName: String,
        @McpDescription("Kotlin code to execute") code: String,
        @McpDescription("Execution timeout in seconds") timeout: Int = 60
    ): ExecuteCodeResult { ... }
}
```

No REST endpoint fallback - McpToolset only.

### Script Execution Model

**Two-layer API**:

1. `McpScriptScope` - Bound to script engine, single method `execute { }`
2. `McpScriptContext` - Full API, passed to execute block

```kotlin
// User writes:
execute {
    waitForSmartMode()
    println("Hello!")
}
```

This ensures we control the execution context and coroutine setup.

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
    id("org.jetbrains.intellij.platform") version "2.1.0"
    kotlin("jvm") version "2.1.0"
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.3")
        bundledPlugin("com.intellij.java")
    }
}
```

### 1.2 Update plugin.xml

```xml
<idea-plugin>
    <id>com.jonnyzzz.intellij.mcp-steroid</id>
    <name>IntelliJ MCP Steroid</name>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.lang</depends>
    <depends>com.intellij.mcpServer</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Review panel -->
        <editorNotificationProvider
            implementation="com.jonnyzzz.intellij.mcp.review.McpReviewNotificationProvider"/>

        <!-- Project services -->
        <projectService serviceImplementation="com.jonnyzzz.intellij.mcp.execution.ExecutionManager"/>
        <projectService serviceImplementation="com.jonnyzzz.intellij.mcp.storage.ExecutionStorage"/>

        <!-- Registry keys -->
        <registryKey key="mcp.steroids.review.mode" defaultValue="ALWAYS"
            description="Review mode: ALWAYS, TRUSTED, NEVER"/>
        <registryKey key="mcp.steroids.review.timeout" defaultValue="300"
            description="Review timeout in seconds"/>
        <registryKey key="mcp.steroids.execution.timeout" defaultValue="60"
            description="Execution timeout in seconds"/>
    </extensions>

    <extensions defaultExtensionNs="com.intellij.mcpServer">
        <mcpToolset implementation="com.jonnyzzz.intellij.mcp.SteroidsMcpToolset"/>
    </extensions>
</idea-plugin>
```

### 1.3 Package Structure (Actual Implementation)

```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── SteroidsMcpToolset.kt          # MCP toolset with all tools + DTOs
├── execution/
│   ├── ExecutionManager.kt        # Project service, orchestrates workflow
│   ├── CodeEvalManager.kt         # Script compilation, lambda capture
│   ├── ScriptExecutor.kt          # Executes captured blocks with timeout
│   ├── McpScriptScope.kt          # Interface bound to script engine (execute {})
│   ├── McpScriptContext.kt        # Context interface for scripts
│   ├── McpScriptContextEx.kt      # Extended interface with reflection helpers
│   └── McpScriptContextImpl.kt    # Implementation with output, waitForSmartMode
├── storage/
│   └── ExecutionStorage.kt        # File-based history + data classes
└── review/
    ├── ReviewManager.kt           # Human review workflow, diff generation
    └── McpReviewNotificationProvider.kt  # Editor notification panel
```

**Note**: Hash utilities are inline in ExecutionStorage and SteroidsMcpToolset (no separate util files).

---

## Phase 2: Core Execution Engine

### 2.1 McpScriptScope Interface

```kotlin
// Bound to script engine as "execute" function receiver
interface McpScriptScope {
    fun execute(block: suspend McpScriptContext.() -> Unit)
}
```

### 2.2 McpScriptContext Interface (Actual Implementation)

```kotlin
interface McpScriptContext {
    val project: Project
    val executionId: String
    val disposable: Disposable
    val isDisposed: Boolean

    // Output
    fun println(vararg values: Any?)
    fun printJson(obj: Any?)
    fun logInfo(message: String)
    fun logWarn(message: String)
    fun logError(message: String, throwable: Throwable? = null)

    // IDE Utilities
    suspend fun waitForSmartMode()
}

// Extended interface with reflection helpers
interface McpScriptContextEx : McpScriptContext {
    fun listServices(): List<String>
    fun listExtensionPoints(): List<String>
    fun describeClass(className: String): String
}
```

**Note**: `readAction` and `writeAction` are NOT part of McpScriptContext. Scripts should import and use IntelliJ's coroutine-aware APIs directly:
```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
```

### 2.3 McpScriptContextImpl (Actual Implementation)

```kotlin
class McpScriptContextImpl(
    override val project: Project,
    override val executionId: String,
    override val disposable: Disposable,  // Parent Disposable for cleanup
) : McpScriptContextEx {

    private val disposed = AtomicBoolean(false).also {
        Disposer.register(disposable) { it.set(true) }
    }

    override val isDisposed: Boolean
        get() = disposed.get()

    override suspend fun waitForSmartMode() {
        checkDisposed()
        if (!DumbService.isDumb(project)) return

        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                if (disposed.get()) {
                    cont.cancel()
                    return
                }
                DumbService.getInstance(project).smartInvokeLater {
                    if (disposed.get()) {
                        cont.cancel()
                    } else if (DumbService.isDumb(project)) {
                        waitForSmart()
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            waitForSmart()
        }
    }

    // Output methods write to ExecutionStorage.appendOutput()
    // Reflection helpers provide informational service/EP listings
}
```

### 2.4 Two-Phase Execution (Actual Implementation)

The actual implementation splits execution into two components:

**CodeEvalManager** - Handles compilation and lambda capture:
```kotlin
@Service(Service.Level.PROJECT)
class CodeEvalManager(private val project: Project) : Disposable {

    fun evalCode(executionId: String, code: String): EvalResult {
        val scope = DisposableScope(executionId)  // McpScriptScope impl

        val engine = IdeScriptEngineManager.getInstance()
            .getEngineByFileExtension("kts", null)
            ?: return EvalResult.Failed(/* error */)

        engine.setBinding("execute", { block -> scope.execute(block) })
        engine.eval(wrapWithImports(code))

        Disposer.dispose(scope)  // Mark scope as disposed

        if (scope.capturedBlocks.isEmpty()) {
            return EvalResult.Failed(/* no execute block error */)
        }

        return EvalResult.Success(scope.capturedBlocks.toList())
    }

    private fun wrapWithImports(code: String): String = """
        import com.intellij.openapi.project.*
        import com.intellij.openapi.application.*
        import com.intellij.openapi.application.readAction
        import com.intellij.openapi.application.writeAction
        import com.intellij.openapi.vfs.*
        import com.intellij.openapi.editor.*
        import com.intellij.openapi.fileEditor.*
        import com.intellij.openapi.command.*
        import com.intellij.psi.*
        import kotlinx.coroutines.*

        $code
    """.trimIndent()
}
```

**ScriptExecutor** - Runs captured blocks with timeout:
```kotlin
@Service(Service.Level.PROJECT)
class ScriptExecutor(private val project: Project) : Disposable {

    suspend fun execute(executionId: String, code: String, timeoutSeconds: Int?): ExecutionResult {
        val evalResult = project.service<CodeEvalManager>().evalCode(executionId, code)
        if (evalResult is EvalResult.Failed) return evalResult.errorResult

        val executionDisposable = Disposer.newDisposable(this, "mcp-execution-$executionId")
        val context = McpScriptContextImpl(project, executionId, executionDisposable)

        try {
            return coroutineScope {
                withContext(Dispatchers.IO) {
                    withTimeout(timeoutSeconds.seconds) {
                        runTheSubmittedCode(evalResult.result, executionId, context)
                    }
                }
            }
        } finally {
            Disposer.dispose(executionDisposable)
        }
    }

    private suspend fun runTheSubmittedCode(
        capturedBlocks: List<suspend McpScriptContext.() -> Unit>,
        executionId: String,
        context: McpScriptContextImpl
    ): ExecutionResult {
        for (block in capturedBlocks) {
            yield()
            block(context)
        }
        return ExecutionResult(status = ExecutionStatus.SUCCESS)
    }
}
```

### 2.5 ExecutionManager (Project Service)

```kotlin
@Service(Service.Level.PROJECT)
class ExecutionManager(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) {
    private val executionScope = coroutineScope.childScope(
        "MCP Execution",
        Dispatchers.Default.limitedParallelism(1)  // Sequential
    )

    private val executor = ScriptExecutor(
        project,
        project.service<ExecutionStorage>(),
        executionScope
    )

    private val executions = ConcurrentHashMap<ExecutionId, ExecutionState>()

    suspend fun submit(code: String, params: ExecutionParams): ExecutionId {
        val storage = project.service<ExecutionStorage>()
        val executionId = storage.createExecution(code, params)

        executions[executionId] = ExecutionState.Compiling

        executionScope.launch {
            val result = executor.execute(executionId, code)
            executions[executionId] = ExecutionState.Completed(result)
            storage.writeResult(executionId, result)
        }

        return executionId
    }

    fun getStatus(executionId: ExecutionId): ExecutionStatus =
        executions[executionId]?.toStatus() ?: ExecutionStatus.NotFound

    fun cancel(executionId: ExecutionId): Boolean {
        // Implementation
    }
}
```

---

## Phase 3: Storage

### 3.1 ExecutionStorage

```kotlin
@Service(Service.Level.PROJECT)
class ExecutionStorage(private val project: Project) {
    private val baseDir: Path
        get() = Path.of(project.basePath!!, ".idea", "mcp-run")

    fun createExecution(code: String, params: ExecutionParams): ExecutionId {
        val projectHash = ProjectHash.compute(project.name)
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
        val payloadHash = PayloadHash.compute(code, params)

        val id = ExecutionId("$projectHash-$timestamp-$payloadHash")
        val dir = baseDir.resolve(id.value)
        Files.createDirectories(dir)

        Files.writeString(dir.resolve("script.kt"), code)
        Files.writeString(dir.resolve("parameters.json"), Gson().toJson(params))

        return id
    }

    fun appendOutput(id: ExecutionId, message: OutputMessage) {
        val file = baseDir.resolve(id.value).resolve("output.jsonl")
        Files.writeString(
            file,
            Gson().toJson(message) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )
    }

    fun readOutput(id: ExecutionId, offset: Int): List<OutputMessage> {
        val file = baseDir.resolve(id.value).resolve("output.jsonl")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file)
            .drop(offset)
            .map { Gson().fromJson(it, OutputMessage::class.java) }
    }

    fun writeResult(id: ExecutionId, result: ExecutionResult) {
        val file = baseDir.resolve(id.value).resolve("result.json")
        Files.writeString(file, Gson().toJson(result))
    }
}
```

### 3.2 Hash Utilities

```kotlin
object ProjectHash {
    fun compute(projectName: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectName.toByteArray())
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hash).take(3)
    }
}

object PayloadHash {
    fun compute(code: String, params: ExecutionParams): String {
        val payload = code + Gson().toJson(params)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hash).take(10)
    }
}
```

---

## Phase 4: MCP Toolset

### 4.1 SteroidsMcpToolset

```kotlin
class SteroidsMcpToolset : McpToolset {

    @McpTool
    @McpDescription("List all open projects")
    fun list_projects(): List<ProjectInfo> {
        return ProjectManager.getInstance().openProjects.map {
            ProjectInfo(it.name, it.basePath ?: "")
        }
    }

    @McpTool
    @McpDescription("Execute Kotlin code in IDE context")
    suspend fun execute_code(
        @McpDescription("Project name (from list_projects)") projectName: String,
        @McpDescription("Kotlin code to execute") code: String,
        @McpDescription("Timeout in seconds") timeout: Int = 60,
        @McpDescription("Show review on error") showReviewOnError: Boolean = false
    ): ExecuteCodeResult {
        val project = findProject(projectName) ?: mcpFail("Project not found: $projectName")
        val manager = project.service<ExecutionManager>()
        val id = manager.submit(code, ExecutionParams(timeout, showReviewOnError))
        return ExecuteCodeResult(id.value, manager.getStatus(id))
    }

    @McpTool
    @McpDescription("Get execution result (poll for status and output)")
    fun get_result(
        @McpDescription("Execution ID") executionId: String,
        @McpDescription("Message offset") offset: Int = 0
    ): GetResultResponse {
        val id = ExecutionId(executionId)
        val project = findProjectByExecutionId(id) ?: mcpFail("Project not found for execution")
        val manager = project.service<ExecutionManager>()
        val storage = project.service<ExecutionStorage>()

        return GetResultResponse(
            executionId = executionId,
            status = manager.getStatus(id),
            output = storage.readOutput(id, offset)
        )
    }

    @McpTool
    @McpDescription("Cancel execution")
    fun cancel_execution(
        @McpDescription("Execution ID") executionId: String
    ): CancelResult {
        val id = ExecutionId(executionId)
        val project = findProjectByExecutionId(id) ?: mcpFail("Project not found for execution")
        val manager = project.service<ExecutionManager>()
        val cancelled = manager.cancel(id)
        return CancelResult(cancelled)
    }

    private fun findProject(name: String): Project? =
        ProjectManager.getInstance().openProjects.find { it.name == name }

    private fun findProjectByExecutionId(id: ExecutionId): Project? {
        val projectHash = id.value.split("-").firstOrNull() ?: return null
        return ProjectManager.getInstance().openProjects.find {
            ProjectHash.compute(it.name) == projectHash
        }
    }
}
```

---

## Phase 5: Code Review

### 5.1 ReviewManager

```kotlin
@Service(Service.Level.PROJECT)
class ReviewManager(private val project: Project) {
    private val pending = ConcurrentHashMap<ExecutionId, CompletableDeferred<ReviewResult>>()

    suspend fun requestReview(id: ExecutionId, code: String): ReviewResult {
        val reviewMode = Registry.stringValue("mcp.steroids.review.mode")
        if (reviewMode == "TRUSTED" || reviewMode == "NEVER") {
            return ReviewResult.Approved
        }

        val file = saveToPendingFolder(id, code)

        withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).openFile(file, true)
        }

        val deferred = CompletableDeferred<ReviewResult>()
        pending[id] = deferred
        return deferred.await()
    }

    fun approve(id: ExecutionId) {
        pending.remove(id)?.complete(ReviewResult.Approved)
    }

    fun reject(id: ExecutionId, reason: String, editedCode: String?) {
        pending.remove(id)?.complete(ReviewResult.Rejected(reason, editedCode))
    }
}
```

### 5.2 McpReviewNotificationProvider

```kotlin
class McpReviewNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<FileEditor, JComponent?>? {
        if (!file.path.contains(".idea/mcp-run/") || !file.name.endsWith(".kt")) return null
        if (!file.path.contains("/pending/")) return null

        val id = extractExecutionId(file) ?: return null

        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text("MCP Script awaiting review")
                createActionLabel("Approve & Execute") {
                    project.service<ReviewManager>().approve(id)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
                createActionLabel("Reject") {
                    val editedCode = FileDocumentManager.getInstance()
                        .getDocument(file)?.text
                    project.service<ReviewManager>().reject(id, "Rejected", editedCode)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }
}
```

---

## Phase 6: Testing

### 6.1 Unit Tests

```kotlin
class ScriptExecutorTest : BasePlatformTestCase() {
    @Test
    fun `simple script executes`() {
        val result = executor.execute(id, """
            execute {
                println("Hello")
            }
        """)
        assertIs<ExecutionResult.Success>(result)
    }

    @Test
    fun `script without execute block fails`() {
        val result = executor.execute(id, """
            println("No execute block")
        """)
        assertIs<ExecutionResult.Error>(result)
    }

    @Test
    fun `waitForSmartMode waits for indexing`() { ... }

    @Test
    fun `readAction runs in read context`() { ... }
}
```

### 6.2 Integration Tests

```kotlin
class McpIntegrationTest : HeavyPlatformTestCase() {
    @Test
    fun `execute_code returns execution_id`() { ... }

    @Test
    fun `get_result returns output`() { ... }
}
```

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
| MCP Integration | McpToolset only (no REST fallback) |
| Target Version | IntelliJ 2025.3+ (sinceBuild: 252.1) |
| Entry Point | `execute { }` (McpScriptContext is the receiver) |
| Script Engine | IdeScriptEngineManager + AllPluginsLoader |
| Execution Architecture | Two-phase: CodeEvalManager (compile) + ScriptExecutor (run) |
| CoroutineScope | Service-injected, Dispatchers.IO + withTimeout |
| McpScriptContext | Has disposable property, NOT Disposable itself |
| Read/Write Actions | NOT part of context, use IntelliJ's coroutine-aware APIs |
| Review Mode | ALWAYS default, TRUSTED = trust all callers |
| Execution ID | `{hash-3}-{YYYY-MM-DD}T{HH-MM-SS}-{payload-10}` |
| Response Model | Polling via get_result (no streaming) |
| Language | Kotlin only (v1) |
| Slots/Commands | Deferred to v2 |
| Compilation | Synchronous in CodeEvalManager, before execution phase |
