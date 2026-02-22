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

**⚠️ ALL VFS mutation ops need writeAction**: `createDirectoryIfMissing()`, `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require writeAction. Put the ENTIRE create-directory-and-write sequence inside a SINGLE writeAction block:

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

> **Step 0 — Do This FIRST Before Creating Any Migration File**
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

### Batch Project Exploration (One Script Instead of Many Calls)

Explore the full project structure and read multiple relevant files in a single execution — avoid making 5+ separate calls to understand the codebase:

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: Print the full file tree for src/main and src/test
ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
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
ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
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

// After editing pom.xml, sync Maven to download new dependencies
MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()
println("Maven sync triggered — wait for background indexing before running tests")
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

**Always prefer this over `./mvnw test` or `./gradlew test`.** Running tests through the IDE
runner is equivalent to clicking the green ▶ button next to a test class or method. Benefits:

- **No 200k-char truncation problem** — pass/fail from `isPassed()` on the SMTestProxy root
- **Structured results** in the IDE Test Results window — individual failures navigable
- **Works for any build system** — Maven, Gradle, or plain JUnit

> ⚠️ **CRITICAL**: `JUnitConfiguration` (from `com.intellij.execution.junit`) is for **standalone
> JUnit tests** that do NOT need Maven/Gradle. For Maven or Gradle projects use the APIs below —
> otherwise dependencies won't be resolved and the test will fail to compile.

#### Maven projects — `MavenRunConfigurationType.runConfiguration()`

```kotlin
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val latch = CountDownLatch(1)
var passed = false

// Subscribe BEFORE launching so we don't miss the event
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        passed = testsRoot.isPassed()
        val failed = testsRoot.getAllTests().count { it.isDefect }
        println("Tests finished — passed=$passed failures=$failed")
        connection.disconnect()
        latch.countDown()
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
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
    /* executor= */ null,
    /* pomFile= */ null,
) { /* descriptor callback — optional, latch above handles completion */ }

latch.await(10, TimeUnit.MINUTES)
println("Result: passed=$passed")
```

#### Gradle projects — `GradleRunConfiguration.setRunAsTest(true)`

```kotlin
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.runners.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val latch = CountDownLatch(1)
var passed = false

val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        passed = testsRoot.isPassed()
        println("Tests finished — passed=$passed")
        connection.disconnect()
        latch.countDown()
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
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

latch.await(10, TimeUnit.MINUTES)
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

### Run Unit Tests via Maven Wrapper (FALLBACK ONLY)

**Use `./mvnw` only when the IDE runner cannot be used** (e.g. the test requires a full Maven lifecycle or specific Maven plugins). The IDE runner above is always preferred.

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

### Run Gradle Tests via ProcessBuilder (for Gradle / multi-module projects)

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val latch = CountDownLatch(1)

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
        latch.countDown()
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
})

// Then launch via MavenRunConfigurationType or GradleRunConfiguration (see ★ PREFERRED ★ above)
// ... launch code here ...

latch.await(10, TimeUnit.MINUTES)
```

### Check Compile Errors Without Running Full Build

**Always run this BEFORE `./mvnw test`** — it catches errors in seconds, not minutes. If this reports errors, fix them before running the Maven test command.

> **⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only analyzes the single
> file you pass. It does NOT catch compile errors in OTHER files that reference your changed signatures.
> After modifying a widely-used class (DTO, command, entity, record), also check the key dependent files
> (service, controller, mapper, test), or run `./mvnw test-compile -q` (with takeLast() truncation) for
> project-wide verification.

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

// Project root manager
val rootManager = ProjectRootManager.getInstance(project)
println("SDK: ${rootManager.projectSdk?.name}")

// Module manager
val moduleManager = ModuleManager.getInstance(project)
moduleManager.modules.forEach { module ->
    println("Module: ${module.name}")
}

// Content roots
rootManager.contentRoots.forEach { root ->
    println("Content root: ${root.path}")
}

// Source roots
rootManager.contentSourceRoots.forEach { src ->
    println("Source root: ${src.path}")
}
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
