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
