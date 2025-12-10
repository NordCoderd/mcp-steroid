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

## Future Extensions

1. **File watching** - notify when files change
2. **Event subscriptions** - subscribe to IDE events (file save, build, etc.)
3. **Multi-file scripts** - submit multiple files as a package
4. **Dependencies** - allow scripts to declare Maven/Gradle dependencies
5. **REPL mode** - stateful execution across multiple requests
6. **Breakpoint integration** - debug scripts in IDE debugger
