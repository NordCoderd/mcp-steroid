---
name: intellij-mcp-steroid
description: Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to IntelliJ Platform APIs. Use when you need to search code, run refactorings, access PSI (parsed code model), query project structure, run inspections, or perform any IDE operation. Prefer this over file-based operations - the IDE has indexed everything.
license: Apache-2.0
compatibility: Requires IntelliJ IDEA 2025.2+ with the MCP Steroid plugin installed
metadata:
  author: jonnyzzz
  version: "1.0"
  category: development
---

# IntelliJ MCP Steroid - IDE API Access for AI Agents

Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to the IntelliJ Platform API.

## Quickstart Flow

```
1. steroid_list_projects → get list of open projects
2. Pick a project_name from the list
3. steroid_execute_code → run Kotlin code with that project
4. steroid_execute_feedback → report success/failure for tracking
```

**Example session:**
```
→ steroid_list_projects
← {"projects":[{"name":"my-app","path":"/path/to/my-app"}]}

→ steroid_execute_code(project_name="my-app", code="execute { println(project.name) }", ...)
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
List all open projects. Returns project names for use with `steroid_execute_code`.

### `steroid_execute_code`
Execute Kotlin code in IntelliJ's runtime.

**Parameters:**
- `project_name` (required): Target project from `steroid_list_projects`
- `code` (required): Kotlin code with `execute { }` block
- `reason` (required): Human-readable explanation
- `task_id` (required): Group related executions
- `timeout` (optional): Timeout in seconds (default: 60)

### `steroid_execute_feedback`
Rate execution results. Use after `steroid_execute_code`.

## Critical Rules

### 1. Execute Block is a SUSPEND Function
```kotlin
execute {
    // This is a coroutine - use suspend APIs!
    waitForSmartMode()  // suspend function - works directly
    delay(1000)         // coroutine delay - works directly
}
```
**NEVER use `runBlocking`** - it causes deadlocks.

### 2. IMPORTS Must Be OUTSIDE Execute Block

**WRONG:**
```kotlin
execute {
    import com.intellij.psi.PsiManager  // ERROR!
}
```

**CORRECT:**
```kotlin
import com.intellij.psi.PsiManager
import com.intellij.openapi.application.readAction

execute {
    val file = readAction { PsiManager.getInstance(project).findFile(vf) }
}
```

### 3. Read/Write Actions for PSI/VFS

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction

execute {
    // Reading PSI/VFS/indices
    val data = readAction {
        PsiManager.getInstance(project).findFile(virtualFile)
    }

    // Modifying PSI/VFS/documents
    writeAction {
        document.setText("new content")
    }
}
```

## Script Template

```kotlin
// 1. IMPORTS - Always outside execute block
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiManager

// 2. EXECUTE BLOCK
execute {
    // 3. Wait for indexing if needed
    waitForSmartMode()

    // 4. Use IntelliJ APIs
    val result = readAction {
        // PSI/VFS operations here
    }

    // 5. Output results
    println(result)
}
```

## Context Available in Execute Block

```kotlin
execute {
    // Properties
    project      // IntelliJ Project instance
    params       // Tool parameters (JsonElement)
    disposable   // For resource cleanup
    isDisposed   // Check if disposed

    // Output methods
    println("Hello", 42, "world")      // Space-separated output
    printJson(object { val x = 1 })    // Pretty JSON
    printException("Failed", exception) // Error with stack trace (recommended for errors)
    progress("Step 1 of 3...")         // Progress (throttled 1/sec)

    // IDE utilities
    waitForSmartMode()  // Wait for indexing
}
```

## Common Patterns

### Get Project Info
```kotlin
execute {
    println("Project: ${project.name}")
    println("Base path: ${project.basePath}")
}
```

### Get IDE Log Path
```kotlin
execute {
    val logPath = com.intellij.openapi.application.PathManager.getLogPath()
    println("Log: $logPath/idea.log")
}
```

### List Plugins
```kotlin
execute {
    com.intellij.ide.plugins.PluginManagerCore.getLoadedPlugins()
        .filter { it.isEnabled }
        .forEach { println("${it.name}: ${it.version}") }
}
```

### Find Plugin by ID
```kotlin
execute {
    val plugin = com.intellij.ide.plugins.PluginManagerCore.loadedPlugins
        .find { it.pluginId.idString == "org.jetbrains.kotlin" }
    println("Kotlin plugin: ${plugin?.version}")
}
```

### List Extension Points
```kotlin
execute {
    project.extensionArea.extensionPoints
        .filter { it.name.contains("kotlin", ignoreCase = true) }
        .forEach { println("${it.name}: ${it.extensionList.size} extensions") }
}
```

### Navigate Project Files
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

execute {
    ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
        println("Root: ${root.path}")
        VfsUtil.iterateChildrenRecursively(root, null) { file ->
            if (file.extension == "kt") println("  ${file.path}")
            true
        }
    }
}
```

### Open Another Project (CAUTION: opens new window)
```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

execute {
    val path = Path.of("/path/to/project")
    ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
}
```

### Restart IDE (CAUTION: destructive operation)
```kotlin
execute {
    com.intellij.openapi.application.ApplicationManager.getApplication().restart()
}
```

## Complete End-to-End PSI Example

This example finds a Kotlin class, gets its methods, and prints their signatures:

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

execute {
    waitForSmartMode()

    readAction {
        // Find all classes named "MyService" in the project
        val scope = GlobalSearchScope.projectScope(project)
        val classes = KotlinClassShortNameIndex.get("MyService", project, scope)

        if (classes.isEmpty()) {
            println("No class named 'MyService' found")
            return@readAction
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
}
```

## Find Usages (Complete Example)

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

execute {
    waitForSmartMode()

    readAction {
        // First find the class
        val scope = GlobalSearchScope.projectScope(project)
        val classes = KotlinClassShortNameIndex.get("MyService", project, scope)
        val targetClass = classes.firstOrNull()

        if (targetClass == null) {
            println("Class not found")
            return@readAction
        }

        // Find all usages using findAll() (returns a Collection)
        val usages = ReferencesSearch.search(targetClass, scope).findAll()

        println("Found ${usages.size} usages of ${targetClass.name}:")
        usages.forEach { ref ->
            val file = ref.element.containingFile.virtualFile.path
            val offset = ref.element.textOffset
            println("  $file:$offset")
        }
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

execute {
    waitForSmartMode()

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
}
```

### Find Elements by Type

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClass

execute {
    waitForSmartMode()

    readAction {
        val psiFile = // ... get file

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
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

execute {
    waitForSmartMode()

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
}
```

### Find Class Hierarchy

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

execute {
    waitForSmartMode()

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
}
```

---

## Indexing and Search

### Search Files by Name

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

execute {
    waitForSmartMode()

    readAction {
        // Find files by exact name
        val files = FilenameIndex.getFilesByName(
            project,
            "build.gradle.kts",
            GlobalSearchScope.projectScope(project)
        )

        files.forEach { file ->
            println("Found: ${file.virtualFile.path}")
        }
    }
}
```

### Search Files by Extension

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.KotlinFileType

execute {
    waitForSmartMode()

    readAction {
        // Find all Kotlin files
        val kotlinFiles = FileTypeIndex.getFiles(
            KotlinFileType.INSTANCE,
            GlobalSearchScope.projectScope(project)
        )

        println("Found ${kotlinFiles.size} Kotlin files")
        kotlinFiles.take(20).forEach { vf ->
            println("  ${vf.path}")
        }
    }
}
```

### Find Methods by Name (Stub Index)

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.PsiMethod

execute {
    waitForSmartMode()

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
}
```

---

## Document and Editor Operations

### Read Document Content

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

execute {
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
}
```

### Modify Document (CAUTION: modifies file)

```kotlin
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

execute {
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
}
```

### Access Current Editor

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager

execute {
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
}
```

---

## VFS (Virtual File System) Operations

### Read File Content

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets

execute {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.txt")

    if (vf != null && !vf.isDirectory) {
        val content = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
        println("File content (${content.length} chars):")
        println(content.take(500))
    }
}
```

### List Directory Contents

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

execute {
    val dir = LocalFileSystem.getInstance().findFileByPath("/path/to/directory")

    if (dir != null && dir.isDirectory) {
        dir.children.forEach { child ->
            val type = if (child.isDirectory) "DIR" else "FILE"
            println("[$type] ${child.name}")
        }
    }
}
```

### Create File (CAUTION: modifies filesystem)

```kotlin
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.vfs.LocalFileSystem

execute {
    writeAction {
        val parentDir = LocalFileSystem.getInstance().findFileByPath("/path/to/dir")
        if (parentDir != null) {
            val newFile = parentDir.createChildData(this, "newfile.txt")
            newFile.setBinaryContent("Hello, World!".toByteArray())
            println("Created: ${newFile.path}")
        }
    }
}
```

---

## Refactoring Operations

### Rename Element (CAUTION: modifies code)

```kotlin
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiNamedElement

execute {
    waitForSmartMode()

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
}
```

### Safe Refactoring with RefactoringFactory

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.refactoring.RefactoringFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

execute {
    waitForSmartMode()

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

execute {
    waitForSmartMode()

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
}
```

### Get Errors and Warnings

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

execute {
    waitForSmartMode()

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
}
```

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

execute {
    waitForSmartMode()

    val filePath = "/path/to/YourFile.kt"
    val offset = 150  // Position where you want completions

    val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
    if (vf == null) {
        println("File not found")
        return@execute
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
}
```

### Get Completions at Current Editor Caret

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.lookup.LookupElement

execute {
    waitForSmartMode()

    val editor = FileEditorManager.getInstance(project).selectedTextEditor
    if (editor == null) {
        println("No editor open")
        return@execute
    }

    val offset = editor.caretModel.offset
    val virtualFile = editor.virtualFile

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

execute {
    waitForSmartMode()

    val filePath = "/path/to/YourFile.kt"
    val offset = 150

    val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
    if (vf == null) {
        println("File not found")
        return@execute
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
}
```

### Introspect a Class - Get All Methods and Fields

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField

execute {
    waitForSmartMode()

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
}
```

### Introspect an Interface - Discover Available APIs

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

execute {
    waitForSmartMode()

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
}
```

### Get Type Information at a Position

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass

execute {
    waitForSmartMode()

    val filePath = "/path/to/YourFile.java"
    val offset = 200

    val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
    if (vf == null) {
        println("File not found")
        return@execute
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
}
```

### Resolve Reference - Find What a Symbol Points To

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiVariable

execute {
    waitForSmartMode()

    val filePath = "/path/to/YourFile.kt"
    val offset = 150  // Put caret on a symbol you want to resolve

    val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
    if (vf == null) {
        println("File not found")
        return@execute
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
}
```

---

## Services and Components

### Access Project Services

```kotlin
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager

execute {
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
}
```

### Check File Type in Project

```kotlin
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem

execute {
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
}
```

---

## Search Scopes

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.module.ModuleManager

execute {
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
    val vf = // ... get virtual file
    val fileScope = GlobalSearchScope.fileScope(project, vf)

    // Multiple files scope
    val filesScope = GlobalSearchScope.filesScope(project, listOf(vf1, vf2))
}
```

## Actions

### Find Action by ID
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

execute {
    val action = ActionManager.getInstance().getAction("GotoFile")
    println("Action: ${action?.templatePresentation?.text}")
}
```

### List All Actions
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

execute {
    ActionManager.getInstance().getActionIdList("")
        .filter { it.contains("Goto", ignoreCase = true) }
        .take(20)
        .forEach { println(it) }
}
```

## Reflection for API Discovery

```kotlin
execute {
    try {
        val clazz = Class.forName("com.intellij.openapi.project.Project")
        clazz.methods
            .filter { it.parameterCount == 0 }
            .take(20)
            .forEach { println("${it.name}(): ${it.returnType.simpleName}") }
    } catch (e: ClassNotFoundException) {
        println("Class not found")
    }
}
```

## Error Handling

Use `printException` for errors - it includes the stack trace in the output:

```kotlin
execute {
    try {
        // risky operation
    } catch (e: Exception) {
        printException("Operation failed", e)  // Recommended - includes stack trace in output
    }
}
```

## Best Practices

1. **Call `waitForSmartMode()` before PSI operations** - indexing must complete first
2. **Use `readAction { }` for all PSI/VFS reads** - even simple property access
3. **Use `writeAction { }` for all PSI/VFS modifications** - always
4. **Keep imports outside `execute { }`** - imports inside won't compile
5. **Prefer Kotlin coroutine APIs** - you're in a suspend context
6. **Use meaningful `task_id`** - groups related executions
7. **Report progress** - `progress("Step 1 of 5...")`
8. **Use `printException` for errors** - better than printStackTrace
9. **Use reflection for discovery** - explore unknown APIs
10. **Prefer IntelliJ APIs over file operations** - IDE has indexed everything

## Troubleshooting

### Check if Server is Running
The MCP server runs inside IntelliJ. To verify:
1. Open IntelliJ IDEA with the MCP Steroid plugin installed
2. Open any project
3. Check `.idea/mcp-steroids.txt` in the project folder for the server URL
4. The server binds to port 63150 by default (configurable via `mcp.steroids.server.port` registry key)

### Endpoints
- `/` - Returns this SKILL.md content
- `/skill.md` - Same as above
- `/mcp` - MCP protocol endpoint for tool calls
- `/.well-known/mcp.json` - MCP server discovery

### Common Issues
- **"Project not found"** - Run `steroid_list_projects` first to get exact project names
- **No output from execute** - Make sure to call `println()` or `printJson()` to see results
- **Timeout** - Increase `timeout` parameter (default 60 seconds)
- **Script errors** - Check imports are outside `execute { }` block

## Summary

The MCP Steroid server gives you **direct access to IntelliJ's runtime**:

- Query the project model
- Invoke any IntelliJ API
- Run refactorings
- Execute actions
- Inspect plugins
- Access PSI (parsed code)

**The execute block is a suspend function.** Use coroutine APIs directly.

**Don't settle for file-level operations when you have IDE-level access.**
