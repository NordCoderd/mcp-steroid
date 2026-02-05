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
