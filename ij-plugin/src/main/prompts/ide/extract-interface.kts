import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.extractInterface.ExtractInterfaceProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo

// Configuration - modify these for your use case
val sourceClassFqn = "com.example.Source" // TODO: Set class FQN
val interfaceName = "NewInterface"  // TODO: Set interface name
val targetDirPath = "/path/to/target/dir" // TODO: Set target directory
val memberName = "methodToExtract"  // TODO: Set member name
val dryRun = true


data class ExtractPlan(
    val summary: String,
    val sourceFqn: String,
    val interfaceName: String,
    val targetDirPath: String,
    val memberName: String
)

val (summary, plan) = readAction {
    val scope = GlobalSearchScope.projectScope(project)
    val sourceClass = JavaPsiFacade.getInstance(project).findClass(sourceClassFqn, scope)
        ?: return@readAction "Source class not found: $sourceClassFqn" to null
    val member = sourceClass.findMethodsByName(memberName, false).firstOrNull()
        ?: return@readAction "Method not found: $memberName in $sourceClassFqn" to null
    val targetDirVf = findFile(targetDirPath)
        ?: return@readAction "Target directory not found: $targetDirPath" to null
    val targetDir = PsiManager.getInstance(project).findDirectory(targetDirVf)
        ?: return@readAction "Cannot resolve target directory: $targetDirPath" to null

    val summary = "Prepared to extract interface $interfaceName from $sourceClassFqn"
    summary to ExtractPlan(summary, sourceClassFqn, interfaceName, targetDir.virtualFile.path, member.name)
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
    val sourceClass = JavaPsiFacade.getInstance(project).findClass(plan.sourceFqn, scope)
    if (sourceClass == null) {
        println("Source class not found: ${plan.sourceFqn}")
        return@writeIntentReadAction
    }
    val targetDirVf = findFile(plan.targetDirPath)
    if (targetDirVf == null) {
        println("Target directory not found: ${plan.targetDirPath}")
        return@writeIntentReadAction
    }
    val targetDir = PsiManager.getInstance(project).findDirectory(targetDirVf)
    if (targetDir == null) {
        println("Cannot resolve target directory: ${plan.targetDirPath}")
        return@writeIntentReadAction
    }
    val member = sourceClass.findMethodsByName(plan.memberName, false).firstOrNull()
    if (member == null) {
        println("Method not found: ${plan.memberName} in ${plan.sourceFqn}")
        return@writeIntentReadAction
    }
    val info = MemberInfo(member)
    info.isChecked = true
    val processor = ExtractInterfaceProcessor(
        project,
        false,
        targetDir,
        plan.interfaceName,
        sourceClass,
        arrayOf(info),
        DocCommentPolicy(DocCommentPolicy.COPY)
    )
    processor.run()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

WriteCommandAction.runWriteCommandAction(project) {
    val scope = GlobalSearchScope.projectScope(project)
    val sourceClass = JavaPsiFacade.getInstance(project).findClass(plan.sourceFqn, scope) ?: return@runWriteCommandAction
    val targetDirVf = findFile(plan.targetDirPath) ?: return@runWriteCommandAction
    val targetDir = PsiManager.getInstance(project).findDirectory(targetDirVf) ?: return@runWriteCommandAction
    val interfaceFile = targetDir.findFile("${plan.interfaceName}.java")
    val packageName = JavaDirectoryService.getInstance().getPackage(targetDir)?.qualifiedName
    val interfaceText = buildString {
        if (!packageName.isNullOrBlank()) {
            append("package ").append(packageName).append(";\n\n")
        }
        append("public interface ").append(plan.interfaceName).append(" {\n}\n")
    }
    val interfaceClass = if (interfaceFile == null) {
        val created = JavaDirectoryService.getInstance().createInterface(targetDir, plan.interfaceName)
        created
    } else {
        val javaFile = interfaceFile as? com.intellij.psi.PsiJavaFile
        val existingText = interfaceFile.virtualFile?.let { VfsUtil.loadText(it) }.orEmpty()
        if (!existingText.contains("interface ${plan.interfaceName}")) {
            interfaceFile.virtualFile?.let { VfsUtil.saveText(it, interfaceText) }
        }
        javaFile?.classes?.firstOrNull()
    }
    val referenceList = sourceClass.implementsList ?: sourceClass.extendsList
    if (referenceList != null && referenceList.referenceElements.none { it.referenceName == plan.interfaceName }) {
        val factory = JavaPsiFacade.getElementFactory(project)
        val reference = if (interfaceClass != null) {
            factory.createClassReferenceElement(interfaceClass)
        } else {
            factory.createReferenceFromText(plan.interfaceName, sourceClass)
        }
        referenceList.add(reference)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

println("Extracted interface: ${plan.interfaceName}")
