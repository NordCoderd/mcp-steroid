IDE: Inline Method
[IU]
This example inlines a method body at call sites,

```kotlin
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.inline.InlineMethodProcessor

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val line = 10     // TODO: 1-based line number
val column = 15   // TODO: 1-based column number
val inlineThisOnly = false
val deleteDeclaration = true
val dryRun = true


val (psiFile, document) = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null to null
    val psi = PsiManager.getInstance(project).findFile(virtualFile)
    val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
    psi to doc
}

if (psiFile == null || document == null) {
    println("File not found or no document: $filePath")
    return
}

val offset = document.getLineStartOffset(line - 1) + (column - 1)

val element = readAction { psiFile.findElementAt(offset) }
if (element == null) {
    println("No element found at $line:$column")
    return
}

val (targetMethod, reference, methodName) = readAction {
    val ref = element.reference
    val method = (ref?.resolve() as? PsiMethod)
        ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    Triple(method, ref, method?.name ?: "")
}

if (targetMethod == null) {
    println("No method found at $line:$column")
    return
}

if (dryRun) {
    println("Inline method prepared: $methodName")
    println("inlineThisOnly=$inlineThisOnly deleteDeclaration=$deleteDeclaration")
    println("Set dryRun=false to apply changes.")
    return
}

val editor = withContext(Dispatchers.EDT) {
    EditorFactory.getInstance().createEditor(document, project)
}
try {
    val processor = readAction {
        InlineMethodProcessor(
            project,
            targetMethod,
            reference,
            editor,
            inlineThisOnly,
            false,
            false,
            deleteDeclaration
        )
    }

    writeIntentReadAction { processor.run() }

    println("Inlined method: $methodName")
} finally {
    withContext(Dispatchers.EDT) {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Rename](mcp-steroid://lsp/rename) - Rename symbol across project
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
