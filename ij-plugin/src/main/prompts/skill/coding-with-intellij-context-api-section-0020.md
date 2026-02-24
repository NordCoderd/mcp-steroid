
### Output Methods

```kotlin
// Print space-separated values
println(vararg values: Any?)

// Serialize to pretty JSON (uses Jackson)
printJson(obj: Any)

// Report error without failing execution (includes stack trace)
printException(msg: String, throwable: Throwable)

// Report progress (throttled to 1 message per second)
progress(message: String)

// Capture IDE screenshot - artifacts saved as screenshot.png, screenshot-tree.md, screenshot-meta.json
takeIdeScreenshot(fileName: String = "screenshot"): String  // returns execution_id
```
