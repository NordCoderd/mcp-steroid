/**
 * IDE: Safe Delete
 *
 * This example safely deletes a method or class, similar to "Refactor | Safe Delete".
 *
 * IntelliJ API used:
 * - SafeDeleteProcessor
 * - ReferencesSearch (for preview)
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position inside method or class to delete
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of delete operation or error message
 */

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
    val line = 10     // 1-based line number
    val column = 15   // 1-based column number
    val dryRun = true

    waitForSmartMode()

    val (psiFile, document) = readAction {
        val virtualFile = findFile(filePath) ?: return@readAction null to null
        val psi = PsiManager.getInstance(project).findFile(virtualFile)
        val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
        psi to doc
    }

    if (psiFile == null || document == null) {
        println("File not found or no document: $filePath")
        return@execute
    }

    val offset = document.getLineStartOffset(line - 1) + (column - 1)
    val element = readAction { psiFile.findElementAt(offset) }
    if (element == null) {
        println("No element found at $line:$column")
        return@execute
    }

    val namedElement = readAction {
        PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
            ?: (element.reference?.resolve() as? PsiNamedElement)
    }

    if (namedElement == null) {
        println("No deletable element found at $line:$column")
        return@execute
    }

    val targetName = readAction { namedElement.name ?: "<unnamed>" }
    val usages = readAction { ReferencesSearch.search(namedElement).findAll() }
    if (dryRun) {
        println("Safe delete prepared for: $targetName")
        println("Usages found: ${usages.size}")
        println("Set dryRun=false to apply changes.")
        return@execute
    }

    val processor = SafeDeleteProcessor.createInstance(
        project,
        null,
        arrayOf(namedElement),
        false,
        false
    )

    val isTestMode = ApplicationManager.getApplication().isUnitTestMode
    if (isTestMode) {
        writeAction { processor.run() }
    } else {
        processor.run()
    }

    writeAction { FileDocumentManager.getInstance().saveAllDocuments() }
    println("Safely deleted: $targetName")
}
