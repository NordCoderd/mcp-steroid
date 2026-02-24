## Quick Reference Card

### Context Properties

```kotlin
project: Project         // Current project
params: JsonElement      // Execution parameters
disposable: Disposable   // For cleanup
isDisposed: Boolean      // Check if disposed
```

### Output

```kotlin
println(vararg values)                 // Print values
printJson(obj)                         // JSON output
printException(msg, throwable)         // Error with stack trace
progress(msg)                          // Progress (throttled)
takeIdeScreenshot(fileName)            // Capture screenshot
```

### Read/Write (No imports needed!)

```kotlin
readAction { }           // Read PSI/VFS
writeAction { }          // Write PSI/VFS
smartReadAction { }      // Wait + read
```

### Scopes (No imports needed!)

```kotlin
projectScope()           // Project files only
allScope()               // Project + libraries
```

### File Access

```kotlin
findFile(path)                    // VirtualFile by absolute path
findPsiFile(path)                 // PsiFile by absolute path
findProjectFile(relativePath)     // VirtualFile by project-relative path
findProjectPsiFile(relativePath)  // PsiFile by project-relative path
```

### Code Analysis (Recommended)

```kotlin
runInspectionsDirectly(file, includeInfoSeverity = false)
```

### Common Imports

```kotlin
// PSI
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.searches.ReferencesSearch

// Java PSI
import com.intellij.psi.JavaPsiFacade

// Kotlin PSI
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// VFS
import com.intellij.openapi.vfs.LocalFileSystem

// Editor
import com.intellij.openapi.fileEditor.FileEditorManager

// Commands
import com.intellij.openapi.command.WriteCommandAction
```

### Thread Safety

```kotlin
readAction { }    // For reading PSI/VFS
writeAction { }   // For writing PSI/VFS
smartReadAction { } // Wait for indexing + read
```

### Example: Find and Modify

```kotlin
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import com.intellij.openapi.command.WriteCommandAction

// Find
val ktClass = smartReadAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope()).firstOrNull()
}

// Modify
if (ktClass != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        ktClass.setName("MyNewClass")
    }
}
```

---

## Refactoring: Find All Call Sites Before Adding Parameters

When adding a new field or parameter to a record, command, or DTO class, use `ReferencesSearch`
to locate every call site **before** editing. This prevents compile errors from undiscovered
constructors or factory calls in other files.

```kotlin
// Before adding a field to CreateReleaseCommand — find every constructor call site first
import com.intellij.psi.search.searches.ReferencesSearch
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.commands.CreateReleaseCommand",
        GlobalSearchScope.projectScope(project)
    )
}
if (cmdClass != null) {
    val usages = readAction {
        ReferencesSearch.search(cmdClass, GlobalSearchScope.projectScope(project)).findAll()
    }
    usages.forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
        println("$file:${ref.element.textOffset} → " + ref.element.parent.text.take(120))
    }
} else println("class not found — check FQN")
// Fix every listed call site BEFORE running the compiler.
// This 1 call replaces reading 3-5 files just to find constructors.
```

---

## Docker-Unavailable Fallback: Compile Verification When Tests Cannot Run

When `./mvnw test` fails with `Could not find a valid Docker environment` or a `DockerException`,
Testcontainers cannot start the database container. Your code changes may be correct even though
no test passed. Use this two-step verification:

**Step 1 — Confirm it is a Docker-only failure** (not a compile or logic error):
```kotlin
// Run this after a test failure to determine whether Docker is the sole blocker:
val proc = ProcessBuilder("./mvnw", "test", "-Dtest=MyIntegrationTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
proc.waitFor()
val dockerError = lines.any { "Could not find a valid Docker" in it || "DockerException" in it }
println("Docker-only failure: $dockerError")
println(lines.takeLast(20).joinToString("\n"))
```

**Step 2 — Verify compilation separately** (only when step 1 confirms Docker is the sole blocker):
```kotlin
// Verify compile with test-compile (faster than full test, no Docker needed):
val proc = ProcessBuilder("./mvnw", "test-compile", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exitCode = proc.waitFor()
println("test-compile exit: $exitCode")
println(lines.filter { "ERROR" in it || "BUILD" in it || "FAILURE" in it }.joinToString("\n"))
// Exit code 0 = compilation success → safe to report ARENA_FIX_APPLIED: yes with caveat
// Exit code 1 = compile errors → fix errors first
```

**Reporting rule** (only when both conditions hold):
- `./mvnw test` failed with an explicit `DockerException` / `Could not find a valid Docker environment`
- `./mvnw test-compile` exits 0

→ Report: `ARENA_FIX_APPLIED: yes`
→ Note: `(tests blocked by Docker unavailability — compilation verified via test-compile)`

**Do NOT** apply this path when tests fail for any other reason (logic errors, missing methods,
Spring context startup failures). The Docker-unavailable path is ONLY valid when the error is
literally "cannot connect to Docker daemon."

---

**End of Guide**

For more examples, see the MCP resources:
- `mcp-steroid://lsp/overview` - LSP-like examples
- `mcp-steroid://ide/overview` - IDE power operations
- `mcp-steroid://debugger/overview` - Debugger examples

**IntelliJ Platform SDK**: https://plugins.jetbrains.com/docs/intellij/
**Source Code**: https://github.com/JetBrains/intellij-community
