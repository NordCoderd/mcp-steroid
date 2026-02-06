import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // 1-based line number
val column = 15    // 1-based column number


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

    // Convert line/column to offset (both are 1-based in LSP, 0-based in IntelliJ)
    val offset = document.getLineStartOffset(line - 1) + (column - 1)

    // Find element at position
    val element = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    // Try to find a reference on this element or its parents
    val reference = generateSequence(element) { it.parent }
        .mapNotNull { it.reference }
        .firstOrNull()
    val resolved = reference?.resolve()

    if (resolved != null) {
        // Found via reference resolution
        val defFile = resolved.containingFile?.virtualFile?.path ?: "unknown"
        val defDocument = resolved.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val defOffset = resolved.textOffset
        val defLine = defDocument?.getLineNumber(defOffset)?.plus(1) ?: -1
        val defCol = defDocument?.let { defOffset - it.getLineStartOffset(defLine - 1) + 1 } ?: -1

        val name = (resolved as? PsiNamedElement)?.name ?: resolved.text?.take(50)
        """
        |Definition found:
        |  Symbol: $name
        |  File: $defFile
        |  Position: $defLine:$defCol
        |  Element type: ${resolved.javaClass.simpleName}
        """.trimMargin()
    } else {
        // No reference, check if element itself is a declaration
        val parent = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
        if (parent != null) {
            val name = parent.name
            """
            |Element is itself a declaration:
            |  Symbol: $name
            |  File: $filePath
            |  Position: $line:$column
            |  Element type: ${parent.javaClass.simpleName}
            """.trimMargin()
        } else {
            "No definition found for element: ${element.text?.take(50)}"
        }
    }
}

println(result)
