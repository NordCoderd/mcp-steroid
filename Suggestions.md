# Suggestions and Open Questions

## Entry Point Design

### Current Design
```kotlin
fun main(ctx: McpScriptContext) { ... }
```

### Alternatives to Consider

1. **Top-level execution with implicit context**
   ```kotlin
   // ctx is implicitly available
   println(ctx.project.name)
   ```
   - Pro: Simpler for one-off scripts
   - Con: Magic variable, less explicit

2. **Class-based entry point**
   ```kotlin
   class Script : McpScript() {
       override fun execute() {
           println(project.name)
       }
   }
   ```
   - Pro: Can have lifecycle methods (init, cleanup)
   - Con: More boilerplate

3. **Annotation-based**
   ```kotlin
   @McpEntry
   fun run(ctx: McpScriptContext) { ... }
   ```
   - Pro: Flexible naming
   - Con: Requires annotation processing

**Recommendation**: Keep `main(ctx: McpScriptContext)` - it's familiar and explicit.

## Classloader Strategy

### Questions
- Should each script execution get a fresh classloader, or reuse for performance?
- How to handle scripts that define classes with the same name?
- Should compiled classes be cached?

### Suggestion
- Fresh classloader per execution for isolation
- Cache compiled classes by content hash
- Clear cache on explicit request or memory pressure

## Threading Model

### Questions
- Should script execution block the MCP request thread?
- How to handle scripts that need to run on EDT (Event Dispatch Thread)?
- Timeout strategy?

### Suggestions
- Execute on a background thread, not EDT
- Provide `ctx.runOnEdt { }` helper for UI operations
- Default timeout of 60 seconds, configurable per-request
- Add `runReadAction { }` and `runWriteAction { }` helpers

## Slot Storage Format

### Options
1. **Simple key-value files** - one file per slot
2. **Single JSON file** - all slots in one file
3. **SQLite database** - more robust for many slots

### Suggestion
- Start with simple key-value files in `.idea/mcp-run/slots/<slot-name>.txt`
- Migrate to SQLite if needed later

## Security Considerations

### Questions
- Should we restrict what APIs scripts can access?
- How to prevent scripts from damaging the IDE or filesystem?
- Should there be a "safe mode" with limited capabilities?

### Suggestions
- No sandboxing initially - scripts have full IDE access (this is the point)
- Add optional timeout and memory limits
- Log all script executions for audit
- Consider adding a confirmation prompt for destructive operations (optional setting)

## MCP Protocol Version

### Question
- Which MCP protocol version to support?
- Should we support both stdio and HTTP transports?

### Suggestion
- Start with HTTP transport on fixed port (11993)
- Support MCP protocol version 2024-11-05 (latest stable)
- Consider adding stdio transport later for direct integration

## Port Configuration

### Current Design
- Fixed port 11993

### Alternative
- Configurable via settings
- Dynamic port with discovery mechanism
- Unix socket on Linux/macOS

### Suggestion
- Keep fixed port for simplicity
- Add setting to change port
- Write port to `.idea/mcp-run/server.port` for discovery

## Error Reporting

### Questions
- How verbose should error messages be?
- Include stack traces in MCP responses?
- How to report multiple errors (e.g., multiple compilation errors)?

### Suggestions
- Include full stack traces for runtime errors
- Return all compilation errors as a list
- Include source code context around errors
- Add error codes for programmatic handling

## Dynamic Command Persistence

### Questions
- Should dynamically registered commands survive IDE restart?
- How to handle command name conflicts?
- Should commands be scoped to project or global?

### Suggestions
- Commands are transient (lost on restart) - keeps it simple
- Command names are project-scoped with global namespace
- Conflict = error, must unregister first
- Future: optional persistence via serialized handlers

## IntelliJ Project Detection Heuristics

### Detection Signals
1. Presence of `intellij.core` module
2. `build.txt` file in project root
3. `intellij.idea.community.main` in module list
4. `.idea/modules.xml` containing `intellij.platform`

### Enhanced Features When Detected
- Include internal API documentation links
- Suggest common extension points
- Provide test framework helpers
- Reference `intellij-community/platform/` package structure

## Kotlin vs Groovy Priority

### Suggestion
- Implement Kotlin first (primary use case)
- Add Groovy later as optional enhancement
- Consider JavaScript/GraalJS as future option

## Performance Optimizations (Future)

1. **Compilation caching** - cache compiled classes by source hash
2. **Warm classloader pool** - pre-create classloaders
3. **Incremental compilation** - for iterative development
4. **Parallel compilation** - if multiple requests queue up

## Output Storage and Streaming

### Design Decisions
- Output written to file (`output.jsonl`), not kept in memory
- File is appended during execution, streamed to client on demand
- JSON lines format for easy parsing and streaming
- Offset parameter allows resuming from specific message

### Why File-Based
- Memory efficient for long-running scripts
- Survives IDE restart (can retrieve past execution results)
- Easy to inspect manually
- Natural streaming via file tailing

### JSON Lines Format
Each line is a complete JSON object:
```jsonl
{"ts":1705312201123,"type":"out","msg":"Hello world"}
{"ts":1705312201125,"type":"log","level":"info","msg":"Processing"}
{"ts":1705312201200,"type":"json","data":{"files":3,"errors":0}}
```

### SSE Implementation Notes
- Use Ktor's SSE support or implement manually with chunked response
- Send heartbeat every 15 seconds to keep connection alive
- Include message sequence number for offset tracking
- Close stream on completion, timeout, or cancellation

## API Versioning

### Questions
- How to handle breaking changes to McpScriptContext?
- Should scripts declare required API version?

### Suggestions
- Semantic versioning for the plugin
- Scripts can optionally declare `@RequiresApiVersion("1.0")`
- Maintain backwards compatibility within major versions

## Logging and Debugging

### Suggestions
- Log all MCP requests/responses at DEBUG level
- Log script compilation and execution at INFO level
- Provide `ctx.log(message)` for script-level logging
- Consider adding a "debug mode" that captures more detail

## Runtime Reflection for API Discovery

LLM agents should use reflection to discover available APIs at runtime. This is essential because:
- IntelliJ's API surface is vast and constantly evolving
- Plugin availability varies per installation
- Runtime introspection reveals actual capabilities

### Recommended Reflection Patterns

```kotlin
fun main(ctx: McpScriptContext) {
    // Discover available services
    val serviceManager = ctx.project.getService(Any::class.java)

    // List all registered extension points
    val extensionArea = Extensions.getRootArea()
    extensionArea.extensionPoints.forEach { ep ->
        println("Extension point: ${ep.name}")
    }

    // Introspect a class to understand its API
    val psiManager = PsiManager.getInstance(ctx.project)
    psiManager::class.java.methods.forEach { method ->
        println("${method.name}(${method.parameterTypes.joinToString()})")
    }

    // Find implementations of an interface
    val implementations = ServiceLoader.load(SomeInterface::class.java)
}
```

### MCP Server Should Document

The MCP server's tool descriptions should include:
1. **Encourage reflection-first approach** - before assuming an API exists, introspect
2. **Provide reflection helpers** in `McpScriptContext`:
   - `ctx.listServices()` - all registered services
   - `ctx.listExtensionPoints()` - all extension points
   - `ctx.describeClass(className)` - methods, fields, annotations
3. **Include examples** of common reflection patterns in tool descriptions
4. **Warn about internal APIs** - classes in `*.impl.*` packages may change

### Benefits for LLM Agents
- Self-documenting: agent can explore what's available
- Version-agnostic: works across IntelliJ versions
- Plugin-aware: discovers APIs from installed plugins
- Reduces hallucination: agent sees real API, not imagined one

## Code Review Mode (Human-in-the-Loop)

### Overview
A safety mode where submitted code is opened in the IDE editor for human review before execution. This is **enabled by default** to ensure safety.

### Workflow
1. Agent submits code via `execute_code` tool
2. MCP server saves code to `.idea/mcp-run/pending/<id>.kt`
3. Code is opened in IntelliJ editor with a review panel
4. Human reviews code and clicks "Approve" or "Reject"
5. MCP server returns result (execution output or rejection message)

### Configuration
```kotlin
// In MCP server settings
enum class ReviewMode {
    ALWAYS,      // Always require human approval (default)
    TRUSTED,     // Auto-approve code matching trusted patterns
    NEVER        // Auto-execute all code (dangerous, for development only)
}
```

### UI Components
- **Review Panel**: Shows code with syntax highlighting
- **Approve Button**: Executes the code
- **Reject Button**: Returns rejection to agent with optional reason
- **Edit Button**: Allow human to modify code before approval
- **Trust Checkbox**: "Trust similar code in future" (pattern-based)

### MCP Response During Review
```json
{
    "status": "pending_review",
    "message": "Code is awaiting human approval in the IDE",
    "review_id": "abc123"
}
```

### Timeout Handling
- Default review timeout: 5 minutes
- After timeout: return `review_timeout` status
- Agent can re-submit or ask user to check IDE

### Code Resolution in Editor
**Suggestion**: When code is opened for review, IntelliJ should resolve it in the same classpath that will be used for execution. This means:
- Setting up a temporary module or using scratch file with proper SDK
- Configuring the classpath to match the execution classloader (platform + selected plugins)
- Enabling code completion, inspections, and error highlighting during review
- This helps the human reviewer understand the code better and catch issues before execution

## Third-Party Code Verification Integration

### Overview
Extensible system for automated code verification before execution, complementing or replacing human review.

### Architecture
```kotlin
interface CodeVerifier {
    /** Verify code before execution. Return null to approve, or error message to reject. */
    suspend fun verify(code: String, language: String, context: VerificationContext): VerificationResult
}

data class VerificationResult(
    val approved: Boolean,
    val reason: String?,
    val suggestions: List<String>? // Optional improvements
)
```

### Built-in Verifiers
1. **Static Analysis** - run IntelliJ inspections on the code
2. **Pattern Blocklist** - reject code matching dangerous patterns (e.g., `Runtime.exec`, `File.delete`)
3. **Scope Limiter** - reject code accessing certain packages

### Third-Party Integration Points
1. **AI-based Review** - send code to another LLM for safety analysis
2. **Custom Webhook** - POST code to external service for verification
3. **Plugin-based** - IntelliJ plugins can register custom verifiers

### Configuration
```kotlin
// Verification pipeline (executed in order, all must pass)
verifiers = [
    PatternBlocklistVerifier(patterns = ["Runtime.exec", "ProcessBuilder"]),
    StaticAnalysisVerifier(minSeverity = WARNING),
    WebhookVerifier(url = "https://my-company.com/code-review"),
    AiVerifier(model = "gpt-4", prompt = "Is this code safe to run in an IDE?")
]
```

### Verification Modes
- **Strict**: All verifiers must approve
- **Majority**: >50% of verifiers approve
- **Any**: At least one verifier approves
- **Advisory**: Log results but don't block (for monitoring)

### MCP Response with Verification
```json
{
    "status": "rejected",
    "verification_results": [
        {"verifier": "pattern_blocklist", "approved": false, "reason": "Contains Runtime.exec"},
        {"verifier": "static_analysis", "approved": true},
        {"verifier": "ai_review", "approved": false, "reason": "Code attempts to delete files"}
    ]
}
```

## Future Extensions

1. **File watching** - notify when files change
2. **Event subscriptions** - subscribe to IDE events (file save, build, etc.)
3. **Multi-file scripts** - submit multiple files as a package
4. **Dependencies** - allow scripts to declare Maven/Gradle dependencies
5. **REPL mode** - stateful execution across multiple requests
6. **Breakpoint integration** - debug scripts in IDE debugger
