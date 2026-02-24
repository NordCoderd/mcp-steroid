## Refactoring Operations

### Rename Element (CAUTION: modifies code)

```kotlin
import com.intellij.openapi.application.writeAction
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
(see [GitHub issue #20](https://github.com/jonnyzzz/intellij-mcp-steroids/issues/20)).
For reliable results, use `runInspectionsDirectly()` instead.

```kotlin
import com.intellij.openapi.application.readAction
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
