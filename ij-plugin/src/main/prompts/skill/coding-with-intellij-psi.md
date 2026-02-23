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

