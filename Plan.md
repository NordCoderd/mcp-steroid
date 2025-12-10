# Implementation Plan

This plan reflects decisions from [Discussions.md](Discussions.md).

---

## Key Architecture Decisions

### MCP Integration Options

**Option A: IntelliJ's Built-in MCP Server (Preferred for 2024.3+)**

IntelliJ has a bundled MCP server plugin (`com.intellij.mcpServer`):

```kotlin
class SteroidsMcpToolset : McpToolset {
    @McpTool
    @McpDescription("Execute Kotlin code in IDE context")
    suspend fun execute_code(
        @McpDescription("Project directory path") projectPath: String,
        @McpDescription("Kotlin code to execute") code: String,
        @McpDescription("Execution timeout in seconds") timeout: Int = 60
    ): ExecuteCodeResult { ... }
}
```

Registration in plugin.xml:
```xml
<depends optional="true" config-file="mcp-integration.xml">com.intellij.mcpServer</depends>
```

**Option B: REST Endpoint (Fallback)**

For older IntelliJ versions, use RestService at `/api/steroids-mcp`.

### Script Execution Model

**Two-layer API**:

1. `McpScriptScope` - Bound to script engine, single method `execute { }`
2. `McpScriptContext` - Full API, passed to execute block

```kotlin
// User writes:
execute { ctx ->
    ctx.waitForSmartMode()
    ctx.println("Hello!")
}
```

This ensures we control the execution context and coroutine setup.

### Classloader

Use `IdeScriptEngineManager` with `AllPluginsLoader.INSTANCE` (automatic, no config needed).

### Review Mode

- Enabled by default (`ALWAYS`)
- Configurable via IntelliJ Registry
- All requests logged regardless of mode

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
        intellijIdeaCommunity("2024.2.4")
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

    <!-- Optional MCP server integration -->
    <depends optional="true" config-file="mcp-integration.xml">com.intellij.mcpServer</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- REST endpoint fallback -->
        <httpRequestHandler implementation="com.jonnyzzz.intellij.mcp.server.McpRestService"/>

        <!-- Review panel -->
        <editorNotificationProvider
            implementation="com.jonnyzzz.intellij.mcp.review.McpReviewNotificationProvider"/>

        <!-- Project services -->
        <projectService serviceImplementation="com.jonnyzzz.intellij.mcp.execution.ExecutionManager"/>
        <projectService serviceImplementation="com.jonnyzzz.intellij.mcp.storage.SlotStorage"/>
        <projectService serviceImplementation="com.jonnyzzz.intellij.mcp.storage.ExecutionStorage"/>

        <!-- Registry keys -->
        <registryKey key="mcp.steroids.review.mode" defaultValue="ALWAYS"
            description="Review mode: ALWAYS, TRUSTED, NEVER"/>
        <registryKey key="mcp.steroids.review.timeout" defaultValue="300"
            description="Review timeout in seconds"/>
        <registryKey key="mcp.steroids.execution.timeout" defaultValue="60"
            description="Execution timeout in seconds"/>
    </extensions>
</idea-plugin>
```

### 1.3 Create mcp-integration.xml

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij.mcpServer">
        <mcpToolset implementation="com.jonnyzzz.intellij.mcp.SteroidsMcpToolset"/>
    </extensions>
</idea-plugin>
```

### 1.4 Package Structure

```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── SteroidsMcpToolset.kt          # MCP toolset (Option A)
├── server/
│   ├── McpRestService.kt          # REST endpoint (Option B)
│   └── McpProtocol.kt             # JSON-RPC handling
├── execution/
│   ├── ExecutionManager.kt        # Project service, queue
│   ├── ScriptExecutor.kt          # Uses IdeScriptEngine
│   ├── McpScriptScope.kt          # Bound to script engine
│   ├── McpScriptContext.kt        # Full context interface
│   ├── McpScriptContextImpl.kt    # Implementation
│   └── OutputCapture.kt           # Writer for output
├── storage/
│   ├── ExecutionStorage.kt        # File-based history
│   └── SlotStorage.kt             # Key-value slots
├── review/
│   ├── ReviewManager.kt           # Manages pending reviews
│   └── McpReviewNotificationProvider.kt
└── util/
    └── ProjectHash.kt             # 3-char project hash
```

---

## Phase 2: Core Execution Engine

### 2.1 McpScriptScope Interface

```kotlin
// Bound to script engine as "execute" function receiver
interface McpScriptScope {
    fun execute(block: suspend McpScriptContext.() -> Unit)
}
```

### 2.2 McpScriptContext Interface

```kotlin
interface McpScriptContext : Disposable {
    val project: Project
    val coroutineScope: CoroutineScope

    // Output
    fun println(message: Any?)
    fun print(message: Any?)
    fun printJson(obj: Any?)
    fun logInfo(message: String)
    fun logWarn(message: String)
    fun logError(message: String, throwable: Throwable? = null)

    // Slots
    fun readSlot(name: String): String?
    fun writeSlot(name: String, value: String)

    // IDE Utilities
    suspend fun waitForSmartMode()
    suspend fun <T> readAction(block: () -> T): T
    suspend fun <T> writeAction(block: () -> T): T

    // Reflection
    fun listServices(): List<String>
    fun listExtensionPoints(): List<String>
    fun describeClass(className: String): String
}
```

### 2.3 McpScriptContextImpl

```kotlin
class McpScriptContextImpl(
    override val project: Project,
    private val executionId: ExecutionId,
    private val outputCapture: OutputCapture,
    private val slotStorage: SlotStorage,
    parentScope: CoroutineScope
) : McpScriptContext {

    override val coroutineScope = parentScope.childScope(
        "McpScriptContext-${executionId}",
        SupervisorJob()
    )

    init {
        coroutineScope.coroutineContext.job.cancelOnDispose(this)
    }

    override suspend fun waitForSmartMode() {
        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                DumbService.getInstance(project).smartInvokeLater {
                    if (DumbService.isDumb(project)) {
                        waitForSmart()
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            waitForSmart()
        }
    }

    override suspend fun <T> readAction(block: () -> T): T =
        com.intellij.openapi.application.readAction { block() }

    override suspend fun <T> writeAction(block: () -> T): T =
        com.intellij.openapi.application.writeAction { block() }

    override fun dispose() {
        // Scope cancelled via cancelOnDispose
    }
}
```

### 2.4 ScriptExecutor

```kotlin
class ScriptExecutor(
    private val project: Project,
    private val executionStorage: ExecutionStorage,
    private val slotStorage: SlotStorage,
    private val parentScope: CoroutineScope
) {
    suspend fun execute(executionId: ExecutionId, code: String): ExecutionResult {
        val engineManager = IdeScriptEngineManager.getInstance()
        val engine = engineManager.getEngineByFileExtension("kts", null)
            ?: return ExecutionResult.Error("Kotlin script engine not available")

        val outputCapture = OutputCapture(executionId, executionStorage)
        engine.setStdOut(outputCapture.stdoutWriter)
        engine.setStdErr(outputCapture.stderrWriter)

        val context = McpScriptContextImpl(
            project, executionId, outputCapture, slotStorage, parentScope
        )

        var capturedBlock: (suspend McpScriptContext.() -> Unit)? = null

        val scope = object : McpScriptScope {
            override fun execute(block: suspend McpScriptContext.() -> Unit) {
                capturedBlock = block
            }
        }

        engine.setBinding("execute", scope::execute)

        val wrappedCode = wrapWithImports(code)

        try {
            // Compile and run script (captures the execute block)
            engine.eval(wrappedCode)

            // Now run the captured block
            val block = capturedBlock
                ?: return ExecutionResult.Error("Script must call execute { }")

            context.use {
                block(context)
            }

            return ExecutionResult.Success
        } catch (e: IdeScriptException) {
            return ExecutionResult.CompilationError(e.message ?: "Compilation failed")
        } catch (e: Exception) {
            return ExecutionResult.RuntimeError(e)
        }
    }

    private fun wrapWithImports(code: String): String {
        val imports = """
            import com.intellij.openapi.project.*
            import com.intellij.openapi.application.*
            import com.intellij.openapi.vfs.*
            import com.intellij.openapi.editor.*
            import com.intellij.openapi.fileEditor.*
            import com.intellij.openapi.command.*
            import com.intellij.psi.*
            import kotlinx.coroutines.*
        """.trimIndent()
        return "$imports\n\n$code"
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
        project.service<SlotStorage>(),
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
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
        val random = UUID.randomUUID().toString().take(8)

        val id = ExecutionId("$projectHash/$date/$time-$random")
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

### 3.2 SlotStorage

```kotlin
@Service(Service.Level.PROJECT)
class SlotStorage(private val project: Project) {
    private val slotsDir: Path
        get() = Path.of(project.basePath!!, ".idea", "mcp-run", "slots")

    fun read(name: String): String? {
        val file = slotsDir.resolve("$name.txt")
        return if (Files.exists(file)) Files.readString(file) else null
    }

    fun write(name: String, value: String) {
        Files.createDirectories(slotsDir)
        Files.writeString(slotsDir.resolve("$name.txt"), value)
    }
}
```

---

## Phase 4: MCP Integration

### 4.1 SteroidsMcpToolset (Option A)

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
        @McpDescription("Project directory path") projectPath: String,
        @McpDescription("Kotlin code to execute") code: String,
        @McpDescription("Timeout in seconds") timeout: Int = 60,
        @McpDescription("Show review on error") showReviewOnError: Boolean = false
    ): ExecuteCodeResult {
        val project = findProject(projectPath) ?: mcpFail("Project not found")
        val manager = project.service<ExecutionManager>()
        val id = manager.submit(code, ExecutionParams(timeout, showReviewOnError))
        return ExecuteCodeResult(id.value, manager.getStatus(id))
    }

    @McpTool
    @McpDescription("Get execution result")
    suspend fun get_result(
        @McpDescription("Execution ID") executionId: String,
        @McpDescription("Message offset") offset: Int = 0
    ): GetResultResponse {
        // Implementation
    }

    @McpTool
    @McpDescription("Cancel execution")
    fun cancel_execution(
        @McpDescription("Execution ID") executionId: String
    ): CancelResult {
        // Implementation
    }
}
```

### 4.2 McpRestService (Option B)

```kotlin
class McpRestService : RestService() {
    override fun getServiceName() = "steroids-mcp"

    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        // Parse JSON-RPC, route to handlers
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
        if (!file.path.contains(".idea/mcp-run/pending/")) return null

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
            execute { ctx ->
                ctx.println("Hello")
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

    @Test
    fun `slots persist between executions`() { ... }
}
```

---

## Implementation Order

1. **Phase 1**: Project setup, plugin.xml, package structure
2. **Phase 2**: McpScriptScope, McpScriptContext, ScriptExecutor
3. **Phase 3**: ExecutionStorage, SlotStorage
4. **Phase 4.2**: McpRestService (simpler, test first)
5. **Phase 6**: Tests
6. **Phase 5**: Code review (can defer)
7. **Phase 4.1**: SteroidsMcpToolset (if targeting 2024.3+)

---

## Key Decisions Summary

| Topic | Decision |
|-------|----------|
| MCP Integration | Option A (McpToolset) for 2024.3+, Option B (REST) fallback |
| Entry Point | `execute { ctx -> }` via McpScriptScope |
| Script Engine | IdeScriptEngineManager + AllPluginsLoader |
| CoroutineScope | Service-injected, childScope pattern |
| McpScriptContext | Implements Disposable, bound to scope |
| Review Mode | ALWAYS default, Registry configurable |
| Execution ID | `{hash}/{date}/{time}-{random}` |
| Language | Kotlin only (v1) |
| Dynamic Commands | Deferred to v2 |
