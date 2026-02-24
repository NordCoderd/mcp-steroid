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

```kotlin
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

```kotlin
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

```kotlin
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

```kotlin
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
