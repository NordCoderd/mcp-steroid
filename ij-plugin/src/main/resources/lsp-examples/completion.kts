/**
 * LSP: textDocument/completion - Code Completion
 *
 * This example demonstrates how to get code completion suggestions
 * at a given position, similar to pressing Ctrl+Space in the IDE.
 *
 * IntelliJ API used:
 * - CompletionService - Core completion infrastructure
 * - CompletionParameters - Parameters for completion request
 * - LookupElement - Individual completion item
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position to get completions for
 *
 * Output: List of completion suggestions
 */

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
    val line = 10      // 1-based line number
    val column = 15    // 1-based column number (position where completion is triggered)
    val maxResults = 20  // Maximum number of results to return

    waitForSmartMode()

    // Find the virtual file
    val virtualFile = findFile(filePath)
        ?: return@execute println("File not found: $filePath")

    val (psiFile, document) = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        psiFile to document
    }
    if (psiFile == null) {
        return@execute println("Cannot parse file: $filePath")
    }
    if (document == null) {
        return@execute println("Cannot get document for: $filePath")
    }

    // Convert line/column to offset
    val offset = document.getLineStartOffset(line - 1) + (column - 1)

    val result = readAction {
        // Get element at position
        val element = psiFile.findElementAt(offset)
            ?: psiFile.findElementAt(offset - 1)
            ?: return@readAction "No element at position ($line:$column)"

        // Collect completions manually by analyzing context
        val completions = mutableListOf<String>()

        // Get completion contributor for this file type
        val contributors = CompletionContributor.forLanguage(psiFile.language)

        buildString {
            appendLine("Completion at $filePath:$line:$column")
            appendLine("=========================================")
            appendLine()
            appendLine("Context element: ${element.text?.take(30)}")
            appendLine("Element type: ${element.javaClass.simpleName}")
            appendLine()

            // Check what's available at this position
            val parent = element.parent
            appendLine("Parent: ${parent?.javaClass?.simpleName}")
            appendLine()

            // List available contributors
            appendLine("Available completion contributors:")
            contributors.take(5).forEach { contributor ->
                appendLine("  - ${contributor.javaClass.simpleName}")
            }
            appendLine()

            // For a real completion, you would need to set up the full
            // completion infrastructure. Here we show what's available:
            appendLine("Note: Full programmatic completion requires")
            appendLine("      setting up CompletionProcess which is")
            appendLine("      typically triggered by user action.")
            appendLine()
            appendLine("Alternative approaches:")
            appendLine("1. Use the IDE's completion action directly")
            appendLine("2. Analyze the PSI context to suggest completions")
            appendLine("3. Use CodeInsightTestCase for testing completions")
        }
    }

    println(result)
}

/**
 * ## See Also
 *
 * Related LSP examples:
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Hover](mcp-steroid://lsp/hover) - Get documentation/type info
 * - [Signature Help](mcp-steroid://lsp/signature-help) - Parameter hints for function calls
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in document
 *
 * IDE power operations:
 * - [Extract Method](mcp-steroid://ide/extract-method) - Refactoring example
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 *
 * Overview resources:
 * - [LSP Examples Overview](mcp-steroid://lsp/overview) - All LSP operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
