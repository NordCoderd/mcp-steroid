/**
 * IDE: Extract Interface
 *
 * This example extracts an interface from a class,
 * similar to "Refactor | Extract Interface".
 *
 * IntelliJ API used:
 * - ExtractInterfaceProcessor
 * - MemberInfo
 * - DocCommentPolicy
 *
 * Parameters to customize:
 * - sourceClassFqn: Fully-qualified name of the class
 * - interfaceName: Name of the new interface
 * - targetDirPath: Directory where the interface should be created
 * - memberName: Member (method) to extract
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of extraction
 *
 * WARNING: This modifies code. Use dryRun=true to preview changes first.
 */

import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.extractInterface.ExtractInterfaceProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo

execute {
    // Configuration - modify these for your use case
    val sourceClassFqn = "com.example.Source" // TODO: Set class FQN
    val interfaceName = "NewInterface"
    val targetDirPath = "/path/to/target/dir" // TODO: Set target directory
    val memberName = "methodToExtract"
    val dryRun = true

    waitForSmartMode()

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
        return@execute
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
}

/**
 * ## See Also
 *
 * Related IDE refactorings:
 * - [Pull Up Members](mcp-steroid://ide/pull-up-members) - Move members to base class
 * - [Push Down Members](mcp-steroid://ide/push-down-members) - Move members to subclasses
 * - [Move Class](mcp-steroid://ide/move-class) - Move classes between packages
 * - [Extract Method](mcp-steroid://ide/extract-method) - Extract statements into new method
 *
 * Related LSP operations:
 * - [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find class inheritors
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
