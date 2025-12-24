/**
 * LSP: textDocument/references - Find All References
 *
 * This example demonstrates how to find all references to a symbol,
 * similar to "Find Usages" (Alt+F7) in IDEs.
 *
 * IntelliJ API used:
 * - ReferencesSearch.search() - Find all references to a PSI element
 * - PsiReference.getElement() - Get the referencing element
 *
 * Parameters to customize:
 * - filePath: Path to file containing the symbol definition
 * - line/column: Position of the symbol to find references for
 *
 * Output: List of all reference locations
 */

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
    val line = 10      // 1-based line number where symbol is defined
    val column = 15    // 1-based column number

    waitForSmartMode()

    val result = readAction {
        // Find the virtual file
        val virtualFile = findFile(filePath)
            ?: return@readAction "File not found: $filePath"

        // Get PSI file
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@readAction "Cannot parse file: $filePath"

        // Get document to convert line/column to offset
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return@readAction "Cannot get document for: $filePath"

        // Convert line/column to offset
        val offset = document.getLineStartOffset(line - 1) + (column - 1)

        // Find element at position
        val element = psiFile.findElementAt(offset)
            ?: return@readAction "No element at position ($line:$column)"

        // Find the named element (declaration) at or around this position
        val namedElement = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
            ?: element.reference?.resolve() as? PsiNamedElement
            ?: return@readAction "No named element found at position"

        // Search for all references
        val references = ReferencesSearch.search(namedElement, projectScope()).findAll()

        if (references.isEmpty()) {
            "No references found for: ${namedElement.name}"
        } else {
            buildString {
                appendLine("References to '${namedElement.name}' (${references.size} found):")
                appendLine()
                references.forEachIndexed { index, ref ->
                    val refElement = ref.element
                    val refFile = refElement.containingFile?.virtualFile?.path ?: "unknown"
                    val refDocument = refElement.containingFile?.let {
                        PsiDocumentManager.getInstance(project).getDocument(it)
                    }
                    val refOffset = refElement.textOffset
                    val refLine = refDocument?.getLineNumber(refOffset)?.plus(1) ?: -1
                    val refCol = refDocument?.let {
                        refOffset - it.getLineStartOffset(refLine - 1) + 1
                    } ?: -1

                    appendLine("${index + 1}. $refFile:$refLine:$refCol")
                    // Show context (the line of code)
                    if (refDocument != null && refLine > 0) {
                        val lineStart = refDocument.getLineStartOffset(refLine - 1)
                        val lineEnd = refDocument.getLineEndOffset(refLine - 1)
                        val lineText = refDocument.getText(
                            com.intellij.openapi.util.TextRange(lineStart, lineEnd)
                        ).trim()
                        appendLine("   > $lineText")
                    }
                }
            }
        }
    }

    println(result)
}
