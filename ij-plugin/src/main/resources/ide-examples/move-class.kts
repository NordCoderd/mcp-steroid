/**
 * IDE: Move Class / Package
 *
 * This example moves a class to another package and updates references,
 * similar to "Refactor | Move".
 *
 * IntelliJ API used:
 * - MoveClassesOrPackagesProcessor
 * - PackageWrapper
 * - SingleSourceRootMoveDestination
 *
 * Parameters to customize:
 * - classFqn: Fully-qualified name of the class to move
 * - targetPackage: New package name
 * - targetDirPath: Directory for the target package
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of move operation
 *
 * WARNING: This modifies code. Use dryRun=true to preview changes first.
 */

import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.PackageWrapper
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor
import com.intellij.refactoring.move.moveClassesOrPackages.SingleSourceRootMoveDestination

// Configuration - modify these for your use case
val classFqn = "com.example.MoveMe"       // TODO: Set class FQN
val targetPackage = "com.example.moved"   // TODO: Set target package
val targetDirPath = "/path/to/target/dir" // TODO: Set target directory
val dryRun = true


data class MovePlan(
    val summary: String,
    val classFqn: String,
    val targetPackage: String,
    val targetDirPath: String
)

val (summary, plan) = readAction {
    val scope = GlobalSearchScope.projectScope(project)
    val psiClass = JavaPsiFacade.getInstance(project).findClass(classFqn, scope)
        ?: return@readAction "Class not found: $classFqn" to null
    val targetDirVf = findFile(targetDirPath)
        ?: return@readAction "Target directory not found: $targetDirPath" to null
    val targetDir = PsiManager.getInstance(project).findDirectory(targetDirVf)
        ?: return@readAction "Cannot resolve target directory: $targetDirPath" to null

    val summary = "Prepared to move ${psiClass.name} to package $targetPackage"
    summary to MovePlan(summary, classFqn, targetPackage, targetDir.virtualFile.path)
}

if (plan == null || dryRun) {
    println(summary)
    if (plan != null && dryRun) {
        println("Set dryRun=false to apply changes.")
    }
    return
}

writeIntentReadAction {
    val scope = GlobalSearchScope.projectScope(project)
    val psiClass = JavaPsiFacade.getInstance(project).findClass(plan.classFqn, scope)!!
    val targetDir = PsiManager.getInstance(project).findDirectory(findFile(plan.targetDirPath)!!)!!
    val destination = SingleSourceRootMoveDestination(
        PackageWrapper(PsiManager.getInstance(project), plan.targetPackage),
        targetDir
    )
    val processor = MoveClassesOrPackagesProcessor(
        project,
        arrayOf(psiClass),
        destination,
        true,
        true,
        null
    )
    processor.run()
}

println("Moved class: ${plan.classFqn} -> ${plan.targetPackage}")

/**
 * ## See Also
 *
 * Related IDE refactorings:
 * - [Move File](mcp-steroid://ide/move-file) - Move files to another directory
 * - [Extract Interface](mcp-steroid://ide/extract-interface) - Create interface from class
 * - [Pull Up Members](mcp-steroid://ide/pull-up-members) - Move members to base class
 * - [Safe Delete](mcp-steroid://ide/safe-delete) - Safely remove elements
 *
 * Related LSP operations:
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
