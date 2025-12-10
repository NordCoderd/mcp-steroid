# Implementation Plan

This plan reflects the finalized design from [Discussions.md](Discussions.md) and research into IntelliJ Platform APIs.

---

## Research Findings

### Script Engine API (Use This!)

IntelliJ has a built-in script engine infrastructure we should leverage:

**Key Files** (from intellij-community):
- `platform/ide-core-impl/src/com/intellij/ide/script/IdeScriptEngineManagerImpl.java`
- `platform/ide-core-impl/src/com/intellij/ide/script/IdeScriptEngine.java`
- `platform/lang-impl/src/com/intellij/ide/script/IDE.java`
- `platform/lang-impl/src/com/intellij/ide/script/IdeConsoleScriptBindings.java`

**AllPluginsLoader.INSTANCE** - ClassLoader that delegates to ALL plugin classloaders:
```java
// From IdeScriptEngineManagerImpl.java line 155
IdeScriptEngine engine = new EngineImpl(scriptEngineFactory,
    loader == null ? AllPluginsLoader.INSTANCE : loader);
```

**Pattern**:
1. Use `IdeScriptEngineManager.getInstance().getEngineByFileExtension("kts", null)` for Kotlin
2. Engine automatically uses `AllPluginsLoader.INSTANCE` which includes all plugins
3. Bind our `McpScriptContext` via `engine.setBinding("ctx", context)`
4. Capture output via `engine.setStdOut(writer)` and `engine.setStdErr(writer)`
5. Execute via `engine.eval(script)`

**IDE Binding** - IntelliJ provides an `IDE` class with `project`, `application`, `print()`, `error()`:
```java
IdeConsoleScriptBindings.ensureIdeIsBound(project, engine);
// Script can access: IDE.project, IDE.application, IDE.print(obj)
```

### HTTP Server (Built-in!)

IntelliJ has a built-in HTTP server using Netty. We should use its extension points:

**Key Files**:
- `platform/built-in-server/src/org/jetbrains/io/BuiltInServer.kt`
- `platform/built-in-server/src/org/jetbrains/ide/RestService.kt`
- `platform/platform-util-netty/src/org/jetbrains/ide/HttpRequestHandler.kt`

**Extension Point**: `com.intellij.httpRequestHandler`

**Pattern** - Extend `RestService` for REST API:
```kotlin
class McpRestService : RestService() {
    override fun getServiceName() = "mcp"  // → /api/mcp

    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        // Handle MCP requests
        // Return null for success, error message for failure
        return null
    }
}
```

**Registration** in plugin.xml:
```xml
<extensions defaultExtensionNs="com.intellij">
    <httpRequestHandler implementation="com.jonnyzzz.intellij.mcp.server.McpRestService"/>
</extensions>
```

**Benefits**:
- No need to manage port ourselves (uses IntelliJ's port 63342-63361)
- Built-in rate limiting, CORS, security
- SSE support via Netty channels
- Proper lifecycle management

**Alternative**: If we need our own port (11993), use `CustomPortServerManager`:
```kotlin
class McpServerManager : CustomPortServerManager() {
    override fun getPort() = 11993
    override fun isAvailableExternally() = true
}
```

### Editor Notification Panel (Review UI)

**Extension Point**: `com.intellij.editorNotificationProvider`

**Key Files**:
- `platform/platform-api/src/com/intellij/ui/EditorNotificationProvider.java`
- `platform/platform-api/src/com/intellij/ui/EditorNotificationPanel.java`

**Pattern**:
```kotlin
class McpReviewNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<FileEditor, JComponent?>? {
        // Only show for files in .idea/mcp-run/pending/
        if (!file.path.contains("mcp-run/pending")) return null

        return Function { fileEditor ->
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Warning).apply {
                text("MCP Script pending review")
                createActionLabel("Approve") { approveExecution(file) }
                createActionLabel("Reject") { rejectExecution(file) }
            }
        }
    }
}
```

**Registration**:
```xml
<editorNotificationProvider
    implementation="com.jonnyzzz.intellij.mcp.review.McpReviewNotificationProvider"/>
```

---

## Phase 1: Project Setup

### 1.1 Update build.gradle.kts
```kotlin
dependencies {
    // IntelliJ Platform provides:
    // - Netty (built-in server)
    // - Kotlin scripting engine (via kotlin plugin)
    // - Gson (bundled)
    // - Coroutines (bundled)

    // We might need kotlin-scripting if not using JSR-223:
    // implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.1.0")
}

intellijPlatform {
    // Depend on built-in-server for HTTP
    bundledPlugin("com.intellij.java")  // For Kotlin scripting support
}
```

### 1.2 Update plugin.xml
```xml
<idea-plugin>
    <id>com.jonnyzzz.intellij.mcp-steroid</id>
    <name>IntelliJ MCP Steroid</name>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.lang</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- HTTP endpoint -->
        <httpRequestHandler
            implementation="com.jonnyzzz.intellij.mcp.server.McpRestService"/>

        <!-- Review panel in editor -->
        <editorNotificationProvider
            implementation="com.jonnyzzz.intellij.mcp.review.McpReviewNotificationProvider"/>

        <!-- Project service for execution management -->
        <projectService
            serviceImplementation="com.jonnyzzz.intellij.mcp.execution.ExecutionManager"/>

        <!-- Project service for slots -->
        <projectService
            serviceImplementation="com.jonnyzzz.intellij.mcp.storage.SlotStorage"/>
    </extensions>
</idea-plugin>
```

### 1.3 Package Structure
```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── server/
│   ├── McpRestService.kt         # RestService extension for /api/mcp
│   ├── McpProtocol.kt            # MCP JSON-RPC message handling
│   └── SseSupport.kt             # SSE streaming utilities
├── execution/
│   ├── ExecutionManager.kt       # Project service, manages queue
│   ├── ScriptExecutor.kt         # Uses IdeScriptEngine
│   └── OutputCapture.kt          # Writer impl for capturing output
├── context/
│   └── McpScriptContext.kt       # Our context bound to scripts
├── storage/
│   ├── ExecutionStorage.kt       # File-based execution history
│   ├── SlotStorage.kt            # Project service for slots
│   └── CommandRegistry.kt        # Dynamic command storage
├── review/
│   ├── ReviewManager.kt          # Manages pending reviews
│   └── McpReviewNotificationProvider.kt  # Editor panel
└── tools/
    ├── ListProjectsTool.kt
    ├── ExecuteCodeTool.kt
    ├── GetResultTool.kt
    ├── CancelExecutionTool.kt
    ├── SlotTools.kt
    └── CommandTools.kt
```

---

## Phase 2: MCP Server (HTTP Endpoint)

### 2.1 Implement McpRestService
**File**: `server/McpRestService.kt`

```kotlin
class McpRestService : RestService() {
    override fun getServiceName() = "mcp"

    override fun isMethodSupported(method: HttpMethod) =
        method == HttpMethod.POST || method == HttpMethod.GET

    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        val path = urlDecoder.path()
        return when {
            path.endsWith("/mcp") -> handleMcpRequest(request, context)
            path.contains("/execution/") && path.endsWith("/result") ->
                handleGetResult(urlDecoder, request, context)
            else -> "Unknown endpoint"
        }
    }
}
```

### 2.2 Implement MCP Protocol
**File**: `server/McpProtocol.kt`

- Parse JSON-RPC 2.0 requests
- Route to tool handlers
- Handle SSE streaming for get_result

### 2.3 Implement list_projects Tool
```kotlin
fun listProjects(): List<ProjectInfo> {
    return ProjectManager.getInstance().openProjects.map { project ->
        ProjectInfo(
            name = project.name,
            path = project.basePath ?: ""
        )
    }
}
```

---

## Phase 3: Script Execution Engine

### 3.1 Implement ScriptExecutor
**File**: `execution/ScriptExecutor.kt`

Uses IntelliJ's built-in script engine with AllPluginsLoader:

```kotlin
class ScriptExecutor(private val project: Project) {

    fun execute(code: String, context: McpScriptContext): ExecutionResult {
        val engineManager = IdeScriptEngineManager.getInstance()

        // Get Kotlin script engine with ALL plugins in classpath
        val engine = engineManager.getEngineByFileExtension("kts", null)
            ?: return ExecutionResult.Error("Kotlin script engine not available")

        // Bind our context
        engine.setBinding("ctx", context)

        // Also bind IDE for compatibility
        IdeConsoleScriptBindings.ensureIdeIsBound(project, engine)

        // Capture output
        val outputCapture = OutputCapture(context.executionId, storage)
        engine.setStdOut(outputCapture.stdoutWriter)
        engine.setStdErr(outputCapture.stderrWriter)

        // Wrap code with imports and main function detection
        val wrappedCode = wrapCode(code)

        return try {
            val result = engine.eval(wrappedCode)
            ExecutionResult.Success(result)
        } catch (e: IdeScriptException) {
            ExecutionResult.RuntimeError(e)
        }
    }

    private fun wrapCode(code: String): String {
        val imports = """
            import com.intellij.openapi.project.*
            import com.intellij.openapi.application.*
            import com.intellij.openapi.vfs.*
            import com.intellij.psi.*
            import kotlinx.coroutines.*
        """.trimIndent()

        return "$imports\n\n$code"
    }
}
```

### 3.2 Implement McpScriptContext
**File**: `context/McpScriptContext.kt`

```kotlin
class McpScriptContext(
    val project: Project,
    private val executionId: ExecutionId,
    private val outputCapture: OutputCapture,
    private val slotStorage: SlotStorage,
    private val commandRegistry: CommandRegistry
) {
    fun println(message: Any?) = outputCapture.println(message)
    fun print(message: Any?) = outputCapture.print(message)
    fun printJson(obj: Any?) = outputCapture.printJson(obj)

    fun logInfo(message: String) = outputCapture.log("info", message)
    fun logWarn(message: String) = outputCapture.log("warn", message)
    fun logError(message: String, t: Throwable? = null) = outputCapture.log("error", message, t)

    fun readSlot(name: String): String? = slotStorage.read(name)
    fun writeSlot(name: String, value: String) = slotStorage.write(name, value)

    fun registerCommand(name: String, description: String, handler: (String) -> String) {
        commandRegistry.register(name, description, handler)
    }

    fun unregisterCommand(name: String) = commandRegistry.unregister(name)

    // Reflection helpers
    fun listServices(): List<String> = // ...
    fun listExtensionPoints(): List<String> = // ...
    fun describeClass(className: String): String = // ...
}
```

### 3.3 Implement OutputCapture
**File**: `execution/OutputCapture.kt`

```kotlin
class OutputCapture(
    private val executionId: ExecutionId,
    private val storage: ExecutionStorage
) {
    val stdoutWriter = object : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            val text = String(cbuf, off, len).trimEnd()
            if (text.isNotEmpty()) {
                storage.appendOutput(executionId, OutputMessage(
                    ts = System.currentTimeMillis(),
                    type = "out",
                    msg = text
                ))
            }
        }
        override fun flush() {}
        override fun close() {}
    }

    // Similar for stderrWriter, log(), printJson()
}
```

---

## Phase 4: Execution Storage

### 4.1 Implement ExecutionStorage
**File**: `storage/ExecutionStorage.kt`

```kotlin
class ExecutionStorage(private val project: Project) {
    private val baseDir: Path
        get() = Paths.get(project.basePath!!, ".idea", "mcp-run")

    fun createExecution(code: String, params: ExecutionParams): ExecutionId {
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
        val random = UUID.randomUUID().toString().take(8)
        val id = ExecutionId("$date/$time-$random")

        val dir = baseDir.resolve(id.value)
        Files.createDirectories(dir)

        Files.writeString(dir.resolve("script.kt"), code)
        Files.writeString(dir.resolve("parameters.json"), gson.toJson(params))

        return id
    }

    fun appendOutput(id: ExecutionId, message: OutputMessage) {
        val file = baseDir.resolve(id.value).resolve("output.jsonl")
        Files.writeString(file, gson.toJson(message) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun readOutput(id: ExecutionId, offset: Int): List<OutputMessage> {
        val file = baseDir.resolve(id.value).resolve("output.jsonl")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file)
            .drop(offset)
            .map { gson.fromJson(it, OutputMessage::class.java) }
    }
}
```

---

## Phase 5: Code Review

### 5.1 Implement ReviewManager
**File**: `review/ReviewManager.kt`

```kotlin
class ReviewManager(private val project: Project) {
    private val pendingReviews = ConcurrentHashMap<ExecutionId, CompletableDeferred<ReviewResult>>()

    suspend fun requestReview(executionId: ExecutionId, code: String): ReviewResult {
        // Save to pending folder
        val pendingFile = saveToPending(executionId, code)

        // Open in editor
        withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).openFile(pendingFile, true)
        }

        // Wait for user decision
        val deferred = CompletableDeferred<ReviewResult>()
        pendingReviews[executionId] = deferred

        return deferred.await()
    }

    fun approve(executionId: ExecutionId) {
        pendingReviews.remove(executionId)?.complete(ReviewResult.Approved)
    }

    fun reject(executionId: ExecutionId, reason: String, editedCode: String?) {
        pendingReviews.remove(executionId)?.complete(
            ReviewResult.Rejected(reason, editedCode)
        )
    }
}
```

### 5.2 Implement McpReviewNotificationProvider
**File**: `review/McpReviewNotificationProvider.kt`

```kotlin
class McpReviewNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<FileEditor, JComponent?>? {
        // Only show for pending review files
        if (!file.path.contains(".idea/mcp-run/pending/")) return null

        val executionId = extractExecutionId(file) ?: return null
        val reviewManager = project.service<ReviewManager>()

        return Function { fileEditor ->
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Warning).apply {
                text("MCP Script awaiting review")

                createActionLabel("Approve & Execute") {
                    reviewManager.approve(executionId)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }

                createActionLabel("Reject") {
                    // Could show dialog for reason
                    val editor = FileEditorManager.getInstance(project)
                        .getSelectedTextEditor()
                    val editedCode = editor?.document?.text
                    reviewManager.reject(executionId, "Rejected by user", editedCode)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }
}
```

---

## Phase 6: MCP Tools Implementation

### 6.1 execute_code
```kotlin
suspend fun executeCode(params: ExecuteCodeParams): ExecuteCodeResponse {
    val project = findProject(params.projectPath)
        ?: return ExecuteCodeResponse.error("Project not found")

    val executionManager = project.service<ExecutionManager>()
    val storage = project.service<ExecutionStorage>()

    // Create execution record
    val executionId = storage.createExecution(params.code, params)

    // Compile/validate first (blocking)
    val validationResult = executionManager.validate(params.code)
    if (validationResult is ValidationResult.Error) {
        if (params.showReviewOnError) {
            // Open for review even with errors
            project.service<ReviewManager>().requestReview(executionId, params.code)
        }
        return ExecuteCodeResponse(
            executionId = executionId.value,
            status = "compilation_error",
            errors = validationResult.errors
        )
    }

    // Start execution (async)
    executionManager.submit(executionId, params)

    return ExecuteCodeResponse(
        executionId = executionId.value,
        status = if (reviewEnabled) "pending_review" else "running"
    )
}
```

### 6.2 get_result
```kotlin
suspend fun getResult(params: GetResultParams): Flow<GetResultResponse> = flow {
    val project = findProject(params.projectPath)
    val storage = project.service<ExecutionStorage>()
    val executionManager = project.service<ExecutionManager>()

    if (params.stream) {
        // Stream updates until complete
        var offset = params.offset
        while (true) {
            val output = storage.readOutput(params.executionId, offset)
            val status = executionManager.getStatus(params.executionId)

            emit(GetResultResponse(
                executionId = params.executionId,
                status = status,
                output = output
            ))

            offset += output.size

            if (status.isTerminal()) break
            delay(100) // Poll interval
        }
    } else {
        // Single response
        emit(GetResultResponse(
            executionId = params.executionId,
            status = executionManager.getStatus(params.executionId),
            output = storage.readOutput(params.executionId, params.offset)
        ))
    }
}
```

---

## Phase 7: Testing

### 7.1 Unit Tests

**ScriptExecutor Tests**:
```kotlin
class ScriptExecutorTest {
    @Test
    fun `simple script executes successfully`()

    @Test
    fun `script with syntax error returns compilation error`()

    @Test
    fun `script can access project via context`()

    @Test
    fun `AllPluginsLoader provides access to all plugin classes`()

    @Test
    fun `output is captured correctly`()
}
```

**ExecutionStorage Tests**:
```kotlin
class ExecutionStorageTest {
    @Test
    fun `creates execution folder with correct structure`()

    @Test
    fun `appends output to jsonl file`()

    @Test
    fun `reads output with offset`()
}
```

### 7.2 Integration Tests

```kotlin
class McpServerIntegrationTest : BasePlatformTestCase() {
    @Test
    fun `execute_code compiles and runs script`()

    @Test
    fun `get_result returns execution output`()

    @Test
    fun `cancel_execution stops running script`()

    @Test
    fun `slots persist between executions`()
}
```

---

## Phase 8: Documentation

### 8.1 Stdio Proxy Documentation
See [STDIO_PROXY.md](STDIO_PROXY.md)

### 8.2 Update README
- Final API documentation
- Usage examples
- Troubleshooting

---

## Implementation Order

1. **Phase 1**: Project setup, plugin.xml
2. **Phase 2.1-2.2**: McpRestService + MCP protocol
3. **Phase 2.3**: list_projects (validates server works)
4. **Phase 3**: Script execution with IdeScriptEngine
5. **Phase 4**: Execution storage
6. **Phase 6.1**: execute_code tool
7. **Phase 6.2**: get_result tool
8. **Phase 7**: Tests
9. **Phase 5**: Code review (can add later)
10. **Phase 8**: Documentation

---

## Key Decisions

| Topic | Decision |
|-------|----------|
| Script Engine | Use `IdeScriptEngineManager` + `AllPluginsLoader.INSTANCE` |
| HTTP Server | Use IntelliJ's built-in `RestService` extension point |
| Review Panel | Use `EditorNotificationProvider` extension point |
| Classloader | `AllPluginsLoader.INSTANCE` already provides all plugins |
| Port | Use IntelliJ's built-in server port (63342+) or custom via `CustomPortServerManager` |
