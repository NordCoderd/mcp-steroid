## Complete End-to-End PSI Example

This example finds a Kotlin class, gets its methods, and prints their signatures:

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

## Find Usages (Complete Example)

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

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClass


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
```

### Java PSI - Find Class by FQN

```kotlin
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

```kotlin
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

---
