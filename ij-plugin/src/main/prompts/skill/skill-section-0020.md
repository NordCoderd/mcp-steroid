
This server exposes built-in resources through the MCP resource APIs. These are the fastest way to load full examples and guides without guessing or copy/pasting from the web.

**How to access resources:**
1. Call `list_mcp_resources` to discover available resources.
2. Call `read_mcp_resource` with the resource URI to load the content.

**Key resources provided by this server:**
- `mcp-steroid://skill/skill` - This guide as a resource.
- `mcp-steroid://skill/coding-with-intellij` - Comprehensive guide for writing IntelliJ API code (execution model, patterns, examples).
- `mcp-steroid://skill/debugger-skill` - Debugger-focused skill guide (breakpoints, sessions, threads).
- `mcp-steroid://lsp/overview` - Overview of LSP-like examples and how to use them.
- `mcp-steroid://lsp/<id>` - Runnable Kotlin scripts (e.g., `go-to-definition`, `find-references`, `rename`, `code-action`, `signature-help`).
- `mcp-steroid://ide/overview` - Overview of IDE power operation examples (refactorings, inspections, generation).
- `mcp-steroid://ide/<id>` - Runnable Kotlin scripts (e.g., `extract-method`, `introduce-variable`, `change-signature`, `safe-delete`, `optimize-imports`, `pull-up-members`, `push-down-members`, `extract-interface`, `move-class`, `generate-constructor`, `call-hierarchy`, `project-dependencies`, `inspection-summary`, `project-search`, `run-configuration`).
- `mcp-steroid://debugger/overview` - Overview of debugger examples (breakpoints, sessions, threads).
- `mcp-steroid://debugger/<id>` - Runnable Kotlin scripts (e.g., `set-line-breakpoint`, `debug-run-configuration`, `debug-session-control`, `debug-list-threads`, `debug-thread-dump`).
- `mcp-steroid://open-project/overview` - Guide for opening projects via MCP.
- `mcp-steroid://open-project/<id>` - Project opening examples (e.g., `open-trusted`, `open-with-dialogs`, `open-via-code`).

These resources are designed to be plugged directly into `steroid_execute_code` after you configure file paths/positions.

## Critical Rules

### 1. Script Body is a SUSPEND Function
```kotlin
// This is a coroutine - use suspend APIs!
// waitForSmartMode() is called automatically before your script starts.
delay(1000)         // coroutine delay - works directly
```
**NEVER use `runBlocking`** - it causes deadlocks.

**NEVER re-probe `waitForSmartMode()` before every operation.** Once the first call completes
(which happens automatically before your script starts), smart mode is confirmed for the duration
of your task. Calling it again before each subsequent `steroid_execute_code` adds ~20s of
unnecessary overhead per re-probe. Only call `waitForSmartMode()` again if you explicitly
trigger re-indexing mid-script.

### 2. Imports Are Optional

Default imports are provided automatically. Add imports only when you need APIs outside the defaults.

```kotlin
import com.intellij.psi.PsiManager
import com.intellij.openapi.application.readAction

val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

**WRONG:**
```kotlin
val filePath = "src/Main.kt"
import com.intellij.psi.PsiManager  // ERROR!
```

**CORRECT:**
```kotlin
import com.intellij.psi.PsiManager

// Use built-in readAction helper - no import needed!
val file = readAction { PsiManager.getInstance(project).findFile(vf) }
```

### 3. Read/Write Actions for PSI/VFS

> **⚠️ THREADING RULE — NEVER SKIP**: Any PSI access (JavaPsiFacade, PsiShortNamesCache, PsiManager.findFile, module roots, annotations, etc.) **MUST** be inside `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately — they are NOT silently ignored. This is the most common cause of first-attempt runtime errors.

**Built-in helpers (no imports needed):**
```kotlin
// Reading PSI/VFS/indices - use built-in readAction
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}

// Or use smartReadAction to auto-wait for indexing
val classes = smartReadAction {
    JavaPsiFacade.getInstance(project).findClass("MyClass", projectScope())
}

// Modifying PSI/VFS/documents
writeAction {
    document.setText("new content")
}
```

**With explicit imports (same functionality):**
```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction

val data = readAction { /* ... */ }
writeAction { /* ... */ }
```

## Script Template

**Minimal template (uses built-in helpers):**
```kotlin
// waitForSmartMode() is called automatically before your script starts

// Use IntelliJ APIs with built-in helpers
val result = readAction {
    // PSI/VFS operations here
}

// Output results
println(result)
```

**With optional imports (for specialized APIs):**
```kotlin
import com.intellij.psi.JavaPsiFacade

// Use smartReadAction - combines wait + read
val psiClass = smartReadAction {
    JavaPsiFacade.getInstance(project).findClass("MyClass", allScope())
}
println(psiClass?.qualifiedName)
```

## Context Available in the Script Body

```kotlin
// === Properties ===
project      // IntelliJ Project instance
params       // Tool parameters (JsonElement)
disposable   // For resource cleanup
isDisposed   // Check if disposed

// === Output Methods ===
println("Hello", 42, "world")       // Space-separated output
printJson(object { val x = 1 })     // Pretty JSON
printException("Failed", exception) // Error with stack trace (recommended!)
progress("Step 1 of 3...")          // Progress (throttled 1/sec)

// === Read/Write Actions (NO IMPORTS NEEDED!) ===
val data = readAction { /* PSI/VFS reads */ }      // Suspend read action
writeAction { /* PSI/VFS writes */ }               // Suspend write action
val smart = smartReadAction { /* reads in smart mode */ }  // Auto-waits for indexing

// === Search Scopes (NO IMPORTS NEEDED!) ===
val scope1 = projectScope()  // Project files only
val scope2 = allScope()      // Project + libraries

// === File Access Helpers ===
val vf = findFile("/absolute/path.kt")           // Find VirtualFile
val psi = findPsiFile("/absolute/path.kt")       // Find PsiFile (suspend)
val vf2 = findProjectFile("src/main/App.kt")     // Relative to project
val psi2 = findProjectPsiFile("src/main/App.kt") // Relative to project (suspend)

// === IDE Utilities ===
// waitForSmartMode() runs automatically before your script starts; call it again if you trigger indexing

// === Code Analysis (no window focus required) ===
// runInspectionsDirectly returns Map<String, List<ProblemDescriptor>> - empty if no problems
val file = requireNotNull(findProjectFile("src/main/App.kt")) { "File not found" }
val problems = runInspectionsDirectly(file)
```

## Running Tests

**Always prefer the IntelliJ IDE runner over `./mvnw test` or `./gradlew test`.**
The IDE runner is the equivalent of clicking the green ▶ button next to a test class. It:
- Returns a simple exit code (0 = all passed) — no 200k output to parse
- Shows structured per-test results in the IDE Test Results window
- Reuses the running JVM — faster than spawning a new Maven/Gradle process

See `mcp-steroid://skill/coding-with-intellij` → **"Run Tests via IntelliJ IDE Runner ★ PREFERRED ★"**
for the complete pattern (JUnitConfiguration + ExecutionEnvironmentBuilder + CountDownLatch).

Only fall back to `./mvnw test` / `./gradlew test` when the IDE runner cannot be used (e.g., tests
require a full Maven lifecycle). Even then, **never print the full output** — always `take(30) +
takeLast(30)` to avoid MCP token limit errors.

## Common Patterns

### Get Project Info
```kotlin
println("Project: ${project.name}")
println("Base path: ${project.basePath}")
```

### Get IDE Log Path
```kotlin
val logPath = com.intellij.openapi.application.PathManager.getLogPath()
println("Log: $logPath/idea.log")
```

### List Plugins
```kotlin
com.intellij.ide.plugins.PluginManagerCore.getLoadedPlugins()
    .filter { it.isEnabled }
    .forEach { println("${it.name}: ${it.version}") }
```

### Find Plugin by ID
```kotlin
val plugin = com.intellij.ide.plugins.PluginManagerCore.loadedPlugins
    .find { it.pluginId.idString == "org.jetbrains.kotlin" }
println("Kotlin plugin: ${plugin?.version}")
```

### List Extension Points
```kotlin
project.extensionArea.extensionPoints
    .filter { it.name.contains("kotlin", ignoreCase = true) }
    .forEach { println("${it.name}: ${it.extensionList.size} extensions") }
```

### Navigate Project Files
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
    println("Root: ${root.path}")
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (file.extension == "kt") println("  ${file.path}")
        true
    }
}
```

### Open Another Project (CAUTION: opens new window)
```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

val path = Path.of("/path/to/project")
ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
```

### Restart IDE (CAUTION: destructive operation)
```kotlin
com.intellij.openapi.application.ApplicationManager.getApplication().restart()
```
