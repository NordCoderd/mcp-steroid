/**
 * LSP: textDocument/codeAction - Code Actions / Quick Fixes
 *
 * This example demonstrates how to get available code actions (quick fixes,
 * refactorings) at a given position, similar to Alt+Enter in IDEs.
 *
 * IntelliJ API used:
 * - ShowIntentionActionsHandler - Get available intentions
 * - IntentionManager - List registered intentions
 * - LocalInspectionTool - Run inspections to find problems
 * - QuickFix - Fixes for inspection problems
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position to get code actions for
 *
 * Output: List of available code actions
 */

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInspection.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiErrorElement

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
    val line = 10      // 1-based line number
    val column = 15    // 1-based column number

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
        val element = psiFile.findElementAt(offset)
            ?: return@readAction "No element at position ($line:$column)"

        buildString {
            appendLine("Code Actions at $filePath:$line:$column")
            appendLine("=".repeat(50))
            appendLine()

            // 1. Check for syntax errors
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            if (errors.isNotEmpty()) {
                appendLine("Syntax Errors (${errors.size}):")
                errors.take(5).forEach { error ->
                    val errorLine = document.getLineNumber(error.textOffset) + 1
                    appendLine("  - Line $errorLine: ${error.errorDescription}")
                }
                appendLine()
            }

            // 2. List available intentions (general)
            appendLine("Available Intentions:")
            appendLine("-".repeat(30))

            val intentionManager = IntentionManager.getInstance()
            val allIntentions = intentionManager.intentionActions

            // Best-effort availability check without editor
            var availableCount = 0
            allIntentions.forEach { intention ->
                try {
                    if (intention.isAvailable(project, null, psiFile)) {
                        availableCount++
                        if (availableCount <= 15) {
                            appendLine("  [Intention] ${intention.text}")
                        }
                    }
                } catch (e: Exception) {
                    // Some intentions may throw during availability check
                }
            }
            if (availableCount > 15) {
                appendLine("  ... and ${availableCount - 15} more intentions")
            }
            if (availableCount == 0) {
                appendLine("  (no intentions available at this position)")
            }
            appendLine()

            // 3. List inspection-based problems in the file
            appendLine("Inspection Problems in File:")
            appendLine("-".repeat(30))

            val inspectionManager = InspectionManager.getInstance(project)
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

            val tools = profile.getAllEnabledInspectionTools(project)
            var problemCount = 0

            for (toolWrapper in tools.take(20)) {
                val tool = toolWrapper.tool
                if (tool is LocalInspectionTool) {
                    try {
                        val problems = tool.checkFile(psiFile, inspectionManager, false)
                        if (problems != null) {
                            for (problem in problems) {
                                problemCount++
                                if (problemCount <= 10) {
                                    val probOffset = problem.psiElement?.textOffset ?: 0
                                    val probLine = document.getLineNumber(probOffset) + 1
                                    appendLine("  Line $probLine: ${problem.descriptionTemplate?.take(60)}")

                                    // List available fixes
                                    val fixes = problem.fixes
                                    if (fixes != null) {
                                        for (fix in fixes) {
                                            appendLine("    -> Fix: ${fix.name}")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Inspection may fail
                    }
                }
            }
            if (problemCount > 10) {
                appendLine("  ... and ${problemCount - 10} more problems")
            }
            if (problemCount == 0) {
                appendLine("  (no inspection problems found)")
            }
        }
    }

    println(result)
}

/**
 * ## See Also
 *
 * Related LSP examples:
 * - [Rename](mcp-steroid://lsp/rename) - Rename symbol across project
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 * - [Hover](mcp-steroid://lsp/hover) - Get documentation/type info
 *
 * IDE power operations:
 * - [Inspect and Fix](mcp-steroid://ide/inspect-and-fix) - Run inspection and apply fix
 * - [Inspection Summary](mcp-steroid://ide/inspection-summary) - List enabled inspections
 *
 * Overview resources:
 * - [LSP Examples Overview](mcp-steroid://lsp/overview) - All LSP operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
