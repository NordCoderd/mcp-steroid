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

