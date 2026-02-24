## McpScriptContext API Reference

The `McpScriptContext` is the receiver (`this`) of your script body. It provides access to the project, output methods, and utility functions.

**Source**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt`](../../kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt)

### Core Properties

```kotlin
project: Project         // IntelliJ Project instance
params: JsonElement      // Original tool execution parameters (JSON)
disposable: Disposable   // Parent Disposable for resource cleanup
isDisposed: Boolean      // Check if context is disposed
```
