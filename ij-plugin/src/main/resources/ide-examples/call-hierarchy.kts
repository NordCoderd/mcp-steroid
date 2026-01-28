/**
 * IDE: Call Hierarchy (Find Callers)
 *
 * This example finds call sites for a method, similar to the
 * "Call Hierarchy" tool window.
 *
 * IntelliJ API used:
 * - MethodReferencesSearch - Find method call references
 * - PsiMethod - Target method resolution
 * - PsiTreeUtil - Walk PSI tree to locate methods
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position inside the method declaration or call
 * - maxResults: Limit the number of call sites shown
 *
 * Output: List of callers and their locations
 */

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val line = 10     // 1-based line number
val column = 15   // 1-based column number
val maxResults = 20


val result = readAction {
    val virtualFile = findFile(filePath)
        ?: return@readAction "File not found: $filePath"
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@readAction "Cannot parse file: $filePath"
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction "Cannot get document for: $filePath"

    val offset = document.getLineStartOffset(line - 1) + (column - 1)
    val element = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    val reference = generateSequence(element) { it.parent }
        .mapNotNull { it.reference }
        .firstOrNull()
    val method = (reference?.resolve() as? PsiMethod)
        ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        ?: return@readAction "No method found at position ($line:$column)"

    val refs = MethodReferencesSearch.search(method, projectScope(), true).findAll()
    if (refs.isEmpty()) {
        "No callers found for ${method.name}"
    } else {
        buildString {
            appendLine("Callers of ${method.name} (${refs.size}):")
            appendLine()
            refs.take(maxResults).forEachIndexed { index, ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile?.path ?: "unknown"
                val refDocument = refElement.containingFile?.let {
                    FileDocumentManager.getInstance().getDocument(it.virtualFile)
                }
                val refLine = refDocument?.getLineNumber(refElement.textOffset)?.plus(1) ?: -1
                val caller = PsiTreeUtil.getParentOfType(refElement, PsiMethod::class.java, false)
                val callerName = caller?.name ?: "<init>"

                appendLine("${index + 1}. $callerName - $refFile:$refLine")
            }
            if (refs.size > maxResults) {
                appendLine("... and ${refs.size - maxResults} more")
            }
        }
    }
}

println(result)

/**
 * ## See Also
 *
 * Related IDE operations:
 * - [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find class inheritors and overrides
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
 *
 * Related LSP operations:
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 * - [Workspace Symbol](mcp-steroid://lsp/workspace-symbol) - Search symbols across workspace
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
