## Services and Components

### Access Project Services

```kotlin
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager

// ⚠️ ProjectRootManager accesses the project model — must be inside readAction { }
val (sdkName, contentRootPaths, sourceRootPaths) = readAction {
    val rm = ProjectRootManager.getInstance(project)
    Triple(rm.projectSdk?.name, rm.contentRoots.map { it.path }, rm.contentSourceRoots.map { it.path })
}
println("SDK: $sdkName")

// Module manager
val moduleManager = ModuleManager.getInstance(project)
moduleManager.modules.forEach { module ->
    println("Module: ${module.name}")
}

// Content roots
contentRootPaths.forEach { println("Content root: $it") }

// Source roots
sourceRootPaths.forEach { println("Source root: $it") }
```

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

```kotlin
import com.intellij.openapi.progress.ProcessCanceledException

try {
    // some operation
} catch (e: ProcessCanceledException) {
    // ✗ WRONG - Never catch this!
    throw e  // Always rethrow
}
```

---

## Best Practices

### 1. Use smartReadAction for PSI Operations

```kotlin
// ✓ RECOMMENDED - combines wait + read in one call
val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope())
}

// Instead of:
waitForSmartMode()
val classes = readAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope())
}
```

### 2. Use Built-in Helpers

No imports needed for:
- `readAction`, `writeAction`, `smartReadAction`
- `projectScope()`, `allScope()`
- `findPsiFile()`, `findProjectFile()`

### 3. Imports Are Optional

Default imports are provided automatically. Add imports only when needed:

```kotlin
// ✓ CORRECT - top-level imports when needed
import com.intellij.psi.PsiManager

val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

### 4. Prefer IntelliJ APIs Over File Operations

The IDE has indexed everything - use it!

### 5. Use ONE Consistent task_id for the Entire Task

**Use the SAME `task_id` from your first call to your last.** Do NOT change `task_id` mid-task.

Changing `task_id` resets the MCP server's per-task feedback history and causes you to lose
context of what you've already done. After changing `task_id`, agents frequently re-read all
the same files already in their conversation history — wasting ~20s per redundant call.

**After discovering Docker is unavailable, a compile error, or any other constraint:**
continue with the SAME `task_id` and adapt your strategy (e.g., switch to `runInspectionsDirectly`
for compile verification). Do NOT restart exploration from scratch under a new `task_id`.

Group related executions with the same `task_id`.

### 6. Report Progress

```kotlin
progress("Step 1 of 5...")
delay(1000)
progress("Step 2 of 5...")
```

### 7. Use printException for Errors

Includes full stack trace in output.

### 8. Keep Trying

The IntelliJ API has a learning curve - persistence pays off!

### 8a. Single Exploration Pass — Never Re-read Files Already in Context

Every file you read via `steroid_execute_code` is in your conversation history for the rest
of the session. Do NOT re-read a file you already read earlier:

- Files you read remain available in your conversation history.
- Only re-read a file if you **explicitly modified it** and need to verify the write succeeded.
- Do NOT restart exploration under a new `task_id` — you will re-read the same files and waste turns.
- Do NOT re-create a file because you forgot whether you created it — use `findProjectFile()` to check existence.

This rule is critical: each `steroid_execute_code` call takes ~20 seconds. Re-reading files already in
context wastes turns and time without adding information.

### 8b. Recovering from steroid_execute_code Compile Errors

When `steroid_execute_code` fails with a Kotlin compilation error (e.g. `unresolved reference 'GlobalSearchScope'`):

1. **Read the error message** — it names the exact unresolved symbol.
2. **Add the missing import** at the top of your script and resubmit.
3. **Do NOT switch to Bash/grep** after a compile error — one corrected steroid call is faster than 10 grep commands.

Common imports that are frequently missing:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope   // ← most often missing
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VfsUtil
```

If the error is `suspension functions can only be called within coroutine body`, your helper
function is missing the `suspend` keyword — add `suspend fun myHelper()`.

### 9. Use runInspectionsDirectly for Code Analysis

More reliable than daemon-based analysis (works even when IDE window is not focused).

### 10. Never Use runBlocking

You're already in a coroutine context!

### 11. Verified Existing Implementation Is a Successful Outcome

If required behavior is already present, you still need explicit verification and explicit final
status output. "No code changes" is valid only after verification, not by assumption.

```kotlin
val requiredPaths = listOf(
    "src/main/java/com/example/api/MyController.java",
    "src/main/java/com/example/service/MyService.java",
    "src/test/java/com/example/api/MyControllerTest.java",
)

val missing = requiredPaths.filter { findProjectFile(it) == null }
if (missing.isNotEmpty()) {
    println("FINAL_STATUS=INCOMPLETE")
    println("MISSING_FILES=${missing.joinToString()}")
} else {
    val filesWithProblems = mutableListOf<String>()
    for (path in requiredPaths) {
        val vf = findProjectFile(path) ?: continue
        val problems = runInspectionsDirectly(vf)
        if (problems.isNotEmpty()) filesWithProblems += path
    }

    if (filesWithProblems.isEmpty()) {
        println("FINAL_STATUS=COMPLETE")
        println("FINAL_REASON=Verified existing implementation; no edits required")
    } else {
        println("FINAL_STATUS=INCOMPLETE")
        println("FILES_WITH_PROBLEMS=${filesWithProblems.joinToString()}")
    }
}
```

---
