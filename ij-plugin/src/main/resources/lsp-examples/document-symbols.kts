/**
 * LSP: textDocument/documentSymbol - Document Symbols
 *
 * This example demonstrates how to list all symbols (classes, functions,
 * variables, etc.) in a document, similar to the Structure view in IDEs.
 *
 * IntelliJ API used:
 * - StructureViewBuilder - Build structure view for a file
 * - PsiTreeUtil - Navigate PSI tree
 * - PsiNamedElement - Elements with names (classes, methods, etc.)
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 *
 * Output: Hierarchical list of symbols in the document
 */

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.kt"  // TODO: Set your file path

    waitForSmartMode()

    val result = readAction {
        // Find the virtual file
        val virtualFile = findFile(filePath)
            ?: return@readAction "File not found: $filePath"

        // Get PSI file
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@readAction "Cannot parse file: $filePath"

        // Get document for line numbers
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)

        buildString {
            appendLine("Document Symbols: ${virtualFile.name}")
            appendLine("=" .repeat(50))
            appendLine()

            // Method 1: Use StructureView
            val structureViewBuilder = LanguageStructureViewBuilder.INSTANCE
                .getStructureViewBuilder(psiFile)

            if (structureViewBuilder is TreeBasedStructureViewBuilder) {
                appendLine("Structure View Elements:")
                appendLine("-".repeat(30))

                val model = structureViewBuilder.createStructureViewModel(null)
                val root = model.root

                fun printElement(element: com.intellij.ide.structureView.StructureViewTreeElement, indent: String = "") {
                    val value = element.value
                    val presentation = element.presentation
                    val name = presentation.presentableText ?: (value as? PsiNamedElement)?.name ?: "?"
                    val icon = presentation.getIcon(false)?.toString() ?: ""
                    val location = presentation.locationString ?: ""

                    // Get line number if possible
                    val lineInfo = if (value is PsiElement && document != null) {
                        val line = document.getLineNumber(value.textOffset) + 1
                        ":$line"
                    } else ""

                    appendLine("$indent$name$lineInfo $location".trim())

                    element.children.forEach { child ->
                        if (child is com.intellij.ide.structureView.StructureViewTreeElement) {
                            printElement(child, "$indent  ")
                        }
                    }
                }

                printElement(root)
                appendLine()
            }

            // Method 2: Direct PSI traversal
            appendLine("PSI Named Elements:")
            appendLine("-".repeat(30))

            val symbols = mutableListOf<Triple<String, String, Int>>() // name, type, line

            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiNamedElement && element.name != null) {
                        val name = element.name!!
                        val type = element.javaClass.simpleName
                            .replace("Impl", "")
                            .replace("Psi", "")
                            .replace("Kt", "")
                        val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                        symbols.add(Triple(name, type, line))
                    }
                    super.visitElement(element)
                }
            })

            // Group by type
            symbols.groupBy { it.second }.forEach { (type, items) ->
                appendLine()
                appendLine("$type (${items.size}):")
                items.sortedBy { it.third }.forEach { (name, _, line) ->
                    appendLine("  $name (line $line)")
                }
            }
        }
    }

    println(result)
}
