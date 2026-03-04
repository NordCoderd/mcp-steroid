IntelliJ API Power User Guide

RECOMMENDED: Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to IntelliJ Platform APIs.


# MCP Steroid - IDE API Access for AI Agents

Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to the IntelliJ Platform API.

## Important Notes for AI Agents

**Learning Curve**: Writing working code for IntelliJ APIs may require several attempts. This is normal! The API is vast and powerful. Keep trying - each attempt teaches you more about the available APIs. Use `printException()` to see stack traces when errors occur.

**Persistence Pays Off**: If your first attempt fails, analyze the error, adjust your approach, and try again. The power you gain from direct IDE access is worth the effort.

**Comparison to LSP**: This MCP server provides functionality similar to LSP (Language Server Protocol) tools, but uses IntelliJ's native APIs instead. IntelliJ APIs are often more powerful and feature-rich than standard LSP, offering:
- Deeper code understanding via PSI (Program Structure Interface)
- Access to IDE-specific features (inspections, refactorings, intentions)
- Full project model with module dependencies
- Platform-specific indices for fast code search

## Quickstart Flow

```
1. steroid_list_projects → get list of open projects
2. Pick a project_name from the list
3. steroid_capabilities → list installed plugins and languages (optional)
4. steroid_execute_code → run Kotlin code with that project
5. steroid_execute_feedback → report success/failure for tracking
```

**Example session:**
```
→ steroid_list_projects
← {"ide":{"name":"IntelliJ IDEA","version":"2025.3.2","build":"IU-253.30387.160"},"projects":[{"name":"my-app","path":"/path/to/my-app"}]}

→ steroid_execute_code(project_name="my-app", code="println(project.name)", ...)
← "my-app"

→ steroid_execute_feedback(project_name="my-app", task_id="...", execution_id="...", success_rating=1.0, explanation="Got project name")
```

## When to Use This Skill

**ALWAYS prefer IntelliJ APIs over file-based operations:**

| Instead of...                   | Use IntelliJ API               |
|---------------------------------|--------------------------------|
| Reading files with `cat`/`read` | VFS and PSI APIs               |
| Searching with `grep`/`find`    | Find Usages, Structural Search |
| Manual text replacement         | Automated refactorings         |
| Guessing code structure         | Query project model directly   |

The IDE has indexed everything. It knows the code better than any file search.

## Available Tools

### `steroid_list_projects`
List all open projects. Returns IDE metadata and project names for use with `steroid_execute_code`.

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the correct `project_name` and `window_id` for screenshot/input tools.

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the correct `project_name` and `window_id` for screenshot/input tools.

### `steroid_capabilities`
List IDE capabilities such as installed plugins and registered languages.

**Parameters:**
- `include_disabled_plugins` (optional): Include disabled plugins in the response (default: false)

### `steroid_action_discovery`
Discover available editor actions, quick-fixes, and gutter actions for a file and caret context.

**Parameters:**
- `project_name` (required): Target project name
- `file_path` (required): Absolute or project-relative path to the file
- `caret_offset` (optional): Caret offset within the file (default: 0)
- `action_groups` (optional): Action group IDs to expand (default: editor popup + gutter)
- `max_actions_per_group` (optional): Cap actions returned per group (default: 200)

### `steroid_take_screenshot`
Capture a screenshot of the IDE frame and return image content.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): Target project name
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the screenshot is needed
- `window_id` (optional): Window id from `steroid_list_windows` to target a specific window

**Artifacts (saved under the execution folder):**
- `screenshot.png`
- `screenshot-tree.md`
- `screenshot-meta.json`

Use the returned `execution_id` as `screenshot_execution_id` for `steroid_input`. The response includes `window_id` (also stored in `screenshot-meta.json`).

### `steroid_input`
Send input events (keyboard + mouse) using a sequence string.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): Target project name
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the input is needed
- `screenshot_execution_id` (required): Execution ID from `steroid_take_screenshot` or `takeIdeScreenshot()`
- `sequence` (required): Comma-separated or newline-separated input sequence (commas inside values are allowed unless they look like `, <step>:`; commas are optional when using newlines)

**Sequence examples:**
- `stick:ALT, delay:400, press:F4, type:hurra`
- `click:CTRL+Left@120,200`
- `click:Right@screen:400,300`

**Notes:**
- Comma separators are detected by `, <step>:` patterns, so avoid typing `, delay:` etc in text.
- Trailing commas before a newline are ignored.
- Use `#` for comments until the end of the line.
- Targets default to screenshot coordinates; use `screen:` for absolute screen pixels.
- Input focuses the screenshot window before dispatching events.
- Input focuses the screenshot window before dispatching events.

### `steroid_execute_code`
**Execute code with IntelliJ's brain, not just text files.**

Give your AI agent a senior developer's toolkit: semantic code understanding, automated refactorings, and IDE intelligence that LSP can't provide.

**Why use this over file operations:**
- **See relationships**, not just text: Find all usages instantly, traverse class hierarchies, query the semantic model
- **Refactor correctly**: Rename functions project-wide, extract methods that maintain types, move classes that update imports
- **Catch errors early**: Run the same inspections IntelliJ runs, see type mismatches, detect code smells
- **Understand structure**: Access project model, module dependencies, source roots - the IDE has indexed everything

**Performance note**: Operations complete in sub-second time for typical projects. Large codebases may take longer.

**Quick example:**
```kotlin[IU]
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// Find a class and all its usages - indexed, accurate, fast
smartReadAction {
    val classes = KotlinClassShortNameIndex.get("UserService", project, projectScope())
    val usages = ReferencesSearch.search(classes.first(), projectScope()).findAll()
    println("Found ${usages.size} usages")
}
```

**Parameters:** `project_name`, `code` (Kotlin suspend function body), `task_id`, `reason`, `timeout` (optional)

**Returns:** Execution output with `execution_id` for feedback

**📚 Complete guide:** `mcp-steroid://skill/coding-with-intellij` (API reference, patterns, examples, best practices)

### `steroid_execute_feedback`
Rate execution results. Use after `steroid_execute_code`.

### `steroid_open_project`
Open a project in the IDE. This tool initiates the project opening process and returns quickly.

**IMPORTANT**: This tool does NOT wait for the project to fully open. The project opening process may show dialogs (such as "Trust Project", project type selection, etc.) that require interaction. Use `steroid_take_screenshot` and `steroid_input` tools to interact with any dialogs that appear.

**Parameters:**
- `project_path` (required): Absolute path to the project directory to open
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project path before opening (skips trust dialog). Default: false
- `force_new_frame` (optional): If true, always open in a new window. Default: false

**Workflow:**
1. Call `steroid_open_project` with the project path
2. If `trust_project=true`, the project will be trusted automatically (no trust dialog)
3. Call `steroid_take_screenshot` to see the current IDE state
4. If dialogs are shown, use `steroid_input` to interact with them
5. Call `steroid_list_projects` to verify the project is open

## MCP Resources (Use Them)

This server exposes built-in resources through the MCP resource APIs. These are the fastest way to load full examples and guides without guessing or copy/pasting from the web.

**How to access resources:**
1. Call `list_mcp_resources` to discover available resources.
2. Call `read_mcp_resource` with the resource URI to load the content.

**Key resources provided by this server:**
- `mcp-steroid://prompt/skill` - This guide as a resource.
- `mcp-steroid://skill/coding-with-intellij` - Comprehensive guide for writing IntelliJ API code (execution model, patterns, examples).
- `mcp-steroid://prompt/debugger-skill` - Debugger-focused skill guide (breakpoints, sessions, threads).
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
import com.intellij.openapi.vfs.LocalFileSystem

val virtualFile = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

**WRONG:**
```kotlin
// ERROR example — imports must be at the top, not after code:
//
//   val filePath = "src/Main.kt"
//   import com.intellij.psi.PsiManager  // ERROR: imports must be at the top!
//
// The script engine extracts imports from the top of the script.
// Placing them after code causes compilation errors.
```

**CORRECT:**
```kotlin
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

// Use built-in readAction helper - no import needed!
val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val file = readAction { PsiManager.getInstance(project).findFile(vf) }
```

### 3. Read/Write Actions for PSI/VFS

> **⚠️ THREADING RULE — NEVER SKIP**: Any PSI access (JavaPsiFacade, PsiShortNamesCache, PsiManager.findFile, module roots, annotations, etc.) **MUST** be inside `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately — they are NOT silently ignored. This is the most common cause of first-attempt runtime errors.

**Built-in helpers (no imports needed):**
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

// Reading PSI/VFS/indices - use built-in readAction
val virtualFile = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}

// Modifying PSI/VFS/documents
val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
writeAction {
    document.setText("new content")
}
```

**With explicit imports (same functionality):**
```kotlin
val data = readAction { project.name }
writeAction { project.name }
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
```kotlin[IU]
import com.intellij.psi.JavaPsiFacade

// Use smartReadAction - combines wait + read
val psiClass = smartReadAction {
    JavaPsiFacade.getInstance(project).findClass("MyClass", allScope())
}
println(psiClass?.qualifiedName)
```

## Context Available in the Script Body

```kotlin
import com.intellij.openapi.Disposable

// === Properties ===
val p: Project = project              // IntelliJ Project instance
val d: Disposable = disposable        // For resource cleanup
val disposed: Boolean = isDisposed    // Check if disposed

// === Output Methods ===
println("Hello", 42, "world")       // Space-separated output
printJson(mapOf("key" to "value"))   // Pretty JSON
progress("Step 1 of 3...")          // Progress (throttled 1/sec)

// === Read/Write Actions (NO IMPORTS NEEDED!) ===
val data = readAction { project.name }           // Suspend read action
writeAction { project.name }                     // Suspend write action
val smart = smartReadAction { project.name }     // Auto-waits for indexing

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
val file = findProjectFile("src/main/App.kt")
if (file != null) {
    val problems = runInspectionsDirectly(file)
    println("Problems: ${problems.size}")
}
```

## Running Tests

**Always prefer the IntelliJ IDE runner over `./mvnw test` or `./gradlew test`.**
The IDE runner is the equivalent of clicking the green ▶ button next to a test class. It:
- Returns a simple exit code (0 = all passed) — no 200k output to parse
- Shows structured per-test results in the IDE Test Results window
- Reuses the running JVM — faster than spawning a new Maven/Gradle process

See `mcp-steroid://skill/coding-with-intellij` → **"Run Tests via IntelliJ IDE Runner ★ PREFERRED ★"**
for the complete pattern (MavenRunConfigurationType / GradleRunConfiguration + CompletableDeferred).

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
val logPath = com.intellij.openapi.application.PathManager.getSystemPath() + "/log"
println("Log: $logPath/idea.log")
```
### List Plugins
```kotlin
com.intellij.ide.plugins.PluginManagerCore.getPluginSet().enabledPlugins
    .forEach { println("${it.name}: ${it.version}") }
```
### Find Plugin by ID
```kotlin
val plugin = com.intellij.ide.plugins.PluginManagerCore.getPluginSet().enabledPlugins
    .find { it.pluginId.idString == "org.jetbrains.kotlin" }
println("Kotlin plugin: ${plugin?.version}")
```
### List Extension Points

> **Note:** `extensionArea.extensionPoints` was removed in IntelliJ 2025.3+. Use `Language.getRegisteredLanguages()` or specific `ExtensionPointName` instances instead.
```kotlin
import com.intellij.lang.Language

// In IntelliJ 2025.3+, extensionArea.extensionPoints was removed.
// Use Language.getRegisteredLanguages() or specific ExtensionPointName instances instead.
Language.getRegisteredLanguages()
    .filter { it.displayName.contains("kotlin", ignoreCase = true) }
    .forEach { println("Language: ${it.displayName} (${it.id})") }
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
import com.intellij.openapi.actionSystem.ActionManager
val restartAction = ActionManager.getInstance().getAction("RestartIde")
println("Restart action available: ${restartAction != null}")
// To actually restart: invoke the action via ActionUtil.performAction()
```
## Complete End-to-End PSI Example

This example finds a Kotlin class, gets its methods, and prints their signatures:
```kotlin[IU]
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
## Find Usages (Complete Example)
```kotlin[IU]
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
---

## Advanced PSI Operations

### PSI Tree Navigation
```kotlin
import com.intellij.openapi.application.readAction
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

```kotlin[IU]
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClass
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")
    val psiFile = vf?.let { PsiManager.getInstance(project).findFile(it) }

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
```kotlin[IU]
import com.intellij.openapi.application.readAction
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
```kotlin[IU]
import com.intellij.openapi.application.readAction
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
## Indexing and Search

### Search Files by Name
```kotlin
import com.intellij.psi.search.FilenameIndex

// smartReadAction = waitForSmartMode() + readAction
smartReadAction {
    // Find files by exact name using built-in projectScope()
    val files = FilenameIndex.getVirtualFilesByName("build.gradle.kts", projectScope())

    files.forEach { vf ->
        println("Found: ${vf.path}")
    }
}
```
### Search Files by Extension
```kotlin[IU]
import com.intellij.psi.search.FileTypeIndex
import org.jetbrains.kotlin.idea.KotlinFileType

smartReadAction {
    // Find all Kotlin files using built-in projectScope()
    val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, projectScope())

    println("Found ${kotlinFiles.size} Kotlin files")
    kotlinFiles.take(20).forEach { vf ->
        println("  ${vf.path}")
    }
}
```
### Find Methods by Name (Stub Index)
```kotlin[IU]
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.PsiMethod


readAction {
    val methods = StubIndex.getElements(
        JavaMethodNameIndex.getInstance().key,
        "toString",
        project,
        GlobalSearchScope.projectScope(project),
        PsiMethod::class.java
    )

    println("Found ${methods.size} methods named 'toString'")
    methods.take(10).forEach { method ->
        println("  ${method.containingClass?.qualifiedName}.${method.name}")
    }
}
```
---

## Document and Editor Operations

### Read Document Content
```kotlin
import com.intellij.openapi.application.readAction
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
### Modify Document (CAUTION: modifies file)
```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
val document = FileDocumentManager.getInstance().getDocument(vf!!)

if (document != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        // Insert at position (replaceString with equal start/end offsets = insert)
        document.replaceString(0, 0, "// Added comment\n")

        // Or replace text
        // document.replaceString(startOffset, endOffset, "new text")
    }
    println("Document modified")
}
```
### Access Current Editor
```kotlin
import com.intellij.openapi.application.readAction
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

## VFS (Virtual File System) Operations

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
### Refresh a Specific File (Optional)

Use this only when you know a file changed outside the IDE or VFS looks stale. Prefer refreshing the exact file or directory you need.
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
### Create File (CAUTION: modifies filesystem)
```kotlin
import com.intellij.openapi.application.writeAction
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
---
## Refactoring Operations

### Rename Element (CAUTION: modifies code)

```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

// First find the element to rename in a read action
val element = readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)
    psiFile?.findElementAt(100)?.parent  // find PsiElement at offset
}

if (element is PsiNamedElement) {
    WriteCommandAction.runWriteCommandAction(project) {
        element.setName("newName")
    }
    println("Renamed to: newName")
}
```
### Safe Refactoring with RefactoringFactory
```kotlin[IU]
import com.intellij.openapi.application.readAction
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

## Code Analysis

### Run Inspection on File
```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem


readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)

    if (psiFile != null) {
        val inspectionManager = InspectionManager.getInstance(project)
        // Note: Getting specific inspections requires more setup
        // This shows the basic pattern
        println("File analyzed: ${psiFile.name}")
    }
}
```
### Get Errors and Warnings

**NOTE:** The daemon code analyzer may return stale results if the IDE window is not focused
(see [GitHub issue #20](https://github.com/jonnyzzz/mcp-steroid/issues/20)).
For reliable results, use `runInspectionsDirectly()` instead.
```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

// NOTE: DaemonCodeAnalyzerEx.getFileLevelHighlights() was removed in IntelliJ 2025.3+
// Use runInspectionsDirectly() instead for reliable inspection results (see below).
readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt") ?: return@readAction
    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction

    val analyzer = DaemonCodeAnalyzer.getInstance(project)
    println("File: ${psiFile.name}")
    println("Use runInspectionsDirectly(vf) for reliable inspection results")
}
```
### Run Inspections Directly (Recommended for MCP)

Use `runInspectionsDirectly()` for reliable inspection results regardless of IDE window focus.
This bypasses the daemon's caching and runs inspections directly on the file.
```kotlin
val file = requireNotNull(findProjectFile("src/main/kotlin/MyClass.kt")) { "File not found" }

// Run inspections directly - works even when IDE is not focused
val problems = runInspectionsDirectly(file)

if (problems.isEmpty()) {
    println("No problems found!")
} else {
    problems.forEach { (inspectionId, descriptors) ->
        descriptors.forEach { problem ->
            val element = problem.psiElement
            val line = if (element != null) {
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
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

---
## Code Completion and Introspection

Use the code completion API to discover available methods, fields, and variables at a specific location in the code.

### Approach 1: Using PsiReference.getVariants() (Simplest)

The simplest way to get completion suggestions at a position:
```kotlin
import com.intellij.openapi.application.readAction
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
### Get Completions at Current Editor Caret
```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.lookup.LookupElement


val editor = FileEditorManager.getInstance(project).selectedTextEditor
if (editor == null) {
    println("No editor open")
    return
}

val offset = editor.caretModel.offset
val virtualFile = editor.virtualFile ?: run {
    println("No virtual file for editor")
    return
}

val completions = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    if (psiFile == null) return@readAction emptyArray<Any>()

    val element = psiFile.findElementAt(offset)
    val reference = element?.reference

    reference?.getVariants() ?: emptyArray()
}

println("Completions at offset $offset:")
completions.take(20).forEach { variant ->
    when (variant) {
        is LookupElement -> {
            val psi = variant.psiElement
            val type = psi?.javaClass?.simpleName ?: "unknown"
            println("  - ${variant.lookupString} ($type)")
        }
        else -> println("  - $variant")
    }
}
if (completions.size > 20) {
    println("  ... and ${completions.size - 20} more")
}
```
### Approach 2: Using PsiScopeProcessor (Discover Visible Symbols)

Find all symbols visible at a specific location (variables, methods, classes):
```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.ResolveState


val filePath = "/path/to/YourFile.kt"
val offset = 150

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

val symbols = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction emptyList<Pair<String, String>>()
    val context = psiFile.findElementAt(offset) ?: return@readAction emptyList<Pair<String, String>>()

    val declarations = mutableListOf<Pair<String, String>>()

    val processor = object : PsiScopeProcessor {
        override fun execute(element: PsiElement, state: ResolveState): Boolean {
            if (element is PsiNamedElement) {
                val name = element.name ?: return true
                val kind = element.javaClass.simpleName
                declarations.add(name to kind)
            }
            return true  // Continue processing
        }
    }

    context.processDeclarations(processor, ResolveState.initial(), null, context)
    declarations
}

println("Visible symbols at offset $offset:")
symbols.groupBy { it.second }.forEach { (kind, items) ->
    println("\n$kind:")
    items.take(10).forEach { (name, _) ->
        println("  - $name")
    }
    if (items.size > 10) {
        println("  ... and ${items.size - 10} more")
    }
}
```
### Introspect a Class - Get All Methods and Fields
```kotlin[IU]
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField


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
### Introspect an Interface - Discover Available APIs
```kotlin[IU]
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope


// Discover methods available on common interfaces
val interfaces = listOf(
    "com.intellij.openapi.project.Project",
    "com.intellij.psi.PsiFile",
    "com.intellij.psi.PsiElement",
    "com.intellij.openapi.editor.Editor"
)

readAction {
    interfaces.forEach { className ->
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project))

        if (psiClass == null) {
            println("Not found: $className")
            return@forEach
        }

        val simpleName = className.substringAfterLast('.')
        println("\n=== $simpleName ===")

        // Show key methods (non-deprecated, public)
        psiClass.methods
            .filter { !it.isDeprecated && it.hasModifierProperty("public") }
            .sortedBy { it.name }
            .take(15)
            .forEach { method ->
                val params = method.parameterList.parameters.size
                val returnType = method.returnType?.presentableText ?: "void"
                println("  ${method.name}($params params): $returnType")
            }
    }
}
```
### Get Type Information at a Position
```kotlin[IU]
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass


val filePath = "/path/to/YourFile.java"
val offset = 200

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf)
    if (psiFile == null) {
        println("Cannot parse file")
        return@readAction
    }

    val element = psiFile.findElementAt(offset)
    println("Element at offset $offset: ${element?.text}")
    println("Element type: ${element?.javaClass?.simpleName}")

    // Find containing expression
    val expr = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java)
    if (expr != null) {
        println("Expression: ${expr.text}")
        println("Expression type: ${expr.type?.presentableText}")
    }

    // Find containing variable declaration
    val variable = PsiTreeUtil.getParentOfType(element, PsiVariable::class.java)
    if (variable != null) {
        println("Variable: ${variable.name}")
        println("Variable type: ${variable.type.presentableText}")
    }

    // Find containing method
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    if (method != null) {
        println("In method: ${method.name}")
        println("Return type: ${method.returnType?.presentableText}")
    }

    // Find containing class
    val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
    if (psiClass != null) {
        println("In class: ${psiClass.qualifiedName}")
    }
}
```
### Resolve Reference - Find What a Symbol Points To
```kotlin[IU]
import com.intellij.openapi.application.readAction
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

## Search Scopes

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.module.ModuleManager

// Project scope (all project files)
val projectScope = GlobalSearchScope.projectScope(project)

// All scope (project + libraries)
val allScope = GlobalSearchScope.allScope(project)

// Module scope
val module = ModuleManager.getInstance(project).modules.firstOrNull()
if (module != null) {
    val moduleScope = GlobalSearchScope.moduleScope(module)
    println("Module scope: ${module.name}")
}

// File scope
val vf = findProjectFile("src/main/kotlin/MyClass.kt")!!
val fileScope = GlobalSearchScope.fileScope(project, vf)

// Multiple files scope
val vf2 = findProjectFile("src/main/kotlin/Other.kt")!!
val filesScope = GlobalSearchScope.filesScope(project, listOf(vf, vf2))
```

## Actions

### Find Action by ID
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val action = ActionManager.getInstance().getAction("GotoFile")
println("Action: ${action?.templatePresentation?.text}")
```
### List All Actions
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

ActionManager.getInstance().getActionIdList("")
    .filter { it.contains("Goto", ignoreCase = true) }
    .take(20)
    .forEach { println(it) }
```
## Reflection for API Discovery

```kotlin
try {
    val clazz = Class.forName("com.intellij.openapi.project.Project")
    clazz.methods
        .filter { it.parameterCount == 0 }
        .take(20)
        .forEach { println("${it.name}(): ${it.returnType.simpleName}") }
} catch (e: ClassNotFoundException) {
    println("Class not found")
}
```

## Error Handling

Use `printException` for errors - it includes the stack trace in the output:

```kotlin
try {
    // risky operation
} catch (e: Exception) {
    printException("Operation failed", e)  // Recommended - includes stack trace in output
}
```

## Best Practices

1. **Use `smartReadAction { }` for PSI operations** - combines wait + read in one call
2. **Use built-in helpers** - `readAction`, `writeAction`, `projectScope()`, `allScope()` need no imports
3. **Use file helpers** - `findPsiFile()`, `findProjectFile()` simplify file access
4. **Imports are optional** - add top-level imports only when needed
5. **Prefer Kotlin coroutine APIs** - you're in a suspend context
6. **Use meaningful `task_id`** - groups related executions
7. **Report progress** - `progress("Step 1 of 5...")`
8. **Use `printException` for errors** - includes stack trace in output
9. **Keep trying** - IntelliJ API has a learning curve, persistence pays off
10. **Prefer IntelliJ APIs over file operations** - IDE has indexed everything

## Troubleshooting

### Check if Server is Running
The MCP server runs inside IntelliJ. To verify:
1. Open IntelliJ IDEA with the MCP Steroid plugin installed
2. Open any project
3. Check `.idea/mcp-steroid.md` in the project folder for the server URL
4. The server port is configurable via `mcp.steroid.server.port`; read `.idea/mcp-steroid.md` for the active URL

### Endpoints
- `/` - Returns this SKILL.md content
- `/skill.md` - Same as above
- `/mcp` - MCP protocol endpoint for tool calls
- `/.well-known/mcp.json` - MCP server discovery

### MCP Resources (Preferred)
Use MCP `resources/list` and `resources/read` instead of HTTP fetching when possible.

Recommended resources:
- Use `resources/list` to discover all available resources
- `mcp-steroid://prompt/skill` - Full SKILL.md content as a resource
- `mcp-steroid://prompt/debugger-skill` - Debugger workflow guide + stateful steroid_execute_code notes
- `mcp-steroid://ide/overview` - IDE usage overview and patterns
- `mcp-steroid://lsp/overview` - LSP-style workflows and examples
- `mcp-steroid://debugger/overview` - Debugger workflows and runnable examples

### Common Issues
- **"Project not found"** - Run `steroid_list_projects` first to get exact project names
- **No output from execute** - Make sure to call `println()` or `printJson()` to see results
- **Timeout** - Increase `timeout` parameter (default 60 seconds)
- **Script errors** - Check Kotlin syntax; imports are optional

## Related Resources

📊 Use `resources/list` to discover all available resources and their navigation paths

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - This guide
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows and stateful execution
- [Test Runner Guide](mcp-steroid://prompt/test-skill) - Test execution patterns

### Example Resources
- [LSP Examples](mcp-steroid://lsp/overview) - LSP-like operations (navigation, code intelligence, refactoring)
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations (refactorings, inspections, generation)
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugger workflows and API usage
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations (git blame, history)
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows

### Specific Skill Resources
- [Debugger Guide](mcp-steroid://prompt/debugger-skill) - Setting breakpoints and debugging
- [Test Runner Guide](mcp-steroid://prompt/test-skill) - Running and inspecting tests

---

## Summary

The MCP Steroid server gives you **direct access to IntelliJ's runtime**:

- Query the project model
- Invoke any IntelliJ API
- Run refactorings
- Execute actions
- Inspect plugins
- Access PSI (parsed code)

**Key points:**
- The script body is a suspend function - use coroutine APIs directly
- Built-in helpers (`readAction`, `writeAction`, `projectScope()`, etc.) require no imports
- Use `smartReadAction { }` for most PSI operations
- Use `findPsiFile()` and `findProjectFile()` for easy file access

**This is like LSP, but more powerful.** IntelliJ APIs offer deeper code understanding and more features than standard LSP.

**Don't settle for file-level operations when you have IDE-level access.**

**Keep trying!** The API is vast - errors on first attempts are normal. Each attempt teaches you more.
