IDE: Inspection + Quick Fix

This example runs a local inspection and applies a quick fix,

```kotlin
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.codeInspection.redundantCast.RedundantCastInspection
import com.intellij.util.PairProcessor

data class ProblemInfo(
    val problem: ProblemDescriptor,
    val description: String,
    val fix: QuickFix<CommonProblemDescriptor>?,
    val fixName: String?
)

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
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
    return
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
    return
}

if (dryRun) {
    println("Quick fix available: $fixName")
    println("Set dryRun=false to apply changes.")
    return
}

writeAction {
    fix.applyFix(project, problemInfo.problem)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    FileDocumentManager.getInstance().saveAllDocuments()
}

println("Applied quick fix: $fixName")
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API patterns
