import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import com.intellij.refactoring.introduceVariable.IntroduceVariableHandler
import com.intellij.refactoring.introduceVariable.IntroduceVariableSettings
import com.intellij.refactoring.introduceVariable.InputValidator
import com.intellij.refactoring.ui.TypeSelectorManagerImpl

data class ExpressionData(
    val expression: PsiExpression,
    val text: String,
    val type: PsiType,
    val typeText: String
)

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val line = 10     // TODO: 1-based line number
val column = 15   // TODO: 1-based column number
val newVariableName = "extracted"  // TODO: Set the variable name
val dryRun = true


val (psiFile, document) = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null to null
    val psi = PsiManager.getInstance(project).findFile(virtualFile)
    val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
    psi to doc
}

if (psiFile == null || document == null) {
    println("File not found or no document: $filePath")
    return
}

val offset = document.getLineStartOffset(line - 1) + (column - 1)

val expressionData = readAction {
    val element = psiFile.findElementAt(offset) ?: return@readAction null
    val expression = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false)
        ?: return@readAction null
    val type = expression.type ?: return@readAction null
    ExpressionData(expression, expression.text, type, type.presentableText)
}

if (expressionData == null) {
    println("No expression found at $line:$column")
    return
}

if (dryRun) {
    println("Introduce variable prepared at $filePath:$line:$column")
    println("Expression: ${expressionData.text}")
    println("New variable: $newVariableName : ${expressionData.typeText}")
    println("Set dryRun=false to apply changes.")
    return
}

val handler = object : IntroduceVariableHandler() {
    override fun getSettings(
        project: com.intellij.openapi.project.Project,
        editor: com.intellij.openapi.editor.Editor?,
        expr: PsiExpression,
        occurrences: Array<PsiExpression>,
        typeSelectorManager: TypeSelectorManagerImpl,
        declareFinalIfAll: Boolean,
        anyAssignmentLHS: Boolean,
        validator: InputValidator?,
        anchor: com.intellij.psi.PsiElement,
        replaceChoice: IntroduceVariableBase.JavaReplaceChoice?
    ): IntroduceVariableSettings {
        return object : IntroduceVariableSettings {
            override fun getEnteredName(): String = newVariableName
            override fun isReplaceAllOccurrences(): Boolean = false
            override fun isDeclareFinal(): Boolean = false
            override fun isReplaceLValues(): Boolean = false
            override fun getSelectedType() = expressionData.type
            override fun isOK(): Boolean = true
        }
    }

    override fun reportConflicts(
        conflicts: com.intellij.util.containers.MultiMap<com.intellij.psi.PsiElement, String>,
        project: com.intellij.openapi.project.Project,
        dialog: IntroduceVariableSettings
    ): Boolean = true
}

val editor = withContext(Dispatchers.EDT) {
    EditorFactory.getInstance().createEditor(document, project)
}
try {
    val success = withContext(Dispatchers.EDT) {
        handler.invokeImpl(project, expressionData.expression, null, IntroduceVariableBase.JavaReplaceChoice.NO, editor)
    }
    if (!success) {
        println("Introduce variable failed.")
        return
    }
    withContext(Dispatchers.EDT) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
} finally {
    withContext(Dispatchers.EDT) {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}

println("Introduced variable: $newVariableName")
