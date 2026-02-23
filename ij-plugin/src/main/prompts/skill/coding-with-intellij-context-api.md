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

### Built-in Read/Write Actions (NO IMPORTS NEEDED!)

```kotlin
// Execute under read lock (for PSI/VFS reads)
suspend fun <T> readAction(block: () -> T): T

// Execute under write lock (for PSI/VFS writes)
suspend fun <T> writeAction(block: () -> T): T

// Wait for smart mode + read action in one call
suspend fun <T> smartReadAction(block: () -> T): T
```

**Important**: These are **built-in** - you do NOT need to import `readAction` or `writeAction` from IntelliJ Platform!

### Built-in Search Scopes (NO IMPORTS NEEDED!)

```kotlin
// Project files only (no libraries)
fun projectScope(): GlobalSearchScope

// Project + libraries
fun allScope(): GlobalSearchScope
```

### File Access Helpers

```kotlin
// Find VirtualFile by absolute path
fun findFile(path: String): VirtualFile?

// Find PsiFile by absolute path (suspend - uses readAction)
suspend fun findPsiFile(path: String): PsiFile?

// Find VirtualFile relative to project base path
fun findProjectFile(relativePath: String): VirtualFile?

// Find PsiFile relative to project base path (suspend - uses readAction)
suspend fun findProjectPsiFile(relativePath: String): PsiFile?
```

> **⚠️ `findProjectFile()` pitfall for resource files**: This function requires the **full relative path** from the project root (e.g., `"src/main/resources/application.properties"`). Calling it with just a filename (`findProjectFile("application.properties")`) **always returns null** — causing NPE on `!!`. For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName()` which searches by filename without requiring the full path:
> ```kotlin
> import com.intellij.psi.search.FilenameIndex
> import com.intellij.psi.search.GlobalSearchScope
> val scope = GlobalSearchScope.projectScope(project)
> val appProps = readAction {
>     FilenameIndex.getVirtualFilesByName("application.properties", scope)
>         .firstOrNull { it.path.contains("src/main/resources") }
> } ?: error("application.properties not found in src/main/resources")
> println(VfsUtil.loadText(appProps))
> ```

### IDE Utilities

```kotlin
// Wait for indexing to complete (called automatically before script starts)
suspend fun waitForSmartMode()

// Check if daemon code analyzer is currently running
fun isDaemonRunning(): Boolean

// Wait for highlighting to complete on a file (requires IDE window focus)
suspend fun waitForDaemonAnalysis(file: VirtualFile, timeout: Duration = 30.seconds): Boolean

// Get highlights after analysis completes (note: requires IDE window focus)
suspend fun getHighlightsWhenReady(
    file: VirtualFile,
    minSeverityValue: Int = HighlightSeverity.WEAK_WARNING.myVal,
    timeout: Duration = 30.seconds
): List<HighlightInfo>

// ✓ RECOMMENDED: Run inspections bypassing daemon cache (works regardless of window focus)
suspend fun runInspectionsDirectly(
    file: VirtualFile,
    includeInfoSeverity: Boolean = false
): Map<String, List<ProblemDescriptor>>

// Disable automatic cancellation when modal dialogs appear
fun doNotCancelOnModalityStateChange()
```

### Disposable Hierarchy

The context provides a `disposable` property for resource cleanup:

```kotlin
// Access the execution's parent Disposable
val execDisposable = disposable

// Register your own cleanup
val myResource = Disposer.newDisposable("my-resource")
Disposer.register(execDisposable, myResource)

// myResource will be disposed when execution completes (success, error, or timeout)
```

---

