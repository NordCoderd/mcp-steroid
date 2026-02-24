
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
