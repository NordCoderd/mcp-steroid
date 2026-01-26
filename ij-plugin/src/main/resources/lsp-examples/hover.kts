/**
 * LSP: textDocument/hover - Hover Information
 *
 * This example demonstrates how to get documentation and type information
 * for a symbol at a given position, similar to hovering over code in the IDE.
 *
 * IntelliJ API used:
 * - DocumentationManager - Get documentation for elements
 * - PsiElement.getReference().resolve() - Find the target element
 * - TypeProvider / PsiType - Get type information
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position to get hover info for
 *
 * Output: Documentation and/or type information
 */

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
    val line = 10      // 1-based line number
    val column = 15    // 1-based column number

    waitForSmartMode()

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
}

/**
 * ## See Also
 *
 * Related LSP examples:
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 * - [Signature Help](mcp-steroid://lsp/signature-help) - Parameter hints for function calls
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in document
 *
 * IDE power operations:
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 * - [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find inheritors
 *
 * Overview resources:
 * - [LSP Examples Overview](mcp-steroid://lsp/overview) - All LSP operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
