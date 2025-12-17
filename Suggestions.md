# Suggestions and Future Considerations

This document captures design suggestions and future considerations that are **not yet implemented** or **deferred to later versions**.

---

## Deferred to v2

### Slots API

Named storage slots for persisting data between script executions.

**MCP Tools**:
- `read_slot(project_name, slot_name)` - Read slot value
- `write_slot(project_name, slot_name, value)` - Write slot value

**McpScriptContext Methods**:
- `readSlot(name): String?`
- `writeSlot(name, value: String)`

**Storage**: `.idea/mcp-run/slots/<slot-name>.txt`

### Dynamic Commands

Scripts can register new MCP commands that persist during IDE session.

**MCP Tools**:
- `register_command(name, description, handler_code)`
- `unregister_command(name)`
- `list_commands()`

**Considerations**:
- Handlers stay alive with their classloaders (no explicit disposal)
- Commands are project-scoped
- Lost on IDE restart (transient)

### Streaming Output

Currently using synchronous request-response model.
Future consideration: Add SSE streaming for real-time output during long executions.

---

## Security Considerations

### Questions to Address Later
- Should we restrict what APIs scripts can access?
- How to prevent scripts from damaging the IDE or filesystem?
- Should there be a "safe mode" with limited capabilities?

### Current Approach
- No sandboxing - scripts have full IDE access (this is the point)
- Review mode (`ALWAYS` default) provides human oversight
- All requests logged to disk for audit

### Future Options
- Pattern blocklist (reject `Runtime.exec`, `ProcessBuilder`, etc.)
- Scope limiter (restrict access to certain packages)
- AI-based review integration
- Custom webhook verification

---

## Third-Party Code Verification (Future)

Extensible system for automated code verification before execution.

```kotlin
interface CodeVerifier {
    suspend fun verify(code: String, context: VerificationContext): VerificationResult
}

data class VerificationResult(
    val approved: Boolean,
    val reason: String?,
    val suggestions: List<String>?
)
```

**Potential Verifiers**:
1. Static Analysis - run IntelliJ inspections
2. Pattern Blocklist - reject dangerous patterns
3. AI Review - send to another LLM for safety analysis
4. Custom Webhook - POST to external service

---

## Performance Optimizations (Future)

1. **Compilation caching** - cache compiled classes by source hash
2. **Warm classloader pool** - pre-create classloaders
3. **Incremental compilation** - for iterative development
4. **Parallel compilation** - if multiple requests queue up

---

## Future Extensions

1. **File watching** - notify when files change
2. **Event subscriptions** - subscribe to IDE events (file save, build, etc.)
3. **Multi-file scripts** - submit multiple files as a package
4. **Dependencies** - allow scripts to declare Maven/Gradle dependencies
5. **REPL mode** - stateful execution across multiple requests
6. **Breakpoint integration** - debug scripts in IDE debugger

---

## IntelliJ Project Detection (Future)

Enhanced context when plugin detects it's working on IntelliJ project itself.

**Detection Signals**:
1. Presence of `intellij.core` module
2. `build.txt` file in project root
3. `intellij.idea.community.main` in module list

**Enhanced Features**:
- Include internal API documentation links
- Suggest common extension points
- Provide test framework helpers

---

## API Versioning (Future)

- Scripts can optionally declare `@RequiresApiVersion("1.0")`
- Maintain backwards compatibility within major versions
- Semantic versioning for the plugin

---

## Logging and Debugging

### Current
- All requests logged to `.idea/mcp-run/` regardless of review mode
- Output captured in `output.jsonl`

### Future
- Log all MCP requests/responses at DEBUG level
- Add "debug mode" that captures more detail
- Integration with IDE's logging system
