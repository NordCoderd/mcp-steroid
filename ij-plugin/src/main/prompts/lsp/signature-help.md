LSP: textDocument/signatureHelp - Signature Help

This example demonstrates how to get function signature information

```kotlin
import com.intellij.lang.parameterInfo.LanguageParameterInfo
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number (inside function call)
val column = 20    // TODO: 1-based column number (inside parentheses)


val result = readAction {
    // Find the virtual file
    val virtualFile = findFile(filePath)
        ?: return@readAction "File not found: $filePath"

    // Get PSI file
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@readAction "Cannot parse file: $filePath"

    // Get document
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction "Cannot get document for: $filePath"

    // Convert line/column to offset
    val offset = document.getLineStartOffset(line - 1) + (column - 1)

    // Find element at position
    val element = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    buildString {
        appendLine("Signature Help at $filePath:$line:$column")
        appendLine("=" .repeat(50))
        appendLine()

        // Find the call expression by walking up the tree
        var current: PsiElement? = element
        var callExpression: PsiElement? = null

        // Look for call expression patterns
        while (current != null) {
            val className = current.javaClass.simpleName
            if (className.contains("CallExpression") ||
                className.contains("MethodCall") ||
                className.contains("FunctionCall") ||
                className.contains("ConstructorCall")) {
                callExpression = current
                break
            }
            current = current.parent
        }

        if (callExpression != null) {
            appendLine("Call Expression Found:")
            appendLine("  Type: ${callExpression.javaClass.simpleName}")
            appendLine("  Text: ${callExpression.text?.take(100)}")
            appendLine()

            // Try to get the reference to the called function
            val reference = callExpression.references.firstOrNull()
                ?: PsiTreeUtil.findChildOfType(callExpression, PsiElement::class.java)?.reference

            val resolved = reference?.resolve()

            if (resolved != null) {
                appendLine("Resolved Function:")
                appendLine("  Name: ${(resolved as? PsiNamedElement)?.name}")
                appendLine("  Type: ${resolved.javaClass.simpleName}")
                appendLine("  File: ${resolved.containingFile?.virtualFile?.path}")
                appendLine()

                // Try to get parameter information via reflection
                // (works for both Java and Kotlin)
                try {
                    val getParameterList = resolved.javaClass.methods.find {
                        it.name == "getParameterList" || it.name == "getValueParameterList"
                    }
                    val paramList = getParameterList?.invoke(resolved)

                    if (paramList != null) {
                        val getParameters = paramList.javaClass.methods.find {
                            it.name == "getParameters"
                        }
                        val params = getParameters?.invoke(paramList) as? Array<*>

                        if (params != null && params.isNotEmpty()) {
                            appendLine("Parameters:")
                            params.forEachIndexed { idx, param ->
                                val name = (param as? PsiNamedElement)?.name ?: "param$idx"
                                // Try to get type
                                val getType = param?.javaClass?.methods?.find { it.name == "getType" }
                                val type = getType?.invoke(param)?.toString() ?: "?"
                                appendLine("  ${idx + 1}. $name: $type")
                            }
                        } else {
                            appendLine("  (no parameters)")
                        }
                    }
                } catch (e: Exception) {
                    appendLine("  (Could not extract parameter details: ${e.message})")
                }

                // Get return type if available
                try {
                    val getReturnType = resolved.javaClass.methods.find {
                        it.name == "getReturnType" || it.name == "getReturnTypeReference"
                    }
                    val returnType = getReturnType?.invoke(resolved)
                    if (returnType != null) {
                        appendLine()
                        appendLine("Return Type: $returnType")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            } else {
                appendLine("Could not resolve function reference")
            }
        } else {
            appendLine("No call expression found at this position")
            appendLine()
            appendLine("Context:")
            appendLine("  Element: ${element.text?.take(50)}")
            appendLine("  Type: ${element.javaClass.simpleName}")
            appendLine("  Parent: ${element.parent?.javaClass?.simpleName}")
        }

        appendLine()
        appendLine("Available Parameter Info Handlers:")
        val language = psiFile.language
        val handlers = LanguageParameterInfo.INSTANCE.allForLanguage(language)
        handlers.forEach { handler ->
            appendLine("  - ${handler.javaClass.simpleName}")
        }
    }
}

println(result)
```
