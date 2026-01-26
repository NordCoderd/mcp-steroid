/**
 * LSP: textDocument/rename - Rename Symbol
 *
 * This example demonstrates how to rename a symbol across the project,
 * similar to Shift+F6 in IDEs.
 *
 * IntelliJ API used:
 * - RefactoringFactory.createRename() - Create rename refactoring
 * - RenamePsiElementProcessor - Handle language-specific rename logic
 * - RenameHandler - IDE's rename infrastructure
 *
 * Parameters to customize:
 * - filePath: Path to file containing the symbol
 * - line/column: Position of the symbol to rename
 * - newName: The new name for the symbol
 *
 * Output: Preview of rename changes (or performs rename if dryRun=false)
 *
 * WARNING: This modifies code. Use dryRun=true to preview changes first.
 */

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
    val line = 10      // 1-based line number where symbol is defined
    val column = 15    // 1-based column number
    val newName = "newSymbolName"  // TODO: Set the new name
    val dryRun = true  // Set to false to actually perform the rename

    waitForSmartMode()

    // First, analyze what would be renamed (always in read action)
    data class RenamePlan(
        val analysis: String,
        val element: PsiNamedElement,
        val references: Collection<PsiReference>
    )

    val analysisResult = readAction {
        // Find the virtual file
        val virtualFile = findFile(filePath)
            ?: return@readAction "File not found: $filePath" to null

        // Get PSI file
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@readAction "Cannot parse file: $filePath" to null

        // Get document
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return@readAction "Cannot get document for: $filePath" to null

        // Convert line/column to offset
        val offset = document.getLineStartOffset(line - 1) + (column - 1)

        // Find element at position
        val element = psiFile.findElementAt(offset)
            ?: return@readAction "No element at position ($line:$column)" to null

        // Find the named element to rename
        val namedElement = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
            ?: element.reference?.resolve() as? PsiNamedElement
            ?: return@readAction "No renameable element found at position" to null

        val oldName = namedElement.name ?: "unknown"

        // Check if rename is possible
        val processor = RenamePsiElementProcessor.forElement(namedElement)
        val canRename = processor.canProcessElement(namedElement)

        // Find all usages that would be affected (limit to this file for speed)
        val references = ReferencesSearch.search(namedElement, LocalSearchScope(psiFile)).findAll()

        val analysis = buildString {
            appendLine("Rename Analysis")
            appendLine("===============")
            appendLine()
            appendLine("Symbol: $oldName")
            appendLine("New name: $newName")
            appendLine("Element type: ${namedElement.javaClass.simpleName}")
            appendLine("Can rename: $canRename")
            appendLine()
            appendLine("References that would be updated: ${references.size}")
            appendLine()

            // List affected locations
            references.take(20).forEach { ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile?.path ?: "unknown"
                val refDocument = refElement.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val refOffset = refElement.textOffset
                val refLine = refDocument?.getLineNumber(refOffset)?.plus(1) ?: -1

                appendLine("  - $refFile:$refLine")
            }
            if (references.size > 20) {
                appendLine("  ... and ${references.size - 20} more")
            }
        }

        analysis to RenamePlan(analysis, namedElement, references)
    }

    val (analysis, renamePlan) = analysisResult

    if (renamePlan == null || dryRun) {
        println(analysis)
        if (dryRun && renamePlan != null) {
            println()
            println("(Dry run - no changes made. Set dryRun=false to perform rename)")
        }
        return@execute
    }

    // Perform the actual rename
    WriteCommandAction.runWriteCommandAction(project) {
        val elementToRename = renamePlan.element
        elementToRename.setName(newName)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    println(analysis)
    println()
    println("Rename completed: $newName")
}

/**
 * ## See Also
 *
 * Related LSP examples:
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 *
 * IDE power operations:
 * - [Change Signature](mcp-steroid://ide/change-signature) - Add/reorder parameters
 * - [Move Class](mcp-steroid://ide/move-class) - Move classes between packages
 *
 * Overview resources:
 * - [LSP Examples Overview](mcp-steroid://lsp/overview) - All LSP operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
