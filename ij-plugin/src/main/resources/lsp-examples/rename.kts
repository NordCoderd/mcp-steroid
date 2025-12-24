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
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringFactory
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

        // Find all usages that would be affected
        val references = ReferencesSearch.search(namedElement, projectScope()).findAll()

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

        analysis to namedElement
    }

    val (analysis, elementToRename) = analysisResult

    if (elementToRename == null || dryRun) {
        println(analysis)
        if (dryRun && elementToRename != null) {
            println()
            println("(Dry run - no changes made. Set dryRun=false to perform rename)")
        }
        return@execute
    }

    // Perform the actual rename
    WriteCommandAction.runWriteCommandAction(project) {
        val factory = RefactoringFactory.getInstance(project)
        val rename = factory.createRename(elementToRename, newName)
        rename.isSearchInComments = true
        rename.isSearchInNonJavaFiles = true

        // Preview mode could be enabled with: rename.setPreviewUsages(true)
        rename.run()
    }

    println(analysis)
    println()
    println("Rename completed: ${(elementToRename as? PsiNamedElement)?.name} -> $newName")
}
