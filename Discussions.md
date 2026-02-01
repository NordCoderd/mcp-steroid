# Design Discussions

This file captures the design discussions and decisions made for the MCP server implementation.

---

## Initial Requirements (from user)

The IntelliJ plugin must start an MCP server with the following goals:

1. **Kotlin/Groovy Console Interface**: Offer a console interface to the requestor where user writes code executed in IntelliJ's own classpath.

2. **McpScriptContext Class**: A static class sent to execution context with functions to simplify execution.

3. **Code Execution Model**:
   - Code submitted to MCP server creates a dedicated classloader
   - Classloader has necessary plugins as parents
   - List of plugins is an API parameter
   - Classes are loaded and executed
   - Requestor sees output or compilation errors

4. **Entry Point**: Clear semantics for the entry point, promoted to MCP server specification.

5. **Code Storage**: All submitted code stored under `.idea/mcp-run/` folder.

6. **Single Code Block**: MCP server receives only one code block at a time.

7. **Dynamic Commands**: McpScriptContext allows code to register new MCP commands that run in background.

8. **Blocking Execution**: Code executes in IDE context as blocking function, call completes when function exits.

9. **Slots API**: Well-documented slots API for read/write to named slots.

10. **Project-Scoped**: List open projects with directories, all requests executed per-project (required parameter). McpScriptContext has getter for current project.

11. **Java Services for Compilation**: Use Java services to compile code, run in the classpath of code execution.

12. **IntelliJ Project Detection**: Special case where MCP server detects it's working on IntelliJ project and provides enhanced context.

13. **LLM Documentation**: Documentation must refer to https://github.com/intellij-community for API reference.

14. **Code Review Mode**: Mode (activated by default) where agent code is opened in editor for human review and manual approval before execution. Later: 3rd party tool integration for automatic checking.

15. **Reflection Guidance**: Recommend LLM to use reflection to understand runtime capabilities.

---

## Q&A Session

### MCP Protocol & Transport

**Q1: MCP Protocol Compliance** - Standard MCP uses stdio transport. Are you intentionally using HTTP instead?

**A1**: Yes, we use HTTP/TCP as the transport. Add standard documentation on how to connect it via stdio (proxy/adapter).

**Q2: Why port 11993?** Is this arbitrary or configurable?

**A2**: Port is configurable via IntelliJ Registry, 11993 is default. If port is busy, allocate another port automatically.

---

### Entry Point & Code Structure

**Q3: Return value handling** - How should scripts return data to the LLM?

**A3**: We cannot listen to stdout, so provide methods in context:
- `println` family of functions in context for output
- Function to send JSON back (use Gson or Jackson to serialize objects)
- Function to log info/warn/errors

**Q4: Imports in submitted code** - Should code include imports or auto-import?

**A4**: Add predefined set of `*` imports, document them, let agent know. It's assumed imports are included in code.

**Q5: Package declaration** - Required?

**A5**: Package declaration is optional and should not change the logic.

---

### Code Review Mode

**Q6: Blocking behavior during review** - How should LLM handle pending review?

**A6**:
- Make the call waiting (blocking)
- If protocol allows, let LLM know code waits approval
- Editing will not change the code
- Rejection message should include all changes and comments user made in file
- **Suggestion**: Make IntelliJ resolve the code in the correct (same) classpath

**Q7: What happens if user edits code?**

**A7**: User can only send the edited code back together with the rejection message.

---

### Classloader & Compilation

**Q8: Kotlin compiler availability** - How to handle Kotlin stdlib?

**A8**: Put the library onto our plugin classpath and use that to compile the code.

**Q9: Script dependencies on other scripts** - Can scripts import from previous executions?

**A9**: Script always executed in fresh classloader. No dependencies to previous code allowed. But it's the same JVM, so many ways to interact indirectly exist.

---

### Slots API

**Q10: Slot data types** - String only or structured?

**A10**: Support both JSON and String. But be careful - can only keep Strings inside, not objects (classloader gets GC-ed).

**Q11: Slot scope** - Project-scoped or global?

**A11**: Everything is project-scoped. Can be a project service in IntelliJ plugin, same as McpContext object.

---

### Dynamic Commands

**Q12: Handler execution context** - What classloader runs the handler?

**A12**: For every script execution:
1. Create a new platform classloader
2. Set all necessary plugins as parent classloaders (parameter of call)
3. Use classpath to compile the code
4. Use same classloader to execute the code
5. Once done, classloader will be GC-ed
6. Must not keep classloader in memory

**Q13: Command parameters** - What does ParameterSpec look like?

**A13**: Allow passing a string as command parameters, code can access it. (Related to Q12 - keep it simple due to classloader lifecycle)

---

### Plugin Dependencies

**Q14: When to add plugin dependencies** - Explicit call or in-code declaration?

**A14**: `add_dependency` is just a parameter to the code execution call, no additional action needed.

**Q15: Default plugins** - Which plugins included by default?

**A15**: Use all plugins as default. **TODO**: Ask for details later, mark this for follow-up.

---

### Error Handling

**Q16: Compilation vs Runtime errors** - Different response structures?

**A16**: Yes, must be as specific as possible to help LLM solve its own problem on first attempt.

**Q17: Partial output on failure** - Include output before exception?

**A17**:
- Stream output as it appears
- Clearly deliver execution status at the end
- Make sure messages are not intermixed in output
- If possible, stream to LLM ASAP

---

### Practical Usage

**Q18: Concurrency** - What if multiple requests arrive?

**A18**: Run requests one-by-one. There must be an option for that (queue).

**Q19: IDE state during execution** - Can script access open editor, selection, etc.?

**A19**: Yes, script can access everything it wants to.

**Q20: Write actions** - Should scripts wrap in WriteAction or use helpers?

**A20**:
- Add information about read and write locks
- Import necessary primitives
- It's up to MCP server to generate correct code
- **Change**: Make entry point function `suspend` to simplify code where suspend is needed
- Still need to create coroutineScope and runBlocking to execute it

---

### Additional Requirements from Q&A

**Timeout and Cancellation**:
- Introduce timeout for function call
- API for MCP server to stop current command run
- Need recommendation on polling vs streaming

**Entry Point Change**:
```kotlin
suspend fun main(ctx: McpScriptContext) { ... }
```

---

## Final Execution Flow Design

After discussion, here is the finalized execution model:

### Flow Diagram

```
execute_code(project, code, plugins, show_review_on_error?)
    │
    ├── Compilation Phase (blocking)
    │   │
    │   ├── Success → assign execution_id → continue to review/execution
    │   │
    │   └── Fail:
    │       ├── show_review_on_error=false → return compilation_error immediately
    │       └── show_review_on_error=true → assign execution_id → show in editor for user help
    │
    ├── Review Phase (if enabled, blocking)
    │   ├── User approves → continue to execution
    │   └── User rejects → return rejection with edits/comments
    │
    └── Return: { execution_id, status: "running" | "pending_review" | "compilation_error" }

get_result(execution_id, stream?: boolean, offset?: number)
    │
    ├── Returns full payload:
    │   { status, output, errors, ... }
    │
    ├── offset=N → skip first N messages
    ├── stream=false → returns current state immediately
    └── stream=true → SSE stream until completion

cancel_execution(execution_id)
    └── Stops the running execution or cancels pending review
```

### API Details

**execute_code** - Compile and start execution
- Blocks during compilation
- Returns execution_id once compilation succeeds (or on error if show_review_on_error=true)
- Does NOT block for execution - use get_result for that

**get_result** - Get execution output and status
- `execution_id`: Required
- `stream`: If true, use SSE to stream output as it appears
- `offset`: Skip first N messages (for pagination/resumption)
- Always returns from beginning (offset 0) unless specified
- Same response structure regardless of stream mode

**cancel_execution** - Cancel running or pending execution
- Works during review wait or during execution
- Returns confirmation

### Storage Structure

```
.idea/mcp-run/
├── 2024-01-15/                    # Date-based folders (alphabetic ordering)
│   └── 103025-a1b2c3d4/           # HHMMSS-random
│       ├── script.kt              # The submitted code
│       ├── parameters.json        # project_path, plugins, timeout, etc.
│       ├── output.jsonl           # Streamed output (JSON lines, appended)
│       └── result.json            # Final status, errors, exception
├── 2024-01-16/
│   └── ...
```

### Output Format (JSON Lines)

Using JSON lines format but with minimal quoting for LLM readability:

```jsonl
{ts:1705312201123,type:out,msg:Hello world}
{ts:1705312201125,type:log,level:info,msg:Processing file.kt}
{ts:1705312201200,type:json,data:{files:3,errors:0}}
{ts:1705312201300,type:err,msg:NullPointerException at line 42}
```

Message types:
- `out` - stdout from println()
- `json` - structured data from printJson()
- `log` - log messages (info/warn/error)
- `err` - exceptions/errors

### Streaming (SSE)

For `stream=true`, use Server-Sent Events:

```
GET /execution/{id}/result?stream=true&offset=0

event: message
data: {ts:1705312201123,type:out,msg:Hello world}

event: message
data: {ts:1705312201125,type:log,level:info,msg:Processing}

event: status
data: {status:running,progress:50}

event: complete
data: {status:success,duration_ms:1523}
```

### Retention Policy

Keep all execution history. Folders named by date ensure:
- Alphabetic sorting = chronological order
- Easy manual cleanup by deleting old date folders
- No automatic deletion (user controls retention)

---

## Decisions Summary

| Topic | Decision |
|-------|----------|
| Transport | HTTP/TCP, document stdio proxy |
| Port | 11993 default, configurable via Registry, auto-allocate if busy |
| Output | Context methods: println, printJson, log* |
| Imports | Predefined * imports, documented |
| Package | Optional |
| Review blocking | Blocks during review, rejection includes user edits/comments |
| Review on error | Optional `show_review_on_error` to let user help fix compilation errors |
| Classloader | Fresh per execution, GC-ed after |
| Slots | String and JSON, project-scoped |
| Commands | String parameters, fresh classloader per invocation |
| Dependencies | Parameter to execute_code (default: all plugins) |
| Default plugins | All plugins (details TBD) |
| Errors | Distinct compilation vs runtime errors |
| Concurrency | Sequential execution (queue) |
| Entry point | `suspend fun main(ctx: McpScriptContext)` |
| Timeout | Yes, with cancellation API |
| **API Flow** | `execute_code` (compile) → `get_result` (output) → `cancel_execution` |
| **Streaming** | SSE for `get_result` with `stream=true` |
| **Output storage** | File-based (output.jsonl), not in memory |
| **Offset** | All APIs support `offset` parameter to skip N messages |
| **Storage** | Date folders, keep all history, manual cleanup |
| **Execution ID** | Format: `YYYYMMDD/HHMMSS-random` |

---

## Items Marked for Follow-up

1. ~~**Default plugins**: Need details on which plugins to include by default~~ → **RESOLVED**: Use `AllPluginsLoader.INSTANCE` from `IdeScriptEngineManagerImpl.java` which automatically delegates to ALL plugin classloaders
2. ~~**Stdio proxy documentation**: How to connect via stdio for standard MCP clients~~ → **RESOLVED**: See [STDIO_PROXY.md](STDIO_PROXY.md)
3. **IntelliJ code resolution**: Make IntelliJ resolve code in same classpath (suggestion) - still open

---

## Research Findings (2024-01-15)

### Script Engine API

IntelliJ has a built-in script engine infrastructure that handles all plugin classloading:

**Key discovery**: `AllPluginsLoader.INSTANCE` in `IdeScriptEngineManagerImpl.java`:
- Custom ClassLoader that delegates `findClass()` to ALL plugin classloaders
- Used by default when no explicit loader is provided
- Includes smart caching (LRU) for performance

**Usage pattern**:
```kotlin
val engine = IdeScriptEngineManager.getInstance().getEngineByFileExtension("kts", null)
// null loader = AllPluginsLoader.INSTANCE = all plugins available
engine.setBinding("ctx", myContext)
engine.eval(script)
```

**Source files**:
- `platform/ide-core-impl/src/com/intellij/ide/script/IdeScriptEngineManagerImpl.java`
- `platform/ide-core-impl/src/com/intellij/ide/script/IdeScriptEngine.java`
- `platform/lang-impl/src/com/intellij/ide/script/IDE.java`

### HTTP Server

IntelliJ has a built-in HTTP server (Netty-based) with extension points:

**Extension point**: `com.intellij.httpRequestHandler`

**Base class**: `RestService` provides:
- Automatic routing by service name (`/api/{serviceName}`)
- JSON reading/writing with Gson
- CORS handling
- Rate limiting
- Error reporting

**Port**: Uses IntelliJ's built-in server port range (63342-63361)

**Alternative**: `CustomPortServerManager` for dedicated port (11993)

**Source files**:
- `platform/built-in-server/src/org/jetbrains/ide/RestService.kt`
- `platform/built-in-server/src/org/jetbrains/io/BuiltInServer.kt`

### Editor Notification Panel

**Extension point**: `com.intellij.editorNotificationProvider`

**Pattern**:
```kotlin
class MyProvider : EditorNotificationProvider {
    override fun collectNotificationData(project, file) = Function { fileEditor ->
        EditorNotificationPanel(fileEditor, Status.Warning).apply {
            text("Message")
            createActionLabel("Action") { /* handler */ }
        }
    }
}
```

**Source files**:
- `platform/platform-api/src/com/intellij/ui/EditorNotificationProvider.java`
- `platform/platform-api/src/com/intellij/ui/EditorNotificationPanel.java`

---

## Clarification Session (2024-12-10)

### Issues Identified in Review

1. **Port Inconsistency**: README said 11993, but we use IntelliJ's built-in server
2. **Entry Point vs JSR-223**: `suspend fun main` doesn't match script engine eval
3. **`plugins` parameter vs AllPluginsLoader**: Contradictory
4. **Review mode default**: Unclear
5. **Dynamic commands classloader**: Potential issue

### Resolutions

**1. Port → Use IntelliJ's built-in server**
- Endpoint: `/api/steroids-mcp` on IntelliJ's port (63342+)
- No custom port management needed

**1+ MAJOR FINDING: IntelliJ has built-in MCP server!**

IntelliJ Community includes a full MCP server plugin (`com.intellij.mcpServer`) with extension points:

```xml
<extensionPoints>
  <extensionPoint name="mcpToolsProvider" interface="com.intellij.mcpserver.McpToolsProvider"/>
  <extensionPoint name="mcpToolset" interface="com.intellij.mcpserver.McpToolset"/>
</extensionPoints>
```

**Option A**: Implement `McpToolset` and register via:
```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
  <mcpToolset implementation="com.jonnyzzz.mcpSteroid.SteroidsMcpToolset"/>
</extensions>
```

**Option B**: Keep separate REST endpoint at `/api/steroids-mcp` for compatibility

**Decision**: Investigate both. If MCP server plugin is bundled in target IntelliJ versions, prefer Option A.

**2. Entry Point → Script Body

Updated architecture:
- The script body is the entry point (McpScriptContext is the receiver)
- `execute { }` remains as a backward-compatible shim
- The body runs in a suspend context with full API access

```kotlin
// What user writes:
println("Hello")
val projectRef = project
// ... actual work

// Legacy shim (kept for compatibility):
interface McpScriptScope {
    fun execute(block: suspend McpScriptContext.() -> Unit)
}
```

This ensures:
- We control when/how the suspend block runs
- We can set up proper coroutine context
- We can wait for indexes, etc.

**3. `plugins` parameter → REMOVED**

AllPluginsLoader.INSTANCE is used internally by IdeScriptEngine. We don't need to expose this. Simplifies API.

**4. Review mode → Enabled by default, Registry configurable**

- `mcp.steroids.review.mode` = `ALWAYS` | `TRUSTED` | `NEVER`
- Default: `ALWAYS`
- All requests logged to disk regardless of review mode

**5. Review mode configuration → IntelliJ Registry**

Registry keys:
- `mcp.steroids.review.mode` (string): ALWAYS, TRUSTED, NEVER
- `mcp.steroids.review.timeout` (int): seconds to wait for review
- `mcp.steroids.execution.timeout` (int): script execution timeout

**6. Dynamic commands → Deferred**

Not implementing in v1. Classloaders won't be explicitly disposed - they'll be GC-ed naturally when no references remain. If handler lambdas reference classes, those classloaders stay alive.

**7. Execution ID → Include project hash**

Format: `{3-char-project-hash}/{YYYYMMDD}/{HHMMSS}-{random8}`
Example: `abc/20241210/143025-x7k9m2p1`

Project hash: first 3 chars of base32-encoded hash of project name.

### New Architecture: McpScriptScope

```kotlin
// User-facing scope (bound to script engine)
interface McpScriptScope {
    /**
     * Execute a suspend block with full MCP context.
     * This is the ONLY way to interact with the IDE.
     */
    fun execute(block: suspend McpScriptContext.() -> Unit)
}

// Full context (passed to execute block)
interface McpScriptContext : Disposable {
    val project: Project
    val coroutineScope: CoroutineScope  // Bound to this Disposable

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

    // Utilities
    suspend fun waitForSmartMode()  // Wait for indexes
    suspend fun <T> readAction(block: () -> T): T
    suspend fun <T> writeAction(block: () -> T): T

    // Reflection helpers
    fun listServices(): List<String>
    fun listExtensionPoints(): List<String>
    fun describeClass(className: String): String
}
```

### waitForSmartMode() Implementation

Based on `com.jetbrains.performancePlugin.ProjectLoaded#runScriptAfterDumb`:

```kotlin
suspend fun waitForSmartMode() {
    suspendCancellableCoroutine { cont ->
        fun runAfterDumb() {
            DumbService.getInstance(project).smartInvokeLater {
                if (DumbService.isDumb(project)) {
                    runAfterDumb()
                } else {
                    cont.resume(Unit)
                }
            }
        }
        runAfterDumb()
    }
}
```

### CoroutineScope Best Practices (from IntelliJ codebase)

**Recommended pattern**: Use service-injected CoroutineScope

```kotlin
@Service(Service.Level.PROJECT)
class ExecutionManager(val coroutineScope: CoroutineScope) {
    private val backgroundScope = coroutineScope.childScope(
        "MCP Script Execution",
        Dispatchers.Default.limitedParallelism(1)  // Sequential execution
    )
}
```

**Key utilities**:
- `Job.cancelOnDispose(disposable)` - Cancel job when disposable is disposed
- `Disposable.disposeOnCompletion(scope)` - Dispose when scope completes
- `CoroutineScope.childScope(name, context)` - Create child scope

**For McpScriptContext**:
```kotlin
class McpScriptContextImpl(
    override val project: Project,
    parentScope: CoroutineScope
) : McpScriptContext, Disposable {

    override val coroutineScope = parentScope.childScope(
        "McpScriptContext",
        SupervisorJob()
    )

    init {
        // Cancel scope when disposed
        coroutineScope.coroutineContext.job.cancelOnDispose(this)
    }

    override fun dispose() {
        // Scope automatically cancelled via cancelOnDispose
    }
}
```

### Kotlin-Only Focus

Groovy support deferred. Focus on Kotlin scripting only for v1.

### Updated Decisions Summary

| Topic | Decision |
|-------|----------|
| **MCP Integration** | McpToolset only (no REST fallback) |
| **Target Version** | IntelliJ 2025.3+ |
| **Entry Point** | Script body (McpScriptContext receiver; execute { } optional) |
| **Plugins param** | REMOVED - AllPluginsLoader used internally |
| **Review mode** | ALWAYS by default, TRUSTED = trust all callers |
| **Registry keys** | review.mode, review.timeout, execution.timeout |
| **Dynamic commands** | Deferred to v2 |
| **Slots API** | Deferred to v2 |
| **Execution ID** | `{hash-3}-{YYYY-MM-DD}T{HH-MM-SS}-{payload-10}` |
| **Language** | Kotlin only (no Groovy for v1) |
| **CoroutineScope** | Service-injected, childScope pattern |
| **Disposable** | McpScriptContext is Disposable, bound to scope |
| **Response Model** | Polling via get_result (no streaming at tool level) |
| **Compilation** | Synchronous (blocks tool call) |

---

## Final Clarifications (2024-12-10)

### Execution ID Format

**Final format**: `{project-hash-3}-{YYYY-MM-DD}T{HH-MM-SS}-{payload-hash-10}`

- No slashes (use `-` separator)
- No timezone in timestamp
- Project hash: first 3 chars of base64url-encoded SHA-256 of project name
- Payload hash: first 10 chars of base64url-encoded SHA-256 of (code + parameters JSON)
- Example: `abc-2024-12-10T14-30-25-a1B2c3D4e5`

Used as directory name: `.idea/mcp-run/abc-2024-12-10T14-30-25-a1B2c3D4e5/`

### Response Model

IntelliJ MCP tools return complete `McpToolCallResult` objects - no streaming at the tool level.
Streaming exists at the transport level (SSE), but individual tool calls are request-response.

**Conclusion**: Use polling via `get_result` tool. This is acceptable for v1.

### Project Identification

- `list_projects` returns all open projects with name and path
- Other tools use `project_name` parameter (not path)
- Project must be open in IDE

### Review Modes

- `ALWAYS` (default): Every script requires human approval
- `TRUSTED`: Trust all MCP callers, auto-approve everything
- `NEVER`: Auto-execute all (development/testing only)

### Deferred to v2

- Slots API (read_slot/write_slot) - both MCP tools and context methods
- Dynamic commands (register_command/unregister_command)
- SSE streaming for output (if feasible with McpToolset)
