### Service Access Pattern

```kotlin
// Project-level service
val storage = project.service<ExecutionStorage>()

// Application-level service
val mcpServer = service<SteroidsMcpServer>()
```

---

## Error Handling

### Use printException for Errors

```kotlin
try {
    // risky operation
    val result = someOperationThatMightFail()
} catch (e: Exception) {
    // ✓ RECOMMENDED - includes stack trace in output
    printException("Operation failed", e)
}
```

### Never Catch ProcessCanceledException
