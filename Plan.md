# Implementation Plan

This plan reflects the finalized design from [Discussions.md](Discussions.md).

---

## Phase 1: Project Setup and Dependencies

### 1.1 Update build.gradle.kts
- Add Ktor dependencies for HTTP server and SSE
- Add Kotlin compiler embeddable dependency
- Add Gson or Jackson for JSON serialization
- Add kotlinx-coroutines dependencies
- Configure plugin to bundle these dependencies

```kotlin
dependencies {
    // Ktor for HTTP server
    implementation("io.ktor:ktor-server-core:2.x")
    implementation("io.ktor:ktor-server-netty:2.x")
    implementation("io.ktor:ktor-server-sse:2.x")

    // Kotlin compiler for script compilation
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}
```

### 1.2 Create package structure
```
src/main/kotlin/com/jonnyzzz/intellij/mcp/
├── server/
│   ├── McpServer.kt              # HTTP server, routing
│   ├── McpProtocol.kt            # MCP JSON-RPC handling
│   └── SseHandler.kt             # SSE streaming support
├── execution/
│   ├── ExecutionManager.kt       # Manages execution queue
│   ├── ExecutionContext.kt       # Single execution state
│   ├── ScriptCompiler.kt         # Kotlin compilation
│   ├── ScriptClassLoader.kt      # Custom classloader
│   └── OutputCapture.kt          # Captures output to file
├── context/
│   ├── McpScriptContext.kt       # Interface for scripts
│   └── McpScriptContextImpl.kt   # Implementation
├── storage/
│   ├── ExecutionStorage.kt       # File-based storage
│   ├── SlotStorage.kt            # Slot read/write
│   └── CommandRegistry.kt        # Dynamic commands
├── review/
│   ├── ReviewManager.kt          # Code review workflow
│   ├── ReviewPanel.kt            # UI panel
│   └── ReviewEditorProvider.kt   # Editor integration
├── tools/
│   ├── ListProjectsTool.kt
│   ├── ExecuteCodeTool.kt
│   ├── GetResultTool.kt
│   ├── CancelExecutionTool.kt
│   ├── SlotTools.kt
│   └── CommandTools.kt
└── McpSteroidPlugin.kt           # Plugin entry point
```

### 1.3 Update plugin.xml
- Register application service for McpServer
- Register project service for slot storage
- Register editor provider for review
- Register tool window for execution history (optional)

---

## Phase 2: MCP Server Infrastructure

### 2.1 Implement McpServer
**File**: `server/McpServer.kt`

- Start Ktor HTTP server on plugin initialization
- Default port 11993, configurable via Registry
- Auto-allocate port if busy (try 11993, 11994, 11995...)
- Write active port to `.idea/mcp-run/server.port`
- Implement graceful shutdown on plugin unload

```kotlin
class McpServer : Disposable {
    private var server: ApplicationEngine? = null
    private var activePort: Int = DEFAULT_PORT

    fun start() {
        activePort = findAvailablePort(DEFAULT_PORT)
        server = embeddedServer(Netty, port = activePort) {
            routing {
                post("/mcp") { handleMcpRequest() }
                get("/execution/{id}/result") { handleGetResult() }
            }
        }.start(wait = false)
    }
}
```

### 2.2 Implement MCP Protocol Handler
**File**: `server/McpProtocol.kt`

- Parse JSON-RPC 2.0 requests
- Route to appropriate tool handlers
- Format JSON-RPC responses
- Handle errors with proper error codes

### 2.3 Implement SSE Handler
**File**: `server/SseHandler.kt`

- Support `stream=true` parameter
- Send heartbeat every 15 seconds
- Event types: `message`, `status`, `complete`
- Clean connection close on completion

### 2.4 Implement list_projects Tool
**File**: `tools/ListProjectsTool.kt`

- Query `ProjectManager.getInstance().openProjects`
- Return `[{ name, path }]`
- Simple, no async needed

---

## Phase 3: Execution Storage

### 3.1 Implement ExecutionStorage
**File**: `storage/ExecutionStorage.kt`

- Generate execution ID: `YYYYMMDD/HHMMSS-<random8>`
- Create folder structure under `.idea/mcp-run/`
- Write `script.kt`, `parameters.json`
- Append to `output.jsonl` during execution
- Write `result.json` on completion

```kotlin
class ExecutionStorage(private val project: Project) {
    fun createExecution(code: String, params: ExecutionParams): ExecutionId
    fun appendOutput(id: ExecutionId, message: OutputMessage)
    fun readOutput(id: ExecutionId, offset: Int): List<OutputMessage>
    fun writeResult(id: ExecutionId, result: ExecutionResult)
    fun getResult(id: ExecutionId): ExecutionResult?
}
```

### 3.2 Implement OutputCapture
**File**: `execution/OutputCapture.kt`

- Thread-safe output collection
- Writes to file immediately (no memory buffering)
- Supports JSON lines format
- Message types: `out`, `json`, `log`, `err`

```kotlin
data class OutputMessage(
    val ts: Long,
    val type: String,  // out, json, log, err
    val msg: String? = null,
    val level: String? = null,  // for log type
    val data: Any? = null       // for json type
)
```

---

## Phase 4: Script Compilation

### 4.1 Implement ScriptClassLoader
**File**: `execution/ScriptClassLoader.kt`

- Create fresh classloader per execution
- Parent: IntelliJ platform classloader
- Add specified plugin classloaders
- Default: all enabled plugins (TODO: get details)
- Ensure McpScriptContext is loadable

```kotlin
class ScriptClassLoader(
    parent: ClassLoader,
    pluginIds: List<String>
) : URLClassLoader(arrayOf(), parent) {
    init {
        // Add plugin classpaths
        pluginIds.forEach { addPluginToClasspath(it) }
    }
}
```

### 4.2 Implement ScriptCompiler
**File**: `execution/ScriptCompiler.kt`

- Use kotlin-compiler-embeddable
- Add predefined imports:
  ```kotlin
  import com.intellij.openapi.project.*
  import com.intellij.openapi.application.*
  import com.intellij.openapi.vfs.*
  import com.intellij.openapi.editor.*
  import com.intellij.openapi.fileEditor.*
  import com.intellij.openapi.command.*
  import com.intellij.psi.*
  import kotlinx.coroutines.*
  ```
- Compile to in-memory classes
- Return detailed errors with line numbers

```kotlin
class ScriptCompiler(private val classLoader: ScriptClassLoader) {
    fun compile(code: String): CompilationResult
}

sealed class CompilationResult {
    data class Success(val mainClass: Class<*>) : CompilationResult()
    data class Error(val errors: List<CompilationError>) : CompilationResult()
}

data class CompilationError(
    val line: Int,
    val column: Int,
    val message: String,
    val severity: String
)
```

---

## Phase 5: Script Execution

### 5.1 Implement McpScriptContext
**File**: `context/McpScriptContext.kt` (interface)
**File**: `context/McpScriptContextImpl.kt` (implementation)

```kotlin
interface McpScriptContext {
    val project: Project

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

    // Dynamic commands
    fun registerCommand(name: String, description: String, handler: suspend (String) -> String)
    fun unregisterCommand(name: String)

    // Reflection helpers
    fun listServices(): List<String>
    fun listExtensionPoints(): List<String>
    fun describeClass(className: String): String
}
```

### 5.2 Implement ExecutionContext
**File**: `execution/ExecutionContext.kt`

- Holds state for single execution
- Manages classloader lifecycle
- Captures output via McpScriptContextImpl
- Handles timeout and cancellation

```kotlin
class ExecutionContext(
    val id: ExecutionId,
    val project: Project,
    val storage: ExecutionStorage,
    val timeout: Duration
) {
    private var job: Job? = null
    private val classLoader: ScriptClassLoader

    suspend fun execute(compiledClass: Class<*>): ExecutionResult
    fun cancel()
}
```

### 5.3 Implement ExecutionManager
**File**: `execution/ExecutionManager.kt`

- Sequential execution queue
- Manages pending/running executions
- Tracks execution by ID
- Handles timeout scheduling

```kotlin
class ExecutionManager : Disposable {
    private val queue = Channel<ExecutionRequest>(Channel.UNLIMITED)
    private val executions = ConcurrentHashMap<ExecutionId, ExecutionContext>()

    suspend fun submit(request: ExecutionRequest): ExecutionId
    fun getStatus(id: ExecutionId): ExecutionStatus
    fun cancel(id: ExecutionId): Boolean
}
```

---

## Phase 6: Code Review

### 6.1 Implement ReviewManager
**File**: `review/ReviewManager.kt`

- Manages pending reviews
- Opens code in editor
- Waits for user decision (blocking the execution flow)
- Collects user edits/comments on rejection

```kotlin
class ReviewManager(private val project: Project) {
    suspend fun requestReview(
        executionId: ExecutionId,
        code: String,
        classLoader: ScriptClassLoader
    ): ReviewResult
}

sealed class ReviewResult {
    object Approved : ReviewResult()
    data class Rejected(
        val reason: String,
        val editedCode: String?,
        val comments: List<String>
    ) : ReviewResult()
}
```

### 6.2 Implement ReviewPanel
**File**: `review/ReviewPanel.kt`

- Editor panel with syntax highlighting
- Approve/Reject buttons
- Optional comment field
- Shows in same classpath context (for code resolution)

### 6.3 Implement ReviewEditorProvider
**File**: `review/ReviewEditorProvider.kt`

- Opens `.kt` files from `.idea/mcp-run/pending/`
- Configures editor with correct SDK/classpath
- Adds review toolbar

---

## Phase 7: MCP Tools Implementation

### 7.1 Implement execute_code Tool
**File**: `tools/ExecuteCodeTool.kt`

```kotlin
suspend fun executeCode(
    projectPath: String,
    code: String,
    language: String = "kotlin",
    plugins: List<String>? = null,
    timeout: Int = 60,
    showReviewOnError: Boolean = false
): ExecuteCodeResponse {
    // 1. Find project
    // 2. Create execution storage
    // 3. Create classloader with plugins
    // 4. Compile code (blocking)
    // 5. On error: return or show review
    // 6. On success: start async execution
    // 7. Return execution_id
}
```

### 7.2 Implement get_result Tool
**File**: `tools/GetResultTool.kt`

```kotlin
suspend fun getResult(
    executionId: String,
    stream: Boolean = false,
    offset: Int = 0
): Flow<GetResultResponse> {
    // 1. Load execution from storage
    // 2. Read output from offset
    // 3. If stream: emit updates until complete
    // 4. Return current state
}
```

### 7.3 Implement cancel_execution Tool
**File**: `tools/CancelExecutionTool.kt`

```kotlin
fun cancelExecution(executionId: String): CancelResponse {
    // 1. Find execution
    // 2. Cancel if running or pending review
    // 3. Update result.json
    // 4. Return confirmation
}
```

### 7.4 Implement Slot Tools
**File**: `tools/SlotTools.kt`

- `read_slot(project_path, slot_name)`
- `write_slot(project_path, slot_name, value)`
- Project-scoped storage

### 7.5 Implement Command Tools
**File**: `tools/CommandTools.kt`

- `list_commands(project_path)`
- `call_command(project_path, command_name, parameters)`

---

## Phase 8: Testing

### 8.1 Unit Tests
- ScriptCompiler: compilation success/failure
- ScriptClassLoader: plugin loading
- ExecutionStorage: file operations
- OutputCapture: JSON lines format
- McpProtocol: JSON-RPC parsing

### 8.2 Integration Tests
- Server startup/shutdown
- execute_code → get_result flow
- Compilation error handling
- Runtime exception handling
- Timeout and cancellation
- SSE streaming
- Slot read/write
- Dynamic command registration

### 8.3 UI Tests (optional)
- Review panel approve/reject
- Editor integration

---

## Phase 9: Polish and Documentation

### 9.1 Error Handling
- Graceful port allocation failure
- Classloader cleanup on failure
- Timeout edge cases
- Connection drops during SSE

### 9.2 Logging
- DEBUG: all MCP requests/responses
- INFO: execution start/complete
- WARN: timeout, cancellation
- ERROR: compilation/runtime errors

### 9.3 Documentation Updates
- Update README with final API
- Add usage examples
- Document error codes
- Add troubleshooting section

---

## Phase 10: Future Enhancements (Out of Scope)

- Groovy language support
- IntelliJ project detection with enhanced context
- Third-party code verification
- Compilation caching
- REPL mode
- Stdio transport proxy

---

## Implementation Order

Recommended order for incremental development:

1. **Phase 1**: Project setup (dependencies, structure)
2. **Phase 2.1-2.2**: Basic HTTP server with MCP protocol
3. **Phase 2.4**: list_projects tool (simplest, validates server)
4. **Phase 3**: Execution storage (needed for everything else)
5. **Phase 4**: Script compilation
6. **Phase 5**: Script execution (without review)
7. **Phase 7.1-7.3**: execute_code, get_result, cancel_execution
8. **Phase 2.3**: SSE streaming
9. **Phase 8.1-8.2**: Tests
10. **Phase 6**: Code review (can be added later)
11. **Phase 7.4-7.5**: Slots and commands
12. **Phase 9**: Polish

---

## Open Items

- [ ] Confirm default plugin list (currently: all enabled)
- [ ] Confirm Ktor vs Netty bundled with IntelliJ
- [ ] Design stdio proxy documentation
- [ ] Review panel UI mockup
