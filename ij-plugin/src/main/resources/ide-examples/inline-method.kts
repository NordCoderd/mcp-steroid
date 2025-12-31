/**
 * IDE: Inline Method
 *
 * This example inlines a method body at call sites,
 * similar to "Refactor | Inline".
 *
 * IntelliJ API used:
 * - InlineMethodProcessor
 * - EditorFactory for a temporary editor
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position inside method call or declaration
 * - inlineThisOnly: Inline only the selected call site
 * - deleteDeclaration: Remove the original method after inlining
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of inline operation or error message
 */

import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.inline.InlineMethodProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
    val line = 10     // 1-based line number
    val column = 15   // 1-based column number
    val inlineThisOnly = false
    val deleteDeclaration = true
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

    val (targetMethod, reference, methodName) = readAction {
        val ref = element.reference
        val method = (ref?.resolve() as? PsiMethod)
            ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        Triple(method, ref, method?.name ?: "")
    }

    if (targetMethod == null) {
        println("No method found at $line:$column")
        return@execute
    }

    if (dryRun) {
        println("Inline method prepared: $methodName")
        println("inlineThisOnly=$inlineThisOnly deleteDeclaration=$deleteDeclaration")
        println("Set dryRun=false to apply changes.")
        return@execute
    }

    val editor = withContext(Dispatchers.EDT) {
        EditorFactory.getInstance().createEditor(document, project)
    }
    try {
        val processor = readAction {
            InlineMethodProcessor(
                project,
                targetMethod,
                reference,
                editor,
                inlineThisOnly,
                false,
                false,
                deleteDeclaration
            )
        }

        writeIntentReadAction { processor.run() }

        println("Inlined method: $methodName")
    } finally {
        withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }
}
