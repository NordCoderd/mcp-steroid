# Coding with IntelliJ APIs - Comprehensive Guide for AI Agents

This guide teaches you how to write effective Kotlin code that executes inside IntelliJ IDEA's runtime environment via `steroid_execute_code`. You'll learn the execution model, available APIs, and best practices for working with PSI (Program Structure Interface), VFS (Virtual File System), and other IntelliJ platform APIs.

---

## Table of Contents

1. [Introduction](#introduction)
2. [Execution Model](#execution-model)
3. [McpScriptContext API Reference](#mcpscriptcontext-api-reference)
4. [Threading and Read/Write Actions](#threading-and-readwrite-actions)
5. [Common Patterns](#common-patterns)
6. [PSI Operations](#psi-operations)
7. [Code Analysis](#code-analysis)
8. [Document and Editor Operations](#document-and-editor-operations)
9. [VFS Operations](#vfs-operations)
10. [Refactoring Operations](#refactoring-operations)
11. [Code Completion and Introspection](#code-completion-and-introspection)
12. [Services and Components](#services-and-components)
13. [Error Handling](#error-handling)
14. [Best Practices](#best-practices)
15. [Quick Reference Card](#quick-reference-card)

---

## Introduction

### What is steroid_execute_code?

`steroid_execute_code` is an MCP tool that executes Kotlin code directly inside IntelliJ IDEA's JVM. Your code runs with full access to:

- **Project model** - modules, dependencies, source roots
- **PSI (Program Structure Interface)** - parsed code representation
- **VFS (Virtual File System)** - file access layer
- **IntelliJ indices** - fast code search and navigation
- **Editor APIs** - document manipulation, caret position
- **Refactoring APIs** - automated code transformations
- **Inspection APIs** - code quality analysis

### Why Use IntelliJ APIs Over File Operations?

| Instead of... | Use IntelliJ API | Why? |
|--------------|------------------|------|
| `grep`, `find` | PSI search, Find Usages | Understands code structure, not just text |
| Reading files with `cat` | VFS and PSI APIs | Respects IDE's caching and encoding |
| Manual text replacement | Refactoring APIs | Maintains code correctness and formatting |
| Guessing code structure | Query project model | IDE has already indexed everything |

**The IDE knows the code better than any file search tool.**

### Learning Curve

**Important**: Writing IntelliJ API code may require several attempts. This is normal! The API surface is vast and powerful. Keep trying - each attempt teaches you more about the available APIs.

- Use `printException(msg, throwable)` to see full stack traces
- Check return types and nullability
- Use reflection to discover available methods
- Consult the [IntelliJ Platform SDK docs](https://plugins.jetbrains.com/docs/intellij/)

---

## Execution Model

### Script Structure

Your code is the **suspend function body**. You do NOT need an `execute { }` wrapper.

```kotlin
// ✓ CORRECT - This is your script
println("Hello from IntelliJ!")
val projectName = project.name
println("Project: $projectName")

// ✗ WRONG - Do not wrap in execute { }
execute {
    println("Hello")  // ERROR: execute is not defined
}
```

### Script is a Coroutine

The script body runs as a **suspend function**. This means:

- Use coroutine APIs directly (no `runBlocking` needed)
- Call suspend functions without special wrappers
- Use `delay()` instead of `Thread.sleep()`

```kotlin
// ✓ CORRECT - Direct coroutine usage
delay(1000)
progress("Step 1 complete")

// ✗ WRONG - Never use runBlocking
runBlocking {  // ERROR: Causes deadlocks!
    delay(1000)
}
```

### ⚠️ Helper Functions Must Be `suspend` When Calling Suspend APIs

If you define a local helper function inside your script that calls any suspend API (`runInspectionsDirectly`, `readAction`, `writeAction`, `smartReadAction`, etc.), the helper **must be declared `suspend fun`**. Omitting `suspend` causes a compile error: `"suspension functions can only be called within coroutine body"`.

```kotlin
// ✗ WRONG — non-suspend helper calling a suspend API
fun checkFile(vf: VirtualFile) {
    val problems = runInspectionsDirectly(vf)  // ERROR: suspend call in non-suspend fun
    println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
}

// ✓ CORRECT — declare the helper as suspend
suspend fun checkFile(vf: VirtualFile) {
    val problems = runInspectionsDirectly(vf)  // OK: suspend call in suspend fun
    println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
}

// ✓ ALTERNATIVE — inline the call directly in the script body (no helper needed):
val problems = runInspectionsDirectly(vf)
println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
```

This applies to ALL suspend context APIs: `readAction { }`, `writeAction { }`, `smartReadAction { }`, `waitForSmartMode()`, `runInspectionsDirectly()`, `findPsiFile()`, `findProjectPsiFile()`.

### Automatic Smart Mode

`waitForSmartMode()` is called **automatically before your script starts**. You only need to call it again if you trigger indexing mid-script.

```kotlin
// Smart mode already waited - safe to use indices immediately
val classes = readAction {
    JavaPsiFacade.getInstance(project)
        .findClass("com.example.MyClass", allScope())
}

// Only call again if you trigger re-indexing
// (rare - most operations don't trigger indexing)
```

> **Bulk file creation triggers re-indexing**: Writing new files via `writeAction { VfsUtil.saveText(...) }` causes IntelliJ to re-index those files.
> - **In a subsequent exec_code call**: Safe — `waitForSmartMode()` runs automatically at script start, so PSI is up-to-date by the time your code runs.
> - **In the same exec_code call** (create files then immediately inspect them): call `waitForSmartMode()` explicitly after the `writeAction` block and before any `runInspectionsDirectly` / `ReferencesSearch` / `JavaPsiFacade.findClass()` calls on the new files.
>
> ```kotlin
> // Pattern: create files AND inspect in the SAME exec_code call
> writeAction {
>     val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
>     val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example")
>     val f = dir.findChild("MyService.java") ?: dir.createChildData(this, "MyService.java")
>     VfsUtil.saveText(f, "package com.example;\npublic class MyService {}")
> }
> waitForSmartMode()  // ← flush PSI index before inspecting the newly created file
> val vf = findProjectFile("src/main/java/com/example/MyService.java")!!
> val problems = runInspectionsDirectly(vf)
> println(if (problems.isEmpty()) "OK" else problems.toString())
> ```
>
> **Best practice**: Create files in one exec_code call, then inspect in a separate exec_code call — `waitForSmartMode()` runs automatically between calls.

### Execution Flow

1. **Submit code** via `steroid_execute_code`
2. **Review phase** (if enabled) - human approval
3. **Compilation** - Kotlin script engine compiles your code
   - Fast failure if compilation errors occur
4. **Execution** - Your script body runs with timeout
   - Progress messages throttled to 1/second
   - Context disposed when complete
5. **Response** - Output returned to MCP client

### Fast Failure

Errors are reported immediately (no waiting for timeout):

- **Script engine not available** → ERROR immediately
- **Compilation errors** → ERROR with details immediately
- **Runtime errors** → ERROR with stack trace
- **Timeout** → Execution cancelled, resources cleaned up

---

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

## Threading and Read/Write Actions

> **⚠️ THREADING RULE — NEVER SKIP**: Any PSI access (JavaPsiFacade, PsiShortNamesCache, PsiManager.findFile, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. This is the most common first-attempt error when writing IntelliJ scripts.

### IntelliJ Threading Model

IntelliJ Platform has strict threading rules:

1. **EDT (Event Dispatch Thread)** - UI updates only
2. **Read actions** required for PSI/VFS reads
3. **Write actions** required for PSI/VFS writes
4. **Smart mode** required for index-dependent operations

**See**: [IntelliJ Threading Rules](https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html)

### Using Built-in Read Actions

```kotlin
// ✓ CORRECT - Use built-in readAction (no import needed)
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}

// Also correct - but built-in is more convenient
import com.intellij.openapi.application.readAction
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

### Using Built-in Write Actions

```kotlin
// ✓ CORRECT - Use built-in writeAction (no import needed)
writeAction {
    document.setText("new content")
}

// For command-wrapped writes (shows in undo stack)
import com.intellij.openapi.command.WriteCommandAction
WriteCommandAction.runWriteCommandAction(project) {
    document.insertString(0, "// Added comment\n")
}
```

**⚠️ writeAction { } is NOT a coroutine scope**: Calling `readAction { }`, `VfsUtil.saveText()`, or ANY suspend function inside `writeAction { }` throws:
```
suspension functions can only be called within coroutine body
```
This error appears at **runtime** (not at compile time), so it's easy to miss. The fix is simple — always **read first, write second**:

```kotlin
// ✗ WRONG — readAction inside writeAction causes runtime error
writeAction {
    val text = readAction { VfsUtil.loadText(vf) }  // ERROR: suspend function in non-coroutine
    VfsUtil.saveText(vf, text.replace(...))
}

// ✓ CORRECT — read outside, write inside
val text = VfsUtil.loadText(vf)           // read OUTSIDE writeAction (VfsUtil.loadText is NOT suspend)
val updated = text.replace("\"api\"", "\"/api/v1\"")
writeAction { VfsUtil.saveText(vf, updated) }   // write INSIDE — no suspend calls allowed here
```

**Note**: `VfsUtil.loadText(vf)` is a regular function (not suspend) — it's safe to call outside any action. `VfsUtil.saveText(vf, text)` is also a regular function but requires a write lock, so it must go inside `writeAction { }`.

If you genuinely need suspend calls inside a write block, use `edtWriteAction { }` instead of `writeAction { }` — it is a suspend function that acquires the write lock.

### Smart Read Actions (Recommended)

Use `smartReadAction` when you need both smart mode and read access:

```kotlin
// ✓ RECOMMENDED - Combines waitForSmartMode() + readAction in one call
val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}

// Equivalent to:
waitForSmartMode()
val classes = readAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}
```

### Smart Mode vs Dumb Mode

During indexing, the IDE is in "dumb mode" - many APIs are unavailable:

```kotlin
import com.intellij.openapi.project.DumbService

if (DumbService.isDumb(project)) {
    println("IDE is indexing - indices not available")
} else {
    println("Smart mode - all APIs available")
}
```

**Good news**: `waitForSmartMode()` is called automatically before your script starts!

### Modal Dialogs and ModalityState

When a modal dialog is open in the IDE, the default EDT dispatcher (`Dispatchers.EDT`) will
**not execute** your code — it waits until the dialog is dismissed. To interact with the IDE
while a modal dialog is present, use `ModalityState.any()`:

```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ✓ CORRECT - Runs on EDT even when a modal dialog is showing
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    // Enumerate windows, inspect dialogs, close dialogs, etc.
}
```

**When to use `ModalityState.any()`:**
- Enumerating open windows or dialogs while a modal is present
- Taking screenshots when a dialog is blocking the IDE
- Closing modal dialogs programmatically (e.g., `dialog.close(...)`)
- Any EDT work that must run regardless of modal state

**When NOT to use it:**
- Normal UI operations — use plain `Dispatchers.EDT` instead
- Read/write actions — use `readAction { }` / `writeAction { }` instead

**Detecting modal dialogs:**
```kotlin
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    val isModal = ModalityState.current() != ModalityState.nonModal()
    println("Modal dialog showing: $isModal")
}
```

---

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
import com.intellij.ide.plugins.PluginManagerCore

PluginManagerCore.getLoadedPlugins()
    .filter { it.isEnabled }
    .forEach { println("${it.name}: ${it.version}") }
```

### Find Plugin by ID

```kotlin
import com.intellij.ide.plugins.PluginManagerCore

val plugin = PluginManagerCore.loadedPlugins
    .find { it.pluginId.idString == "org.jetbrains.kotlin" }
println("Kotlin plugin: ${plugin?.version}")
```

### Check Plugin Installed (Before Using Plugin APIs)

> **⚠️ Do NOT call `PluginsAdvertiser.installAndEnable` or any programmatic plugin installer.**
> These APIs change signatures between IntelliJ versions and throw `IllegalArgumentException` /
> `IllegalAccessError` at runtime (2025.x+). Always check first; if not installed, use `required_plugins`
> parameter instead and let the tool system handle it.

```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Check if a plugin is installed (and loaded) — use this BEFORE calling any plugin-specific API
val pluginId = PluginId.getId("com.intellij.database")  // replace with the plugin you need
val installed = PluginManagerCore.isPluginInstalled(pluginId)
val loaded = PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
println("Plugin $pluginId: installed=$installed loaded=$loaded")

// If not loaded: do NOT attempt installation. Instead, report the missing plugin ID and stop.
// The steroid_execute_code `required_plugins` parameter is the correct way to declare dependencies.
```

### Navigate Project Files

```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

// ⚠️ contentRoots accesses the project model — must be inside readAction { }
val roots = readAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
roots.forEach { root ->
    println("Root: ${root.path}")
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (file.extension == "kt") println("  ${file.path}")
        true
    }
}
```

### Check File Type in Project

```kotlin
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")

if (vf != null) {
    val fileIndex = ProjectFileIndex.getInstance(project)

    println("Is in project: ${fileIndex.isInProject(vf)}")
    println("Is in source: ${fileIndex.isInSource(vf)}")
    println("Is in test source: ${fileIndex.isInTestSourceContent(vf)}")
    println("Is in library: ${fileIndex.isInLibraryClasses(vf)}")

    val module = fileIndex.getModuleForFile(vf)
    println("Module: ${module?.name}")
}
```

---

## PSI Operations

PSI (Program Structure Interface) is IntelliJ's parsed representation of source code. It provides a rich API for code analysis and manipulation.

### End-to-End Example: Find Kotlin Class Methods

```kotlin
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// smartReadAction = waitForSmartMode() + readAction { } in one call
smartReadAction {
    // Use built-in projectScope() helper
    val classes = KotlinClassShortNameIndex.get("MyService", project, projectScope())

    if (classes.isEmpty()) {
        println("No class named 'MyService' found")
        return@smartReadAction
    }

    classes.forEach { ktClass ->
        println("Class: ${ktClass.fqName}")
        println("File: ${ktClass.containingFile.virtualFile.path}")

        // List all methods
        ktClass.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
            .forEach { method ->
                val params = method.valueParameters.joinToString { "${it.name}: ${it.typeReference?.text}" }
                val returnType = method.typeReference?.text ?: "Unit"
                println("  fun ${method.name}($params): $returnType")
            }
    }
}
```

### Find Usages

```kotlin
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// smartReadAction = waitForSmartMode() + readAction
smartReadAction {
    // Use built-in projectScope() helper
    val classes = KotlinClassShortNameIndex.get("MyService", project, projectScope())
    val targetClass = classes.firstOrNull()

    if (targetClass == null) {
        println("Class not found")
        return@smartReadAction
    }

    // Find all usages using findAll() (returns a Collection)
    val usages = ReferencesSearch.search(targetClass, projectScope()).findAll()

    println("Found ${usages.size} usages of ${targetClass.name}:")
    usages.forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path
        val offset = ref.element.textOffset
        println("  $file:$offset")
    }
}
```

### PSI Tree Navigation

```kotlin
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiElement
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)

    // Navigate parent chain
    val element = psiFile?.findElementAt(100) // element at offset 100
    var current: PsiElement? = element
    while (current != null) {
        println("${current.javaClass.simpleName}: ${current.text.take(50)}")
        current = current.parent
    }
}
```

### Find Elements by Type

```kotlin
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClass

readAction {
    val psiFile = findPsiFile("/path/to/file.kt")

    if (psiFile != null) {
        // Find all functions in file
        val functions = PsiTreeUtil.findChildrenOfType(psiFile, KtNamedFunction::class.java)
        functions.forEach { fn ->
            println("Function: ${fn.name} at line ${fn.textOffset}")
        }

        // Find all classes
        val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
        classes.forEach { cls ->
            println("Class: ${cls.name}")
        }
    }
}
```

### Java PSI - Find Class by FQN

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

readAction {
    val facade = JavaPsiFacade.getInstance(project)
    val psiClass = facade.findClass(
        "com.example.MyClass",
        GlobalSearchScope.allScope(project)
    )

    if (psiClass != null) {
        println("Found class: ${psiClass.qualifiedName}")
        println("File: ${psiClass.containingFile.virtualFile.path}")

        // List methods
        psiClass.methods.forEach { method ->
            val params = method.parameterList.parameters
                .joinToString { "${it.name}: ${it.type.presentableText}" }
            println("  ${method.name}($params): ${method.returnType?.presentableText}")
        }

        // List fields
        psiClass.fields.forEach { field ->
            println("  field ${field.name}: ${field.type.presentableText}")
        }
    }
}
```

### Find Class Hierarchy

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

readAction {
    val baseClass = JavaPsiFacade.getInstance(project)
        .findClass("java.util.List", GlobalSearchScope.allScope(project))

    if (baseClass != null) {
        // Find all implementations
        val inheritors = ClassInheritorsSearch.search(
            baseClass,
            GlobalSearchScope.projectScope(project),
            true // include anonymous
        ).findAll()

        println("Found ${inheritors.size} implementations of ${baseClass.name}")
        inheritors.take(20).forEach { impl ->
            println("  ${impl.qualifiedName}")
        }
    }
}
```

---

## Code Analysis

### Run Inspections Directly (Recommended)

**⚠️ WARNING**: The daemon code analyzer returns stale results if the IDE window is not focused. Always use `runInspectionsDirectly()` for reliable results.

```kotlin
// ✓ RECOMMENDED - Reliable regardless of window focus
val file = requireNotNull(findProjectFile("src/main/kotlin/MyClass.kt")) { "File not found" }

val problems = runInspectionsDirectly(file)

if (problems.isEmpty()) {
    println("No problems found!")
} else {
    problems.forEach { (inspectionId, descriptors) ->
        descriptors.forEach { problem ->
            val element = problem.psiElement
            val line = if (element != null) {
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(element.containingFile)
                doc?.getLineNumber(element.textOffset)?.plus(1) ?: "?"
            } else "?"
            println("[$inspectionId] Line $line: ${problem.descriptionTemplate}")
        }
    }
}
```

**Parameters:**
- `file`: VirtualFile to inspect
- `includeInfoSeverity`: Include INFO-level problems (default: false, only WEAK_WARNING and above)

**Returns:** `Map<String, List<ProblemDescriptor>>` - inspection tool ID to problems found

### Get Errors and Warnings (Daemon-based, requires window focus)

**Note**: This approach may return stale results if the IDE window is not focused. Prefer `runInspectionsDirectly()` for MCP use cases.

```kotlin
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)
    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)

    if (psiFile != null && document != null) {
        val highlights = DaemonCodeAnalyzerEx.getInstanceEx(project)
            .getFileLevelHighlights(project, psiFile)

        highlights.forEach { info ->
            val severity = info.severity
            println("[$severity] ${info.description} at ${info.startOffset}")
        }
    }
}
```

---

## Document and Editor Operations

### Read Document Content

```kotlin
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val document = FileDocumentManager.getInstance().getDocument(vf!!)

    if (document != null) {
        println("Lines: ${document.lineCount}")
        println("Length: ${document.textLength}")

        // Get specific line
        val lineNum = 5
        if (lineNum < document.lineCount) {
            val startOffset = document.getLineStartOffset(lineNum)
            val endOffset = document.getLineEndOffset(lineNum)
            println("Line $lineNum: ${document.getText().substring(startOffset, endOffset)}")
        }
    }
}
```

### Modify Document

**CAUTION: This modifies files on disk!**

```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
val document = FileDocumentManager.getInstance().getDocument(vf!!)

if (document != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        // Insert at position
        document.insertString(0, "// Added comment\n")

        // Or replace text
        // document.replaceString(startOffset, endOffset, "new text")

        // Or delete
        // document.deleteString(startOffset, endOffset)
    }
    println("Document modified")
}
```

### Access Current Editor

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager

readAction {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor

    if (editor != null) {
        val document = editor.document
        val caretModel = editor.caretModel
        val selectionModel = editor.selectionModel

        println("Current file: ${editor.virtualFile?.name}")
        println("Caret offset: ${caretModel.offset}")
        println("Caret line: ${caretModel.logicalPosition.line}")

        if (selectionModel.hasSelection()) {
            println("Selected: ${selectionModel.selectedText}")
        }
    } else {
        println("No editor open")
    }
}
```

---

## VFS Operations

### Read File Content

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.txt")

if (vf != null && !vf.isDirectory) {
    val content = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
    println("File content (${content.length} chars):")
    println(content.take(500))
}
```

### Refresh a Specific File

Use this only when you know a file changed outside the IDE:

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

val path = "/path/to/file.txt"
val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
if (vf != null) {
    VfsUtil.markDirtyAndRefresh(false, false, false, vf)
}
```

### List Directory Contents

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

val dir = LocalFileSystem.getInstance().findFileByPath("/path/to/directory")

if (dir != null && dir.isDirectory) {
    dir.children.forEach { child ->
        val type = if (child.isDirectory) "DIR" else "FILE"
        println("[$type] ${child.name}")
    }
}
```

### Create File

**CAUTION: This modifies the filesystem!**

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

writeAction {
    val parentDir = LocalFileSystem.getInstance().findFileByPath("/path/to/dir")
    if (parentDir != null) {
        val newFile = parentDir.createChildData(this, "newfile.txt")
        newFile.setBinaryContent("Hello, World!".toByteArray())
        println("Created: ${newFile.path}")
    }
}
```

### Create Java/Kotlin Source Files (Preferred Pattern)

Use `VfsUtil.createDirectoryIfMissing` + `VfsUtil.saveText` — safer than shell heredocs and atomic.

**⚠️ ALL VFS mutation ops need writeAction**: `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require writeAction. `createDirectoryIfMissing(VirtualFile parent, String relative)` also requires writeAction — use this overload inside writeAction. Note: `createDirectoryIfMissing(String absolutePath)` self-acquires a write lock internally (safe to call outside writeAction, but DO NOT call it inside writeAction). Put the ENTIRE create-directory-and-write sequence inside a SINGLE writeAction block using the VirtualFile overload:

```kotlin
// ✓ CORRECT — everything that creates or modifies files goes INSIDE writeAction:
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")  // ← writeAction required
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")   // ← writeAction required
    VfsUtil.saveText(f, content)                                                          // ← writeAction required
}
// ✗ WRONG:
// val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/...")  // OUTSIDE writeAction → throws!
// writeAction { VfsUtil.saveText(f, content) }                           // only saveText inside = WRONG
```

**⚠️ AVOID range-based VFS writes**: Never use hardcoded byte ranges when writing files (e.g., `setBinaryContent(bytes, 0, 2000)` when the file may be shorter). This causes `StringIndexOutOfBoundsException` when the range exceeds file length. Always use `VfsUtil.saveText(file, content)` for full-file replacement — it atomically replaces the entire content regardless of existing file size.

**⚠️ Import-in-strings pitfall**: The script preprocessor extracts `import foo.Bar;` lines from the top level of your script — including lines inside triple-quoted strings. This causes compilation failures (e.g., `unresolved reference 'jakarta'`) when you embed Java source in a `"""..."""` literal.

**⚠️ Char-literal pitfall in string-assembled Java**: When building Java source via Kotlin `joinToString()`, char literals like `'\''` cause silent escaping errors. The Kotlin string `"'\\''"` produces Java text `'\''` which is a Java syntax error (empty char literal followed by spurious `'`). For Java code containing char literals (e.g., `toString()` with `', '` separators), prefer `java.io.File.writeText()` with triple-quoted raw strings, or use `PsiFileFactory.createFileFromText()`:

```kotlin
// ✓ SAFE: Use java.io.File for Java source with char literals — not affected by import extraction
java.io.File("${project.basePath}/src/main/java/com/example/model/Product.java")
    .also { it.parentFile.mkdirs() }
    .writeText("""
        package com.example.model;
        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;
        @Entity
        public class Product {
            @Id private Long id;
            private String name;
            @Override public String toString() {
                return "Product{id=" + id + ", name='" + name + '\'' + "}";
            }
        }
    """.trimIndent())
LocalFileSystem.getInstance().refreshAndFindFileByPath("${project.basePath}/src/main/java/com/example/model/Product.java")
println("Created Product.java")
// Verify the write succeeded:
val vf = findProjectFile("src/main/java/com/example/model/Product.java")!!
check(VfsUtil.loadText(vf).contains("class Product")) { "Write failed or file is empty" }
println("Verified: Product.java written correctly")
```

**Workaround for joinToString**: Use `joinToString()` or string concatenation for the Java source content:

```kotlin
writeAction {
    // Create package directories
    // DEPRECATED: project.baseDir — use LocalFileSystem instead:
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val srcDir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")

    // Build Java source using joinToString — avoids import-extraction bug
    val content = listOf(
        "package com.example.model;",
        "import" + " jakarta.persistence.Entity;",
        "import" + " jakarta.persistence.GeneratedValue;",
        "import" + " jakarta.persistence.GenerationType;",
        "import" + " jakarta.persistence.Id;",
        "",
        "@Entity",
        "public class Product {",
        "    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)",
        "    private Long id;",
        "    private String name;",
        "    // getters/setters...",
        "}"
    ).joinToString("\n")

    val f = srcDir.findChild("Product.java") ?: srcDir.createChildData(this, "Product.java")
    VfsUtil.saveText(f, content)
    println("Created: ${f.path}")
}
```

Alternative: use `java.io.File` which is not affected by the preprocessor:

```kotlin
java.io.File("/path/to/project/src/main/java/com/example/Product.java").also { it.parentFile.mkdirs() }.writeText("""
    package com.example;
    import jakarta.persistence.Entity;
    @Entity public class Product { }
""".trimIndent())
// Then refresh the VFS so IntelliJ picks up the new file
LocalFileSystem.getInstance().refreshAndFindFileByPath("/path/to/project/src/main/java/com/example/Product.java")
println("File created and VFS refreshed")
```

---

## Java / Spring Boot Patterns

> **Step -1 — Multi-agent coordination: Check VCS changes BEFORE writing any code**
>
> Multiple agent slots may share the same IntelliJ project simultaneously. Before writing a
> single file, check what another slot has already created or modified:
>
> ```kotlin
> val changes = readAction {
>     com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
>         .allChanges.mapNotNull { it.virtualFile?.path }
> }
> println(if (changes.isEmpty()) "Clean slate" else "FILES ALREADY MODIFIED:\n" + changes.joinToString("\n"))
> // If files are listed: read them BEFORE writing to avoid overwriting parallel-agent work
> ```

> **Step 0 — Explore with PSI BEFORE reading files**
>
> When you need to understand a class's methods, fields, or call-sites, use PSI structural
> queries instead of reading file contents. **1 PSI call replaces 5-10 VfsUtil.loadText calls.**
>
> ```kotlin
> // Inspect class structure — no file read needed:
> val cls = readAction {
>     JavaPsiFacade.getInstance(project).findClass(
>         "com.example.domain.FeatureService",
>         GlobalSearchScope.projectScope(project)
>     )
> }
> cls?.methods?.forEach { m ->
>     val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
>     println("${m.name}($params): ${m.returnType?.presentableText}")
> }
> // Find all callers (replaces grepping source files):
> import com.intellij.psi.search.searches.ReferencesSearch
> ReferencesSearch.search(cls!!, projectScope()).findAll().forEach { ref ->
>     println("${ref.element.containingFile.name} → ${ref.element.parent.text.take(80)}")
> }
> ```
>
> **Rule**: Before reading a 3rd file just to trace code flow, try `ReferencesSearch.search()`
> or `JavaPsiFacade.findClass()`. These answer in 1 round-trip what file reading takes 5-10 calls.

> **Step 2 — Do This FIRST Before Creating Any Migration File**
>
> Always determine the next available Flyway migration version number before writing `V{N}__*.sql`.
> Creating `V5__` when `V5__` already exists breaks Flyway on startup (checksum conflict).
>
> ```kotlin
> val migDir = findProjectFile("src/main/resources/db/migration")!!
> val nextVersion = readAction {
>     migDir.children.map { it.name }
>         .mapNotNull { Regex("""V(\d+)__""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
>         .maxOrNull()?.plus(1) ?: 1
> }
> println("Existing migrations:")
> readAction { migDir.children.map { it.name }.sorted() }.forEach { println("  $it") }
> println("NEXT_MIGRATION_VERSION=V$nextVersion")
> // Use this output as the prefix for your new migration file name
> ```

### Find All @Entity / @Service / @RestController Classes

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.searches.AnnotatedElementsSearch

// Find all JPA @Entity classes
val entityClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
        ?: JavaPsiFacade.getInstance(project).findClass("javax.persistence.Entity", allScope())
}
if (entityClass != null) {
    val entities = AnnotatedElementsSearch.searchPsiClasses(entityClass, projectScope()).findAll()
    println("@Entity classes (${entities.size}):")
    entities.forEach { println("  ${it.qualifiedName} in ${it.containingFile.virtualFile.path}") }
}

// Find all Spring @Service classes
val serviceClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("org.springframework.stereotype.Service", allScope())
}
if (serviceClass != null) {
    AnnotatedElementsSearch.searchPsiClasses(serviceClass, projectScope()).findAll()
        .forEach { println("@Service: ${it.qualifiedName}") }
}

// Find all @RestController classes
val rcClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.RestController", allScope())
}
if (rcClass != null) {
    AnnotatedElementsSearch.searchPsiClasses(rcClass, projectScope()).findAll()
        .forEach { println("@RestController: ${it.qualifiedName}") }
}
```

### Check if a Class Already Exists (prevent duplicate file creation)

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val existing = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.MyClass",
        GlobalSearchScope.projectScope(project)
    )
}
if (existing != null) {
    println("EXISTS: " + existing.containingFile.virtualFile.path)
} else {
    println("NOT_FOUND: safe to create")
    // ... create the file
}
```

### Check Jakarta vs javax Import Conflicts

```kotlin
import com.intellij.psi.JavaPsiFacade

// Check which persistence API is available (Jakarta EE 3 vs older javax)
val hasJakarta = readAction {
    JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope()) != null
}
val hasJavax = readAction {
    JavaPsiFacade.getInstance(project).findClass("javax.persistence.Entity", allScope()) != null
}
println("Has jakarta.persistence: $hasJakarta")
println("Has javax.persistence: $hasJavax")
// Use the correct import prefix in your generated files
val persistencePrefix = if (hasJakarta) "jakarta" else "javax"
println("Use: ${persistencePrefix}.persistence.Entity")
```

### Find All Usages of a Class (Call Sites / Constructor Invocations)

**CRITICAL**: When adding a new field to a command/DTO/entity class, always find all call sites
*before* writing any code. Missing even one call site causes a compile error.

> **⚠️ Safe Constructor/Signature Change Recipe**: `runInspectionsDirectly` is file-scoped and
> does NOT catch cross-file compile errors from constructor changes. Before adding a parameter to
> any constructor, record, or method signature: (1) run `ReferencesSearch` to find ALL call sites,
> (2) update every call site in the same exec_code session, (3) then run `./mvnw compile -q` to
> verify project-wide correctness. Skipping step 1 causes "cannot find symbol" errors that only
> surface during test execution, not during file-level inspection.

```kotlin
import com.intellij.psi.search.searches.ReferencesSearch

// Find every place that constructs or references CreateReleaseCommand
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.CreateReleaseCommand",
        GlobalSearchScope.projectScope(project)
    )
}
if (cmdClass != null) {
    val refs = ReferencesSearch.search(cmdClass, projectScope()).findAll()
    println("Found ${refs.size} usages:")
    refs.forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
        val snippet = ref.element.parent.text.take(100)
        println("  $file → $snippet")
    }
} else println("Class not found")
```

### Find @Repository Methods with @Query Annotations

Inspect existing DB query patterns before adding new queries:

```kotlin
val repo = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.ReleaseRepository",
        GlobalSearchScope.projectScope(project)
    )
}
repo?.methods?.forEach { method ->
    val queryAnnotation = method.annotations.firstOrNull {
        it.qualifiedName == "org.springframework.data.jpa.repository.Query" ||
        it.qualifiedName?.endsWith(".Query") == true
    }
    if (queryAnnotation != null) {
        val value = queryAnnotation.findAttributeValue("value")?.text ?: "<no value>"
        val nativeQ = queryAnnotation.findAttributeValue("nativeQuery")?.text ?: "false"
        println("@Query(nativeQuery=$nativeQ) ${method.name}: $value")
    } else {
        println("derived-query: ${method.name}")
    }
}
```

### Validate Spring Data JPA Repository After Adding Derived Query Methods

**Always run `runInspectionsDirectly` on the repository file immediately after adding derived query methods.** Spring Data JPA method names like `findByFeature_Code` and `findByParentComment_Id` follow strict naming conventions derived from entity field paths. They compile fine in Java but throw `QueryCreationException` at Spring context startup — which only surfaces during `./mvnw test`, not during `./mvnw test-compile`.

> **Rule**: Inspect every file you **modify** — not just files you **create**. The most common undetected error pattern is: inspections pass on all newly created files, but the modified repository has a subtly invalid method name that causes a 90+ second Maven test failure. Catching it with `runInspectionsDirectly` (~5s) prevents that wasted turn.

```kotlin
// After modifying a Spring Data JPA repository (adding new findBy... methods):
val repoVf = findProjectFile("src/main/java/com/example/CommentRepository.java")!!
val problems = runInspectionsDirectly(repoVf)
if (problems.isEmpty()) println("OK: repository methods are valid")
else problems.forEach { (id, d) -> d.forEach { println("[$id] ${it.descriptionTemplate}") } }
// Spring Data Plugin reports: SpringDataMethodInconsistency, invalid derived query names, etc.
// Example valid derived queries for a Comment entity with Feature and ParentComment fields:
//   findByFeature_Code(String code)       → traverses Comment.feature.code
//   findByParentComment_Id(Long id)       → traverses Comment.parentComment.id
// Example invalid: findByFeatureCode(String code) if field is 'feature.code' not 'featureCode'
```

**Batch: inspect multiple modified files at once**
```kotlin
// Inspect both modified file AND newly created files in a single call
for (path in listOf(
    "src/main/java/com/example/CommentRepository.java",   // ← MODIFIED (added findBy methods)
    "src/main/java/com/example/CommentService.java",      // ← CREATED
    "src/main/java/com/example/api/CommentController.java" // ← CREATED
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("OK: $path")
    else problems.forEach { (id, d) -> d.forEach { println("[$id] $path: ${it.descriptionTemplate}") } }
}
```

### Inspect JPA Entity Fields (Parent-Child Relationships)

Understand existing JPA mappings before adding `@ManyToOne` / `@OneToMany` relationships:

```kotlin
val entityClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.Release",
        GlobalSearchScope.projectScope(project)
    )
}
entityClass?.fields?.forEach { field ->
    val jpaAnnotations = field.annotations.filter { ann ->
        listOf("Id", "Column", "ManyToOne", "OneToMany", "ManyToMany", "OneToOne", "JoinColumn")
            .any { ann.qualifiedName?.endsWith(it) == true }
    }
    if (jpaAnnotations.isNotEmpty()) {
        println("${field.name}: ${field.type.presentableText} → ${jpaAnnotations.map { it.qualifiedName?.substringAfterLast('.') }}")
    }
}
```

### Read pom.xml / Test Files via VFS

```kotlin
// Read pom.xml
val pomContent = VfsUtil.loadText(findProjectFile("pom.xml")!!)
println(pomContent)

// Read a specific test file to understand its assertions before implementing
val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/ProductTest.java")!!)
println(testContent)
```

### Targeted File Read (Minimal Context — Avoid Context Bloat)

Instead of printing the full file, filter for the lines you need:

```kotlin
// Extract only test assertions and endpoint URLs from a large test file
val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/MyIntegrationTest.java")!!)
testContent.lines()
    .filter { it.contains("assert") || it.contains("/api/") || it.contains("@Test") || it.trim().startsWith("//") }
    .forEach { println(it) }
```

This is much cheaper than reading the full file when you only need to know what a test asserts.

### Discover Existing Class Naming Conventions

Before creating a new class, check what naming patterns already exist in the project to avoid mismatches (e.g., `EventType` vs `NotificationEventType`):

```kotlin
import com.intellij.psi.search.PsiShortNamesCache

val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
// Print domain model names to understand naming conventions
allNames.filter { name ->
    name.endsWith("Status") || name.endsWith("Type") || name.endsWith("Dto") ||
    name.endsWith("Entity") || name.endsWith("Service") || name.endsWith("Repository")
}.sorted().forEach { println(it) }
```

### Find Next Flyway Migration Version Number

Avoid creating a migration with a version number that already exists:

```kotlin
val migDir = findProjectFile("src/main/resources/db/migration")!!
val nextVersion = readAction {
    migDir.children.map { it.name }
        .mapNotNull { Regex("""V(\d+)__""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()?.plus(1) ?: 1
}
println("Existing migrations:")
readAction { migDir.children.map { it.name }.sorted() }.forEach { println("  $it") }
println("Next version: V$nextVersion")
```

### Find Java/Kotlin Files via IDE Index (PREFERRED over shell find)

**Always prefer the IDE index over `ProcessBuilder("find", ...)`.** The IDE index respects source roots, handles not-yet-flushed writes, and stays consistent with PSI. Shell `find` bypasses indexing and may return stale or out-of-scope results.

```kotlin
// PREFERRED: IDE index — respects source roots, project scope
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

val javaFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
}
println("Java files: ${javaFiles.size}")
javaFiles.forEach { vf -> println(vf.path) }

// Same for Kotlin:
val ktFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))
}
ktFiles.forEach { println(it.path) }

// Find a file by EXACT filename (fastest path — O(1) index lookup by name, no iteration)
val byName = readAction {
    FilenameIndex.getVirtualFilesByName("UserServiceImpl.java", GlobalSearchScope.projectScope(project))
}
byName.forEach { println(it.path) }

// Or by name + path substring filter (when multiple files have the same name):
val filtered = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("user", ignoreCase = true) }
}
filtered.forEach { println(it.path) }

// AVOID: ProcessBuilder("find", "/mcp-run-dir/src", "-name", "*.java", "-type", "f")
// ↑ Bypasses IDE indexing — may miss newly-created files or include out-of-scope files
```

### Search for Text Across Project Files (PREFERRED Over shell grep/rg)

**Always prefer the IDE search API over `ProcessBuilder("grep", ...)` or `ProcessBuilder("rg", ...)`.**
Shell grep bypasses the IDE's PSI and may silently fail on regex escaping (`\/` is invalid in ripgrep).
`PsiSearchHelper` uses the same index as "Find in Files" — it's fast and always correct.

```kotlin
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtil

// Option A: Find all files containing a specific word (word-boundary search)
// Use this for plain identifiers, class names, annotation names, etc.
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
readAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("api", scope, { psiFile ->
        // Further filter if needed (e.g., only files that contain "/api/")
        if (psiFile.text.contains("/api/")) matchingFiles.add(psiFile.virtualFile.path)
        true  // return true to continue searching; false to stop early
    }, true)
}
matchingFiles.forEach { println(it) }

// Option B: Content filter over IDE-indexed files (for arbitrary substrings / URLs / paths)
// Faster than shell grep because it operates on the IDE's already-indexed file list
val filesWithPath = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/") }
}
filesWithPath.forEach { println(it.path) }

// Option C: Search in YAML/XML/properties files (no word boundary needed)
val yamlFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "yml", GlobalSearchScope.projectScope(project))
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/") }
}
yamlFiles.forEach { println(it.path + ": " + VfsUtil.loadText(it).lines().filter { l -> l.contains("/api/") }.joinToString("; ")) }
```

### Combine Discovery + Batch Update in ONE Call (Eliminates Two-Step Pattern)

**Anti-pattern to avoid**: listing files first (call 1), then reading + updating each file (call 2 or more).
**Preferred pattern**: find files that match, read content, apply updates — all in a single exec_code call.

This approach eliminates the most common wasteful two-step pattern seen in Spring Boot refactoring tasks
(e.g., "update URL prefix in all controllers"). Instead of `FilenameIndex` → read each → decide → update,
do everything in one shot:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtil

// Single call: find all Java files containing "/api/v1" and replace with "/api/v2"
val scope = GlobalSearchScope.projectScope(project)
val toUpdate = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", scope)
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/v1") }
}
println("Files to update: ${toUpdate.size}")
toUpdate.forEach { vf ->
    val content = VfsUtil.loadText(vf)          // read OUTSIDE writeAction
    val updated = content.replace("/api/v1", "/api/v2")
    check(updated != content) { "Replace matched nothing in ${vf.name}" }
    writeAction { VfsUtil.saveText(vf, updated) } // write INSIDE writeAction
    println("Updated: ${vf.path}")
}
// Flush changes so git/shell tools see them immediately:
LocalFileSystem.getInstance().refresh(false)
println("Done — updated ${toUpdate.size} files")
```

> **Rule**: If you can describe your task as "find files with X, then update them" — do it in **one**
> exec_code call. Discovery + read + update in separate calls wastes ~20s per round-trip and provides
> no benefit since you're working with the same VFS state.

### Diagnosing String Replace Failures

If `check(updated != content)` fires with `"Replace matched nothing"`, the target string has slightly
different whitespace, indentation, or line endings than you expected — or a prior agent already modified
the file. **Always print the exact target region BEFORE attempting the replace:**

```kotlin
val vf = findProjectFile("src/main/java/com/example/ReleaseService.java")!!
val content = VfsUtil.loadText(vf)

// Step 1: Locate the target region and PRINT it before replacing (costs nothing; saves a retry turn):
val keyword = "updateRelease"   // keyword near your target
val idx = content.indexOf(keyword)
if (idx < 0) {
    println("KEYWORD_NOT_FOUND: '$keyword' — file may already be modified, or check the exact method name")
} else {
    println("EXCERPT (chars $idx..${idx + 300}):\n" + content.substring(idx, (idx + 300).coerceAtMost(content.length)))
}

// Step 2: Only AFTER confirming the exact text from the excerpt, perform the replace:
val updated = content.replace("exact old string from excerpt", "new string")
check(updated != content) { "No change — re-read the excerpt above and fix old_string" }
writeAction { VfsUtil.saveText(vf, updated) }
println("Updated: ${vf.name}")
```

**When a prior agent already modified the file**: The expected string may be gone or transformed.
Use `ChangeListManager.allChanges` to detect modified files, then re-read before replacing — do not
rely on a prior turn's view of the file content.

**Alternative when string replace fails repeatedly**: Use PSI surgery to add/modify fields and methods
directly (see "Add a Method to an Existing Java Class via PSI" below). PSI operations are whitespace-
insensitive and survive partial edits made by other agents.

### Batch Project Exploration (One Script Instead of Many Calls)

Explore the full project structure and read multiple relevant files in a single execution — avoid making 5+ separate calls to understand the codebase:

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: Print the full file tree for src/main and src/test
// ⚠️ contentRoots accesses the project model — must be inside readAction { }
val contentRoots = readAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
contentRoots.forEach { root ->
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (!file.isDirectory && (file.extension == "java" || file.extension == "kt" || file.name == "pom.xml")) {
            println(file.path.removePrefix(project.basePath!!))
        }
        true
    }
}
```

```kotlin
// Step 2: Read multiple files in a single script (batch instead of per-file calls)
val filesToRead = listOf(
    "src/main/java/com/example/domain/FeatureService.java",
    "src/main/java/com/example/api/controllers/FeatureController.java",
    "src/main/java/com/example/domain/models/Feature.java",
    "pom.xml"
)
for (path in filesToRead) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    println("\n=== $path ===")
    println(VfsUtil.loadText(vf))
}
```

### Semantic Code Navigation — Extract Structural Info Without Reading Full Files

**Prefer PSI-based structural queries over reading entire file contents.** When you need to know
"what methods does FeatureService have?" or "what fields does CommentDto have?", a single PSI call
answers that question in one round-trip — no need to read 5-6 full files one by one.

```kotlin
// Get all methods and fields of a Java class WITHOUT reading the full file text
val cls = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.sivalabs.ft.features.domain.FeatureService",
        GlobalSearchScope.projectScope(project)
    )
}
if (cls != null) {
    println("=== ${cls.qualifiedName} ===")
    println("Methods:")
    cls.methods.forEach { m ->
        val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
        println("  ${m.name}($params): ${m.returnType?.presentableText}")
    }
    println("Fields:")
    cls.fields.forEach { f -> println("  ${f.name}: ${f.type.presentableText}") }
} else println("Class not found")
```

```kotlin
// Inspect multiple related classes in ONE script to understand codebase structure
val classesToInspect = listOf(
    "com.example.features.domain.FeatureRepository",
    "com.example.features.domain.CommentDto",
    "com.example.features.api.FeatureController"
)
for (fqn in classesToInspect) {
    val c = readAction { JavaPsiFacade.getInstance(project).findClass(fqn, projectScope()) }
    if (c == null) { println("NOT FOUND: $fqn"); continue }
    println("\n=== $fqn ===")
    c.methods.forEach { m -> println("  ${m.name}(${m.parameterList.parameters.size} params)") }
}
```

This replaces 6 separate `VfsUtil.loadText()` calls with 1 PSI-based structural query.

### Verify Project Package Structure Before Creating Files

**CRITICAL**: Always verify the actual package hierarchy via the IDE project model before creating new source files.
Directory names alone are NOT reliable — module source roots may start at a different depth than you expect.
Getting this wrong means your files are in the wrong package (tests pass locally but fail arena validation).

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: List all content source roots (shows exactly where packages start)
// ⚠️ contentSourceRoots accesses the project model — must be inside readAction { }
readAction { ProjectRootManager.getInstance(project).contentSourceRoots.toList() }.forEach { root ->
    println("Source root: ${root.path}")
}

// Step 2: Check if a target package exists in the project model
val pkg = readAction { JavaPsiFacade.getInstance(project).findPackage("com.example.api") }
println("com.example.api exists: ${pkg != null}")

// Step 3: If the package is null, list top-level packages to find the correct root
val rootPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("") }
rootPkg?.subPackages?.forEach { println("top-level package: ${it.qualifiedName}") }

// Step 4: Navigate the package hierarchy to find correct sub-packages
val apiPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("com.example") }
apiPkg?.subPackages?.forEach { println("  sub-package: ${it.qualifiedName}") }
```

This prevents the common error of creating `com.example.microservices.api.Foo` when the project
actually uses `com.example.api.Foo` — a package mismatch that passes internal tests (JSON field matching)
but fails integration validation (class path matching).

**For empty modules with no existing source files** — also infer package convention from sibling modules:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

// When target module has no existing Java files (package can't be inferred locally),
// find existing packages in sibling modules to discover naming convention:
val allJavaFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("/main/java/") }
}
// Extract package names from existing files
val existingPackages = readAction {
    allJavaFiles.mapNotNull { vf ->
        val rel = vf.path.substringAfter("/main/java/")
        rel.substringBeforeLast("/").replace("/", ".")
    }.distinct().sorted()
}
println("Existing packages in sibling modules:")
existingPackages.take(10).forEach { println("  $it") }
// ↑ Use this to derive the correct base package (e.g. "shop.api" not "shop.microservices.api")
```

**⚠️ Pitfall: Gradle `group` ≠ Java package prefix**

The `group` field in `build.gradle` (`group = 'shop.microservices.api'`) is the **Maven artifact group coordinate** — it controls how the JAR is published to a repository. It does NOT determine the Java package hierarchy inside the source files. Projects commonly have:
- Gradle `group = 'shop.microservices.api'` (artifact coordinate)
- Actual Java package = `shop.api` (source code package)

**Always derive the required Java package from test import statements or existing source files — never from the Gradle `group` field.**

```kotlin
// Extract required packages from test imports (ground truth for new files):
import com.intellij.psi.PsiJavaFile
val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ProductServiceApiTests.java",
        GlobalSearchScope.projectScope(project)).firstOrNull()
}
val testImports = testFile?.let { vf -> readAction {
    (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)
        ?.importList?.importStatements?.map { it.qualifiedName ?: "" }
} }
println("Packages to use for new files:\n" + testImports?.joinToString("\n"))
// ↑ e.g. "shop.api.core.product.Product" → create file in `shop/api/core/product/`
//   even if build.gradle says `group = 'shop.microservices.api'`
```

### Check Pending VCS Changes (Prefer Over `git diff` Shell Calls)

**PREFERRED over `ProcessBuilder("git", "diff", "HEAD", "--name-only")`** — avoids blocking the script executor thread and works correctly even inside IDE-managed VFS.

```kotlin
// Check which files have pending (uncommitted) changes
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "Clean slate — no pending changes" else "Modified files:\n" + changes.joinToString("\n"))
```

Use this at the start of arena tasks to detect whether a previous agent slot already modified files — avoids overwriting work done by a parallel agent.

**Multi-agent step 2: after VCS check, verify required classes exist with correct FQN** (changed files ≠ correct fix — a prior agent may have created files in the wrong package):

```kotlin
// After detecting modified files, check that required classes actually resolve
val scope = GlobalSearchScope.projectScope(project)
val required = listOf(
    "shop.api.core.product.Product",
    "shop.api.composite.product.ProductAggregate"
)  // ← replace with your task's required FQNs
val missing = required.filter {
    readAction { JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
}
println(if (missing.isEmpty()) "All required classes present — run tests"
        else "STILL MISSING (must create): " + missing.joinToString(", "))
```

### Read JUnit XML Test Results After ExternalSystemUtil `success=false`

When `ExternalSystemUtil.runTask()` returns `success=false` **do NOT immediately fall back to `ProcessBuilder("./gradlew")`**. Read the JUnit XML results directly from `build/test-results/test/` instead — this gives you structured failure details without spawning a nested Gradle daemon:

```kotlin
import com.intellij.openapi.vfs.VfsUtil

// Adjust path for your module (e.g. "microservices/product-service/build/test-results/test")
val testResultsDir = findProjectFile("build/test-results/test")
if (testResultsDir == null) {
    println("No test-results dir — tests may not have run (compilation error stopped before test phase)")
} else {
    testResultsDir.children.filter { it.name.endsWith(".xml") }.forEach { xmlFile ->
        val content = VfsUtil.loadText(xmlFile)
        val failures = Regex("""<failure[^>]*>(.+?)</failure>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(content).map { it.groupValues[1].take(300) }.toList()
        if (failures.isNotEmpty()) println("FAIL ${xmlFile.name}:\n" + failures.first())
        else println("PASS ${xmlFile.name}")
    }
}
```

> **⚠️ CRITICAL: `BUILD SUCCESSFUL` with ProcessBuilder exit=0 does NOT mean tests ran and passed.**
> Gradle exits 0 when it completes all *requested tasks* without error — but if the test task was
> UP-TO-DATE, or a compilation error stopped execution before the test phase, no tests ran at all.
> The **only** confirmation that tests executed and passed is `Tests run: X, Failures: 0, Errors: 0`
> appearing in the output. Absence of this line means tests did not run — do NOT declare success.

**⚠️ VFS → Git sync lag**: After bulk `writeAction { VfsUtil.saveText(...) }` edits, git-based tools (subprocess `git diff`, `ProcessBuilder("git", ...)`) may see stale content because VFS changes haven't been flushed to disk yet. Always call `LocalFileSystem.getInstance().refresh(false)` (synchronous) after bulk VFS edits, BEFORE running any git subprocess or checking git diff:

```kotlin
// Apply bulk changes
writeAction {
    files.forEach { (vf, content) -> VfsUtil.saveText(vf, content) }
}
// Flush VFS to disk — ensures git diff / shell tools see the updates
LocalFileSystem.getInstance().refresh(false)

// Now git-based checks are accurate:
val result = ProcessBuilder("git", "diff", "--name-only")
    .directory(java.io.File(project.basePath!!)).start()
println(result.inputStream.bufferedReader().readText())
```

### Add a Method to an Existing Java Class via PSI (Safer Than VfsUtil.saveText for Partial Updates)

**`VfsUtil.saveText()` replaces the ENTIRE file** — if you only need to add one method, use PSI surgery instead. This avoids overwriting code you haven't read and reduces the risk of accidentally losing other methods.

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.command.WriteCommandAction

// Find the class to modify
val psiClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "org.springframework.samples.petclinic.service.UserServiceImpl",
        GlobalSearchScope.projectScope(project)
    )
}
if (psiClass != null) {
    val factory = JavaPsiFacade.getElementFactory(project)
    // Build method text using concatenation — avoid 'import ...' at line-start in triple-quoted strings
    val methodText = "private void validatePassword(String password) {\n" +
        "    if (password == null || password.isEmpty()) {\n" +
        "        throw new IllegalArgumentException(\"Password must not be empty\");\n" +
        "    }\n" +
        "}"
    val newMethod = readAction { factory.createMethodFromText(methodText, psiClass) }
    WriteCommandAction.runWriteCommandAction(project) {
        psiClass.add(newMethod)
    }
    println("Method added to ${psiClass.qualifiedName}")
    // Run inspection to verify syntax
    val vf = psiClass.containingFile.virtualFile
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("No compile errors") else problems.forEach { (id, ds) -> ds.forEach { println("[$id] ${it.descriptionTemplate}") } }
} else println("Class not found — check the FQN")
```

### Trigger Maven Re-import After pom.xml Changes

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSyncSpec
import com.intellij.platform.backend.observation.Observation

// After editing pom.xml: schedule sync AND AWAIT it with Observation.awaitConfiguration()
// This is the production-grade API used in Android Studio — avoids 600s modal timeouts.
val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(MavenSyncSpec.incremental("post-pom-edit sync"))
Observation.awaitConfiguration(project)   // suspends until Maven sync + indexing fully complete
println("Maven sync and indexing complete — safe to run tests now")
```

> **`Observation.awaitConfiguration()`** is the canonical way to await any background IDE activity
> (Maven sync, Gradle import, indexing). It is suspend-compatible and handles cancellation.
> This replaces ad-hoc polling loops or `waitForSmartMode()` after build-file changes.

### Read Maven Project Model (Dependencies, Effective POM)

**Prefer over `File(basePath, "pom.xml").readText()`** — respects parent POM inheritance and property interpolation. Useful for checking which version of a library is in use, or whether a dependency is present.

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager

// Query Maven project model (effective POM — includes parent POM inheritance and property resolution)
val mavenManager = MavenProjectsManager.getInstance(project)
val rootProject = mavenManager.rootProjects.firstOrNull() ?: error("No Maven project found")
println("Project: ${rootProject.mavenId.groupId}:${rootProject.mavenId.artifactId}:${rootProject.mavenId.version}")
// List all resolved dependencies (includes dependencies inherited from parent POM):
rootProject.dependencies.forEach { dep ->
    println("  dep: ${dep.groupId}:${dep.artifactId}:${dep.version} scope=${dep.scope}")
}
// Check if a specific dependency exists (e.g. to detect Jakarta vs javax):
val hasLiquibase = rootProject.dependencies.any { it.groupId == "org.liquibase" }
println("Has Liquibase: $hasLiquibase")
```

### IDE-Native Project Build Verification (ProjectTaskManager)

**Preferred over `./mvnw test-compile`** — compiles through IntelliJ's build system, gives structured results, and avoids spawning a child Maven process. Use when you want to verify project-wide compilation without running any tests.

```kotlin
import com.intellij.task.ProjectTaskManager
import com.intellij.openapi.module.ModuleManager
import kotlinx.coroutines.future.await

val modules = ModuleManager.getInstance(project).modules
val result = ProjectTaskManager.getInstance(project).build(modules).await()
println("Build errors: ${result.hasErrors()}")
println("Build aborted: ${result.isAborted}")
// result.hasErrors() == false means project-wide compile passed
```

> **Note**: `ProjectTaskManager.build()` compiles *all* modules. For a quick single-file check, use
> `runInspectionsDirectly(vf)` first (seconds), then fall back to this for cross-file verification.

### Run Tests via IntelliJ IDE Runner ★ PREFERRED ★

> **⚠️ Arena / Docker environment**: Tests that use `@Testcontainers` require Docker-in-Docker to be
> available. Most arena environments support this — **do NOT skip running tests just because you see
> `@Testcontainers`**. Only treat Docker as unavailable if you run a baseline test (a test that existed
> BEFORE your changes) and it fails with `Could not find a valid Docker environment` — then it's an
> infrastructure constraint, not a code defect. Use `runInspectionsDirectly()` as your final check
> in that case and declare your fix complete.

**Always prefer this over `./mvnw test` or `./gradlew test`.** Running tests through the IDE
runner is equivalent to clicking the green ▶ button next to a test class or method. Benefits:

- **No 200k-char truncation problem** — pass/fail from `isPassed()` on the SMTestProxy root
- **Structured results** in the IDE Test Results window — individual failures navigable
- **Works for any build system** — Maven, Gradle, or plain JUnit

> ⚠️ **CRITICAL**: `JUnitConfiguration` (from `com.intellij.execution.junit`) is for **standalone
> JUnit tests** that do NOT need Maven/Gradle. For Maven or Gradle projects use the APIs below —
> otherwise dependencies won't be resolved and the test will fail to compile.

#### Maven projects — `MavenRunConfigurationType.runConfiguration()`

> **⚠️ CRITICAL — After editing pom.xml: do NOT use this IDE runner immediately.**
> When `pom.xml` is modified, IntelliJ triggers a Maven project re-import that shows a
> modal dialog. This dialog **blocks the latch for up to 600 seconds** — wasting an entire
> agent turn. Multiply by 3 agents hitting the same issue = 1800 seconds lost.
>
> **Rule**: After editing `pom.xml`, use `ProcessBuilder("./mvnw", ...)` (see the
> "Run Unit Tests via Maven Wrapper" section below) as your PRIMARY test runner.
> The Maven IDE runner is reliable only when no build file was modified in the current session.
>
> If you do use the Maven IDE runner (e.g. for an unmodified project), always pass
> `dialog_killer: true` on the `steroid_execute_code` call to auto-dismiss any dialogs.
> If the latch still times out after 2 minutes, fall back to `ProcessBuilder("./mvnw", ...)`
> immediately — do not wait the full 10 minutes.

```kotlin
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

// Subscribe BEFORE launching so we don't miss the event
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        val failed = testsRoot.getAllTests().count { it.isDefect }
        println("Tests finished — passed=$passed failures=$failed")
        connection.disconnect()
        result.complete(passed)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    // (SMTRunnerEventsAdapter was removed in IntelliJ 2025.x; missing stubs → compilation failure)
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

// Launch via Maven IDE runner (runs through Maven lifecycle, resolves deps)
MavenRunConfigurationType.runConfiguration(
    project,
    MavenRunnerParameters(
        /* isPomExecution= */ true,
        /* workingDirPath= */ project.basePath!!,
        /* pomFileName= */ "pom.xml",
        /* goals= */ listOf("test", "-Dtest=com.example.MyTest", "-Dspotless.check.skip=true"),
        /* profiles= */ emptyList()
    ),
    /* settings (MavenGeneralSettings) = */ null,
    /* runnerSettings (MavenRunnerSettings) = */ null,
) { /* ProgramRunner.Callback — completion handled by SMTRunnerEventsListener above */ }

val passed = withTimeout(5.minutes) { result.await() }
println("Result: passed=$passed")
```

> **⚠️ Docker / CI environments — use `dialog_killer: true`**: When running `MavenRunConfigurationType.runConfiguration()` in a Docker or CI container, Maven project-reimport dialogs can block the run silently for the full latch timeout (5 minutes wasted). Pass `dialog_killer: true` as the `steroid_execute_code` parameter to auto-dismiss these modals. If the latch still times out after 2-3 minutes despite `dialog_killer: true`, **stop waiting and fall back to `ProcessBuilder("./mvnw", ...)` immediately** — do not wait the full 5 minutes.

#### Gradle projects — `GradleRunConfiguration.setRunAsTest(true)`

```kotlin
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.runners.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        println("Tests finished — passed=$passed")
        connection.disconnect()
        result.complete(passed)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

val configurationType = GradleExternalTaskConfigurationType.getInstance()
val factory = configurationType.configurationFactories[0]
val config = GradleRunConfiguration(project, factory, "Run MyTest")
config.settings.externalProjectPath = project.basePath!!
config.settings.taskNames = listOf(":test")
config.settings.scriptParameters = "--tests \"com.example.MyTest\""
config.setRunAsTest(true)  // CRITICAL: enables test console / SMTestProxy wiring

val runManager = RunManager.getInstance(project)
val settings = runManager.createConfiguration(config, factory)
runManager.addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())

val passed = withTimeout(5.minutes) { result.await() }
println("Result: passed=$passed")
```

#### Auto-detect runner via ConfigurationContext (simplest, works for any build system)

This is exactly what the green ▶ gutter button does:

```kotlin
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.SimpleDataContext
import com.intellij.openapi.actionSystem.CommonDataKeys

val psiClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.MyTest", projectScope())
} ?: error("Class not found")

val settings = readAction {
    ConfigurationContext.getFromContext(
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_ELEMENT, psiClass)
            .build()
    ).configuration
} ?: error("No run configuration produced for this class")

// Auto-selects Maven/Gradle/JUnit runner based on project structure
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test started — check IDE Test Results window")
```

---

### Run Unit Tests via Maven Wrapper (PRIMARY after pom.xml edits; fallback otherwise)

**Use `./mvnw` as your PRIMARY test runner whenever you have edited `pom.xml`** in the current session — the Maven IDE runner triggers a re-import modal that blocks for up to 600 seconds after build file changes. For sessions where `pom.xml` was NOT modified, prefer the IDE runner above (avoids 200k-char output truncation).

> **⚠️ CRITICAL — Output Truncation Required**: Spring Boot integration test output routinely exceeds
> **200k characters** (Spring context startup ~100 lines, Flyway migration logs, Testcontainers Docker
> pull logs, full stack traces). Printing the full output causes MCP token limit errors.
> **Always use `takeLast()` to read only the relevant tail**:

```kotlin
// ⚠️ FALLBACK ONLY — use the JUnit or Maven IDE runner below when possible
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed in arena environments
// ⚠️ NEVER print process.inputStream.bufferedReader().readText() — Spring Boot output can be 200k+ chars
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | total output lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / Testcontainers errors appear at the START of output;
// Maven BUILD FAILURE summary appears at the END. Using takeLast alone loses early failures.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

> **⚠️ Run FAIL_TO_PASS tests one at a time** — not all at once. Running multiple Spring Boot tests in
> one Maven call multiplies startup log output (4 tests × 25k chars each = 100k+ chars), causing MCP
> token overflow errors that require multi-step Bash parsing to recover from. Always run individually:
> `-Dtest=SingleTestClass` not `-Dtest=Test1,Test2,Test3,Test4`.

> **⚠️ When take/takeLast is not enough** (output still exceeds limit after first+last 30 lines):
> Use keyword filtering to extract only signal lines from verbose Spring Boot / Testcontainers output:

```kotlin
// Keyword-filtered Maven output — use when verbose Spring Boot output exceeds MCP token limit
// even after take(30)+takeLast(30). Prevents multi-step Bash parsing recovery (saves 3-5 turns).
val process = ProcessBuilder("./mvnw", "test", "-Dtest=OnlyOneTestClass", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val completed = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
val keywords = listOf("Tests run:", "FAILED", "ERROR", "Caused by:", "BUILD", "Could not", "Exception in")
println("Exit: ${if (completed) process.exitValue() else "TIMEOUT"} | total lines: ${lines.size}")
println("--- First 20 lines (Spring startup errors) ---")
lines.take(20).forEach(::println)
println("--- Signal lines only ---")
lines.filter { l -> keywords.any { k -> k in l } }.take(50).forEach(::println)
println("--- Last 15 lines (Maven BUILD FAILURE) ---")
lines.takeLast(15).forEach(::println)
```

Similarly for `test-compile` (project-wide dependency check, faster than full test run):
```kotlin
val process = ProcessBuilder("./mvnw", "test-compile", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Compile exit: $exitCode | lines: ${lines.size}")
// Compile errors may appear anywhere — capture both ends for full context
println(lines.take(20).joinToString("\n"))
println("---")
println(lines.takeLast(20).joinToString("\n"))
```

> **⚠️ Deprecation warnings are non-fatal**: Output like `warning: 'getVirtualFilesByName(String, GlobalSearchScope)' is deprecated` does not indicate failure — the script ran successfully. Do NOT retry just because of deprecation warnings; only retry on actual `ERROR` responses.

### Run Gradle Tests via ExternalSystemUtil ★ PREFERRED for Gradle ★

> **⚠️ Anti-pattern**: Never use `ProcessBuilder("./gradlew", ...)` **inside** `steroid_execute_code`.
> This spawns a nested Gradle daemon from within the IDE JVM, causing classpath conflicts and
> resource exhaustion. Use the IntelliJ ExternalSystem API below instead. If the IDE runner is
> unavailable, fall back to the Bash tool (outside exec_code) — NOT ProcessBuilder inside exec_code.

```kotlin
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

val settings = ExternalSystemTaskExecutionSettings().apply {
    externalProjectPath = project.basePath!!
    taskNames = listOf(":api:test", "--tests", "com.example.api.ProductControllerTest")
    externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
    vmOptions = "-Xmx512m"
}

ExternalSystemUtil.runTask(
    settings,
    com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project,
    GradleConstants.SYSTEM_ID,
    object : TaskCallback {
        override fun onSuccess() { result.complete(true) }
        override fun onFailure() { result.complete(false) }
    },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    false
)
val gradleSuccess = withTimeout(5.minutes) { result.await() }
println("Gradle result: success=$gradleSuccess")
```

> If the IDE runner is not available or times out, use the Bash tool **outside exec_code**:
> `./gradlew :api:test --tests "com.example.api.ProductControllerTest" --no-daemon -q`
> Do NOT use ProcessBuilder inside exec_code for this.

### Run Gradle Tests via ProcessBuilder (fallback — use Bash tool instead when possible)

> **⚠️ FALLBACK ONLY**: Prefer the ExternalSystemUtil approach above. If you must use ProcessBuilder
> (e.g. when IDE runner hangs), note that this spawns a child Gradle process inside the IDE JVM.
> When possible, use the Bash tool outside exec_code instead (avoids classpath conflicts).

For **Gradle** projects, use `./gradlew` with `--tests` for targeted test class execution.

> **⚠️ CRITICAL — Output Truncation Required**: Same as Maven — Gradle integration test output can be 200k+ chars. **Always use `takeLast()` and `take()` to capture both ends.**

```kotlin
// Run a specific test class in a specific Gradle submodule
val proc = ProcessBuilder("./gradlew", ":product-service:test",
    "--tests", "com.example.product.ProductServiceTest",
    "--no-daemon", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exit = proc.waitFor()
println("Exit: $exit | total lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / startup errors appear at the START;
// Gradle BUILD FAILED summary with test counts appears at the END.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Gradle BUILD FAILED summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

```kotlin
// Run ALL tests in a module (when no specific class is needed):
val proc = ProcessBuilder("./gradlew", ":product-service:test", "--no-daemon", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exit = proc.waitFor()
println("Exit: $exit | lines: ${lines.size}")
println(lines.take(30).joinToString("\n"))
println("---")
println(lines.takeLast(30).joinToString("\n"))
```

**Gradle vs Maven cheat sheet:**

| Action | Maven | Gradle |
|--------|-------|--------|
| Run one test class | `-Dtest=SimpleClassName` | `--tests "com.example.FullyQualifiedClassName"` |
| Run one test method | `-Dtest=ClassName#method` | `--tests "com.example.ClassName.method"` |
| Target a module | `-pl product-service` | `:product-service:test` |
| Skip spotless | `-Dspotless.check.skip=true` | (not needed usually) |
| No daemon | n/a | `--no-daemon` |
| Quiet output | `-q` | `-q` |

### Environment Diagnostics (Docker / System — Consolidated)

**Consolidate all Docker and system environment checks into ONE `steroid_execute_code` call** instead of multiple Bash tool calls (each Bash call costs ~20s overhead). This single call replaces 8+ separate Bash commands (`docker info`, `ls /var/run/docker.sock`, `find / -name docker*`, `env | grep DOCKER`, `env | grep TESTCONTAINER`, `ps aux | grep docker`, etc.):

```kotlin
// ONE call replaces 8 separate Bash diagnostics — saves ~160s round-trip overhead
val dockerEnv = System.getenv().filter { (k, _) ->
    k.contains("DOCKER", ignoreCase = true) || k.contains("TESTCONTAINERS", ignoreCase = true)
}
println("Docker/TC env vars: $dockerEnv")
println("docker.sock exists: ${java.io.File("/var/run/docker.sock").exists()}")
val dockerBin = try {
    ProcessBuilder("which", "docker").start().inputStream.bufferedReader().readText().trim()
} catch (e: Exception) { "not found: $e" }
println("docker binary: $dockerBin")
println("dockerd exists: ${java.io.File("/usr/bin/dockerd").exists() || java.io.File("/usr/local/bin/dockerd").exists()}")
println("podman exists: ${java.io.File("/usr/bin/podman").exists()}")
println("PATH: ${System.getenv("PATH")}")
```

> **Key principle**: If you need 3+ diagnostic shell commands, collapse them into ONE `steroid_execute_code` call. The JVM inside IntelliJ has unrestricted filesystem and process access — identical to what Bash can do, but without the per-call overhead.

> **⚡ Proactive Docker pre-check — TRIGGER: run on your VERY FIRST `steroid_execute_code` call when**
> **FAIL_TO_PASS tests use `@Testcontainers` or extend `AbstractIT` / `IntegrationTest` / `AbstractITBase`.**
> Do NOT wait for test failures to discover Docker unavailability — that wastes 8+ turns (~3 min).
> Combine with your IDE readiness probe so it costs zero extra turns:

```kotlin
// STEP ZERO: combine IDE probe + Docker check in one call (before any implementation)
println("Project: ${project.name} @ ${project.basePath}")
println("Smart mode: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
println("Docker available: $dockerOk")
// Decision based on result:
// dockerOk=true  → proceed normally; run @Testcontainers tests as final verification
// dockerOk=false → use runInspectionsDirectly for compile verification only; DO NOT run integration tests
//                  report ARENA_FIX_APPLIED: yes once inspections pass; do NOT investigate Docker further
```

> **When to stop investigating Docker failures**: If `./mvnw test` fails with `Could not find a valid Docker environment` AND an existing test (pre-patch) fails with the same error, the environment lacks Docker. This is an **infrastructure constraint, not a code defect**. Do NOT investigate further — use `runInspectionsDirectly` as your final verification and declare your fix complete.

### Run a Specific JUnit Test Class via IntelliJ Runner (non-Maven/Gradle only)

> ⚠️ **Only use `JUnitConfiguration` for projects that do NOT use Maven or Gradle** (e.g. pure
> IntelliJ module projects). For Maven/Gradle projects use `MavenRunConfigurationType` or
> `GradleRunConfiguration` from the ★ PREFERRED ★ section above.

```kotlin
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor

val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
val config = factory.createConfiguration("Run test", project) as JUnitConfiguration
val data = config.persistentData               // typed as JUnitConfiguration.Data
data.TEST_CLASS = "com.example.MyValidatorTest"
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← must use constant, NOT string "class"
config.setWorkingDirectory(project.basePath!!)
val settings = RunManager.getInstance(project).createConfiguration(config, factory)
RunManager.getInstance(project).addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test run started")
// ⚠️ Pitfall: writing data.TEST_OBJECT = "class" → compile error "unresolved reference 'TEST_CLASS'"
// Always use the constant: JUnitConfiguration.TEST_CLASS
```

### Get Per-Test Breakdown via SMTestProxy

`SMTRunnerEventsListener.TEST_STATUS` works for all runners (Maven, Gradle, JUnit). Subscribe
before launching the test. Use `testsRoot.isPassed()` for overall pass/fail:

```kotlin
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Unit>()

// Subscribe BEFORE launching (don't miss the event)
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        val hasErrors = testsRoot.hasErrors()
        val allTests = testsRoot.getAllTests()
        val failCount = allTests.count { it.isDefect }
        println("Done — passed=$passed errors=$hasErrors failures=$failCount")
        allTests.filter { it.isDefect }.forEach { println("  FAILED: ${it.name}") }
        connection.disconnect()
        result.complete(Unit)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

// Then launch via MavenRunConfigurationType or GradleRunConfiguration (see ★ PREFERRED ★ above)
// ... launch code here ...

withTimeout(5.minutes) { result.await() }
```

### Check Compile Errors Without Running Full Build

**Always run this BEFORE `./mvnw test`** — it catches errors in seconds, not minutes. If this reports errors, fix them before running the Maven test command.

> **⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only analyzes the single
> file you pass. It does NOT catch compile errors in OTHER files that reference your changed signatures.
> After modifying a widely-used class (DTO, command, entity, record), also check the key dependent files
> (service, controller, mapper, test), or run `./mvnw compile -q` (with takeLast() truncation) for
> project-wide verification.
>
> **Staged verification recipe (Maven projects)**:
> 1. `runInspectionsDirectly(vf)` for each changed file — catches single-file syntax/import errors (~5s each)
> 2. `./mvnw compile -q` — catches cross-file type errors, missing methods, broken call sites (~30-60s)
> 3. `./mvnw test -Dtest=TargetTest` — only after steps 1+2 pass (runs Docker-dependent tests)
>
> Do NOT skip step 2 and jump directly to step 3 — a compile error in a dependent file will fail the test
> with a confusing runtime stacktrace rather than a clean compile error message.

> **⚠️ Spring bonus — also catches bean conflicts**: `runInspectionsDirectly` detects Spring Framework
> issues beyond compile errors: duplicate `@Bean` method definitions in `@Configuration` classes (causes
> `NoUniqueBeanDefinitionException` at startup), missing `@Component` / `@Service` annotations, and
> unresolved `@Autowired` dependencies. Run it on `@Configuration` classes **BEFORE** `./mvnw test`
> to catch Spring startup failures in ~5s instead of waiting for a 90s Maven cold-start.

```kotlin
// Faster than 'mvn test' — returns IDE inspection results in seconds
// Run this after creating/modifying files, BEFORE running ./mvnw test
val vf = findProjectFile("src/main/java/com/example/Product.java")!!
val problems = runInspectionsDirectly(vf)
if (problems.isEmpty()) {
    println("No problems found — safe to run tests")
} else {
    problems.forEach { (id, descs) ->
        descs.forEach { println("[$id] ${it.descriptionTemplate}") }
    }
    println("Fix the above errors before running tests")
}
// Also check key dependent files to catch cross-file breakage:
for (depPath in listOf(
    "src/main/java/com/example/service/ProductService.java",
    "src/main/java/com/example/api/ProductController.java"
)) {
    val depVf = findProjectFile(depPath) ?: continue
    val depProblems = runInspectionsDirectly(depVf)
    if (depProblems.isNotEmpty()) {
        println("Problems in $depPath:")
        depProblems.forEach { (id, descs) -> descs.forEach { println("  [$id] ${it.descriptionTemplate}") } }
    }
}
```

### Inspection Result: ClassCanBeRecord → Always Convert for New DTO Classes

> **When creating new DTO, data, or value classes** and `runInspectionsDirectly` reports
> `[ClassCanBeRecord]`, **always convert the class to a Java `record`**. This is not an optional
> style suggestion for new code — the inspection is telling you the class *should be* a record.
> The reference solution typically uses Java records for DTOs; failing to convert causes a structural
> mismatch with expected behavior.
>
> **Do NOT ignore `ClassCanBeRecord` on newly-created DTO/data classes.** Treat it as a required
> action, not informational noise.

```kotlin
// WRONG: create as traditional class and ignore ClassCanBeRecord warning
// public class ProductAggregate { private String name; ... }

// CORRECT: create as Java record from the start
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/api")
    val f = dir.findChild("ProductAggregate.java") ?: dir.createChildData(this, "ProductAggregate.java")
    VfsUtil.saveText(f, listOf(
        "package com.example.api;",
        "",
        "public record ProductAggregate(String name, int weight) {}"
    ).joinToString("\n"))
}
// After writing, run runInspectionsDirectly to confirm ClassCanBeRecord is gone
```

### Inspection Result: ClassEscapesItsScope for Spring Beans → Expected, Non-Blocking

> **`[ClassEscapesItsScope]`** appears on Spring `@Service`, `@Repository`, and `@Component` beans that expose package-private types through public methods (e.g. a `public` method returning a package-private domain object). This is **expected in Spring Boot projects** and non-blocking — it does not prevent compilation or deployment.
>
> **Before spending a turn trying to fix it**: Check whether the same warning appears on existing (pre-patch) services or repositories in the project. If `FeatureService`, `FavoriteFeatureService`, or other existing beans have the same warning, your new `@Service` will too — it is a deliberate design pattern in the codebase. Do NOT refactor to fix it; simply note it and move on.

### Inspection Result: ConstantValue "Value is always null" on DTO Accessor → CRITICAL Bug

> **`[ConstantValue] Value ... is always 'null'`** on a DTO method call (e.g. `dto.releasedAt()`, `dto.status()`, `dto.version()`) in a test file is a **critical data-flow finding, NOT a style warning**. IntelliJ's type system has proven the accessor always returns `null` — which happens when the **DTO record is missing that component field** (the accessor method does not exist or returns null unconditionally).
>
> **Do NOT dismiss `ConstantValue` on DTO/record accessor calls as "pre-existing static analysis notes" or "noise".** It is a guaranteed runtime `NullPointerException` or assertion failure at test execution time.

**Severity classification — prevents misclassification:**

| Inspection ID | Severity | Action |
|---------------|----------|--------|
| `ConstantValue` ("always null/true/false") | **CRITICAL** — runtime failure guaranteed | Investigate immediately |
| `AssertBetweenInconvertibleTypes` | **CRITICAL** — assertion always passes/fails | Investigate |
| `ClassCanBeRecord` | **REQUIRED** — structural mismatch | Convert to record |
| `ClassEscapesItsScope` on Spring beans | **EXPECTED** — ignore | Skip |
| `DeprecatedIsStillUsed` | **LOW** — cosmetic | Fix if time allows |
| `GrazieInspectionRunner` | **COSMETIC** — grammar | Ignore |

**Diagnosis recipe** — run when you see `[ConstantValue] Value ... is always 'null'` on a DTO accessor:

```kotlin
// Step 1: Read the DTO record source to see its actual component list
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val dtoFile = readAction {
    FilenameIndex.getVirtualFilesByName("ReleaseDto.java", GlobalSearchScope.projectScope(project)).firstOrNull()
}
if (dtoFile != null) {
    println("=== DTO source ===")
    println(VfsUtil.loadText(dtoFile))
}

// Step 2: Cross-reference with what the test calls on the DTO
val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ReleaseControllerTests.java", GlobalSearchScope.projectScope(project)).firstOrNull()
}
if (testFile != null) {
    val text = VfsUtil.loadText(testFile)
    // Extract .methodName() calls that look like DTO accessors (lower-camel, not assertion calls):
    val dtoCalls = Regex("\\.([a-z][a-zA-Z0-9]+)\\(\\)")
        .findAll(text)
        .map { it.groupValues[1] }
        .filter { it !in setOf("body", "isEqualTo", "isNotNull", "statusCode", "then", "when", "get", "size", "isEmpty") }
        .toSet()
    println("DTO methods called in tests: $dtoCalls")
}
// Compare output: methods in dtoCalls absent from DTO record components = missing fields to add
```

> **Fix**: Add the missing components to the DTO `record` definition. For example, if `ReleaseDto` is
> `public record ReleaseDto(String name, String version)` and the test calls `dto.releasedAt()`, add
> `Instant releasedAt` to the record — and update the mapper/service/query that constructs the DTO.

---

## Refactoring Operations

### Rename Element

**CAUTION: This modifies code!**

```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiNamedElement

// First find the element to rename in a read action
val element = readAction {
    // ... find your PsiElement
}

if (element is PsiNamedElement) {
    WriteCommandAction.runWriteCommandAction(project) {
        element.setName("newName")
    }
    println("Renamed to: newName")
}
```

### Safe Refactoring with RefactoringFactory

```kotlin
import com.intellij.refactoring.RefactoringFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val psiClass = readAction {
    JavaPsiFacade.getInstance(project)
        .findClass("com.example.OldName", GlobalSearchScope.projectScope(project))
}

if (psiClass != null) {
    val factory = RefactoringFactory.getInstance(project)
    val rename = factory.createRename(psiClass, "NewName")
    rename.run()
    println("Refactoring completed")
}
```

---

## Code Completion and Introspection

### Using PsiReference.getVariants() (Simplest)

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.lookup.LookupElement

val filePath = "/path/to/YourFile.kt"
val offset = 150  // Position where you want completions

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

val completions = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf)
    if (psiFile == null) return@readAction emptyArray<Any>()

    val element = psiFile.findElementAt(offset)
    val reference = element?.reference

    reference?.getVariants() ?: emptyArray()
}

println("Found ${completions.size} completions:")
completions.forEach { variant ->
    when (variant) {
        is LookupElement -> println("  - ${variant.lookupString}")
        is String -> println("  - $variant")
        else -> println("  - ${variant.javaClass.simpleName}: $variant")
    }
}
```

### Introspect a Class - Get All Methods and Fields

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val className = "com.intellij.openapi.project.Project"

readAction {
    val psiClass = JavaPsiFacade.getInstance(project)
        .findClass(className, GlobalSearchScope.allScope(project))

    if (psiClass == null) {
        println("Class not found: $className")
        return@readAction
    }

    println("=== Class: ${psiClass.qualifiedName} ===\n")

    // Get all methods (including inherited)
    val methods = psiClass.allMethods
    println("Methods (${methods.size}):")
    methods
        .filter { !it.isConstructor }
        .sortedBy { it.name }
        .take(30)
        .forEach { method ->
            val params = method.parameterList.parameters
                .joinToString(", ") { "${it.name}: ${it.type.presentableText}" }
            val returnType = method.returnType?.presentableText ?: "void"
            println("  ${method.name}($params): $returnType")
        }

    // Get all fields
    val fields = psiClass.allFields
    println("\nFields (${fields.size}):")
    fields.sortedBy { it.name }.forEach { field ->
        println("  ${field.name}: ${field.type.presentableText}")
    }
}
```

### Resolve Reference - Find What a Symbol Points To

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiVariable

val filePath = "/path/to/YourFile.kt"
val offset = 150  // Put caret on a symbol you want to resolve

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf)
    val element = psiFile?.findElementAt(offset)
    val reference = element?.parent?.reference ?: element?.reference

    if (reference == null) {
        println("No reference at offset $offset")
        return@readAction
    }

    println("Reference text: ${reference.element.text}")

    val resolved = reference.resolve()
    if (resolved == null) {
        println("Could not resolve reference")
        return@readAction
    }

    println("Resolved to: ${resolved.javaClass.simpleName}")

    when (resolved) {
        is PsiMethod -> {
            println("Method: ${resolved.name}")
            println("Containing class: ${resolved.containingClass?.qualifiedName}")
            println("Return type: ${resolved.returnType?.presentableText}")
            println("Parameters:")
            resolved.parameterList.parameters.forEach { param ->
                println("  ${param.name}: ${param.type.presentableText}")
            }
        }
        is PsiField -> {
            println("Field: ${resolved.name}")
            println("Type: ${resolved.type.presentableText}")
            println("Containing class: ${resolved.containingClass?.qualifiedName}")
        }
        is PsiClass -> {
            println("Class: ${resolved.qualifiedName}")
            println("Is interface: ${resolved.isInterface}")
        }
        is PsiVariable -> {
            println("Variable: ${resolved.name}")
            println("Type: ${resolved.type.presentableText}")
        }
        else -> {
            println("Resolved element: ${resolved.text?.take(100)}")
        }
    }
}
```

---

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

### 5. Use Meaningful task_id

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

---

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

**End of Guide**

For more examples, see the MCP resources:
- `mcp-steroid://lsp/overview` - LSP-like examples
- `mcp-steroid://ide/overview` - IDE power operations
- `mcp-steroid://debugger/overview` - Debugger examples

**IntelliJ Platform SDK**: https://plugins.jetbrains.com/docs/intellij/
**Source Code**: https://github.com/JetBrains/intellij-community
