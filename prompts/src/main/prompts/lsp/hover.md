LSP: textDocument/hover - Hover Information

This example demonstrates how to get documentation and type information

```kotlin
import com.intellij.lang.LanguageDocumentation
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number
val column = 15    // TODO: 1-based column number


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

    // Try to resolve reference to get the target element
    val targetElement = element.reference?.resolve()
        ?: PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
        ?: element

    buildString {
        appendLine("Hover Information")
        appendLine("=================")
        appendLine()
        appendLine("Element: ${element.text?.take(50)}")
        appendLine("Element type: ${element.javaClass.simpleName}")
        appendLine()

        if (targetElement !== element) {
            appendLine("Resolved to: ${(targetElement as? PsiNamedElement)?.name ?: targetElement.text?.take(50)}")
            appendLine("Target type: ${targetElement.javaClass.simpleName}")
            appendLine()
        }

        // Try to get documentation
        val language = psiFile.language
        val docProvider = LanguageDocumentation.INSTANCE.forLanguage(language)
        if (docProvider != null) {
            val quickNavigateInfo = docProvider.getQuickNavigateInfo(targetElement, element)
            if (quickNavigateInfo != null) {
                appendLine("Quick Info:")
                appendLine(quickNavigateInfo)
                appendLine()
            }

            // Get full documentation (may contain HTML)
            val docComment = docProvider.generateDoc(targetElement, element)
            if (docComment != null) {
                appendLine("Documentation:")
                // Strip HTML tags for cleaner output
                val plainText = docComment
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .trim()
                appendLine(plainText.take(500))
            }
        }

        // For Kotlin/Java, try to get type information
        try {
            val typeText = when {
                targetElement.javaClass.simpleName.contains("KtProperty") ||
                targetElement.javaClass.simpleName.contains("KtParameter") -> {
                    // Kotlin property/parameter - use reflection to get type
                    val getType = targetElement.javaClass.methods.find { it.name == "getType" }
                    getType?.invoke(targetElement)?.toString()
                }
                targetElement.javaClass.simpleName.contains("PsiVariable") -> {
                    // Java variable
                    val getType = targetElement.javaClass.methods.find { it.name == "getType" }
                    getType?.invoke(targetElement)?.toString()
                }
                else -> null
            }
            if (typeText != null) {
                appendLine()
                appendLine("Type: $typeText")
            }
        } catch (e: Exception) {
            // Type extraction failed, ignore
        }
    }
}

println(result)
```

# See also

IDE power operations:
- [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find inheritors

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API patterns
