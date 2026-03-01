IDE: Change Signature

This example updates a method signature (adds a parameter)

```kotlin
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ParameterInfoImpl

data class MethodData(
    val name: String,
    val returnType: PsiType?,
    val parameterInfos: MutableList<ParameterInfoImpl>,
    val newParamType: PsiType
)

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val line = 10     // TODO: 1-based line number
val column = 15   // TODO: 1-based column number
val newParameterName = "extra"  // TODO: Set parameter name
val newParameterType = "int"  // TODO: Set parameter type
val newParameterDefaultValue = "0"  // TODO: Set default value
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

val method = readAction {
    (element.reference?.resolve() as? PsiMethod)
        ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
}

if (method == null) {
    println("No method found at $line:$column")
    return
}

val methodData = readAction {
    val elementFactory = JavaPsiFacade.getInstance(project).elementFactory
    val paramType = elementFactory.createTypeFromText(newParameterType, method)
    val existingParams = method.parameterList.parameters
    val infos = existingParams.mapIndexed { index, param ->
        ParameterInfoImpl(index, param.name, param.type)
    }.toMutableList()
    val methodReturnType = method.returnType
    MethodData(method.name, methodReturnType, infos, paramType)
}

methodData.parameterInfos.add(
    ParameterInfoImpl(-1, newParameterName, methodData.newParamType, newParameterDefaultValue)
)

if (dryRun) {
    println("Change signature prepared: ${methodData.name}")
    println("Add parameter: $newParameterName : $newParameterType = $newParameterDefaultValue")
    println("Set dryRun=false to apply changes.")
    return
}

val processor = readAction {
    ChangeSignatureProcessor(
        project,
        method,
        false,
        null,
        methodData.name,
        methodData.returnType,
        methodData.parameterInfos.toTypedArray()
    )
}

writeIntentReadAction { processor.run() }

println("Changed signature for: ${methodData.name}")
```

# See also

- [Signature Help](mcp-steroid://lsp/signature-help) - Parameter hints for function calls
- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API patterns
