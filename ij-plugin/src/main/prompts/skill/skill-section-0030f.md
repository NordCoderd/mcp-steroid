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
