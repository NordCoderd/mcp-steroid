/**
 * IDE: Inspection + Quick Fix
 *
 * This example runs a local inspection and applies a quick fix,
 * similar to "Code | Inspect Code" + Alt+Enter.
 *
 * IntelliJ API used:
 * - LocalInspectionTool.checkFile()
 * - LocalQuickFix.applyFix()
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - dryRun: Preview only (no changes)
 *
 * Output: Inspection results and fix status
 */

import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.codeInspection.redundantCast.RedundantCastInspection
import com.intellij.util.PairProcessor

data class ProblemInfo(
    val problem: ProblemDescriptor,
    val description: String,
    val fix: QuickFix<CommonProblemDescriptor>?,
    val fixName: String?
)

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
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

    val inspection = RedundantCastInspection()
    val problems: List<ProblemDescriptor> = readAction {
        val wrapper = LocalInspectionToolWrapper(inspection)
        val map = InspectionEngine.inspectEx(
            listOf(wrapper),
            psiFile,
            psiFile.textRange,
            psiFile.textRange,
            false,
            false,
            true,
            EmptyProgressIndicator(),
            PairProcessor.alwaysTrue()
        )
        map.values.flatten()
    }

    if (problems.isEmpty()) {
        println("No inspection problems found.")
        return@execute
    }

    val problemInfo = readAction {
        val firstProblem = problems.first()
        val description = firstProblem.descriptionTemplate
        val fix = firstProblem.fixes?.firstOrNull()
        val fixName = fix?.name
        ProblemInfo(firstProblem, description, fix, fixName)
    }

    println("Found ${problems.size} problem(s).")
    println("First problem: ${problemInfo.description}")

    val fix = problemInfo.fix
    val fixName = problemInfo.fixName
    if (fix == null || fixName == null) {
        println("No quick fix available for the first problem.")
        return@execute
    }

    if (dryRun) {
        println("Quick fix available: $fixName")
        println("Set dryRun=false to apply changes.")
        return@execute
    }

    writeAction {
        fix.applyFix(project, problemInfo.problem)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    println("Applied quick fix: $fixName")
}
