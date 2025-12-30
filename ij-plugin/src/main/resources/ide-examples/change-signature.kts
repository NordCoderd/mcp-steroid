/**
 * IDE: Change Signature
 *
 * This example updates a method signature (adds a parameter)
 * and updates call sites, similar to "Refactor | Change Signature".
 *
 * IntelliJ API used:
 * - ChangeSignatureProcessor
 * - ParameterInfoImpl
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - line/column: Position inside method declaration or call
 * - newParameterName/type/defaultValue: Parameter to add
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of change or error message
 */

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ParameterInfoImpl

data class MethodData(
    val name: String,
    val returnType: PsiType,
    val parameterInfos: MutableList<ParameterInfoImpl>,
    val newParamType: PsiType
)

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
    val line = 10     // 1-based line number
    val column = 15   // 1-based column number
    val newParameterName = "extra"
    val newParameterType = "int"
    val newParameterDefaultValue = "0"
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

    val method = readAction {
        (element.reference?.resolve() as? PsiMethod)
            ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    }

    if (method == null) {
        println("No method found at $line:$column")
        return@execute
    }

    val methodData = readAction {
        val elementFactory = JavaPsiFacade.getInstance(project).elementFactory
        val paramType = elementFactory.createTypeFromText(newParameterType, method)
        val existingParams = method.parameterList.parameters
        val infos = existingParams.mapIndexed { index, param ->
            ParameterInfoImpl(index, param.name, param.type)
        }.toMutableList()
        val methodReturnType = method.returnType ?: PsiType.VOID
        MethodData(method.name, methodReturnType, infos, paramType)
    }

    methodData.parameterInfos.add(
        ParameterInfoImpl(-1, newParameterName, methodData.newParamType, newParameterDefaultValue)
    )

    if (dryRun) {
        println("Change signature prepared: ${methodData.name}")
        println("Add parameter: $newParameterName : $newParameterType = $newParameterDefaultValue")
        println("Set dryRun=false to apply changes.")
        return@execute
    }

    val processor = readAction {
        ChangeSignatureProcessor(
            project,
            method,
            false,
            null,
            methodData.name,
            methodData.returnType,
            methodData.parameterInfos.toTypedArray()
        )
    }

    val isTestMode = ApplicationManager.getApplication().isUnitTestMode
    if (isTestMode) {
        writeAction { processor.run() }
    } else {
        processor.run()
    }

    println("Changed signature for: ${methodData.name}")
}
