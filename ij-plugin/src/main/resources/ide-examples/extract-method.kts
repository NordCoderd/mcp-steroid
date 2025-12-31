/**
 * IDE: Extract Method
 *
 * This example extracts a range of statements into a new method,
 * similar to "Refactor | Extract Method" in the IDE.
 *
 * IntelliJ API used:
 * - ExtractMethodProcessor / ExtractMethodHandler
 * - EditorFactory for a temporary editor
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - startLine/endLine: 1-based line range to extract
 * - newMethodName: Name for the extracted method
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of extraction or error message
 */

import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiStatement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.extractMethod.ExtractMethodHandler
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
    val startLine = 10 // 1-based start line of selection
    val endLine = 12   // 1-based end line of selection
    val newMethodName = "extractedMethod"
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

    val editor = withContext(Dispatchers.EDT) {
        EditorFactory.getInstance().createEditor(document, project)
    }
    try {
        val startOffset = document.getLineStartOffset(startLine - 1)
        val endOffset = document.getLineEndOffset(endLine - 1)

        val statements = readAction {
            PsiTreeUtil.collectElements(psiFile) { element ->
                element is PsiStatement &&
                    element.textRange.startOffset >= startOffset &&
                    element.textRange.endOffset <= endOffset
            }.filterIsInstance<PsiStatement>().toTypedArray()
        }

        if (statements.isEmpty()) {
            println("No statements found in the specified line range.")
            return@execute
        }

        val processor = ExtractMethodProcessor(
            project,
            editor,
            statements,
            null,
            "Extract Method",
            newMethodName,
            null
        )
        processor.setShowErrorDialogs(false)
        val prepared = readAction { processor.prepare() }
        if (!prepared) {
            println("Extract method preparation failed.")
            return@execute
        }
        processor.setMethodName(newMethodName)
        readAction {
            processor.prepareVariablesAndName()
            processor.prepareNullability()
        }

        if (dryRun) {
            println("Extract method prepared for $filePath ($startLine-$endLine).")
            println("Set dryRun=false to apply changes.")
            return@execute
        }

        writeIntentReadAction { ExtractMethodHandler.extractMethod(project, processor) }

        println("Extracted method: $newMethodName")
    } finally {
        withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }
}
