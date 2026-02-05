/**
 * IDE: Generate Overrides / Implementations
 *
 * This example generates an override/implementation method in a class,
 * similar to "Code | Implement Methods" or "Code | Override Methods".
 *
 * IntelliJ API used:
 * - OverrideImplementUtil
 * - GenerateMembersUtil
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file containing the target class
 * - baseMethodName: Method name to implement/override
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of generated methods
 */

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.psi.PsiSubstitutor
import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.codeInsight.generation.OverrideImplementUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val baseMethodName = "toString" // TODO: Set method name to override/implement
val dryRun = true


val psiClass = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@readAction null
    PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
}

if (psiClass == null) {
    println("No class found in file: $filePath")
    return
}

val className = readAction { psiClass.name ?: "<unnamed>" }

val baseMethod = readAction {
    val candidates = psiClass.interfaces.flatMap { it.methods.toList() }
    candidates.firstOrNull { it.name == baseMethodName } ?: candidates.firstOrNull()
}

if (baseMethod == null) {
    println("No base method found to implement/override.")
    return
}

val prototypes = readAction { OverrideImplementUtil.overrideOrImplementMethod(psiClass, baseMethod, false) }
if (prototypes.isEmpty()) {
    println("No methods generated (already implemented?)")
    return
}
val prototypeNames = readAction { prototypes.map(PsiMethod::getName) }

if (dryRun) {
    println("Generate override prepared for class: $className")
    println("Methods to add: ${prototypeNames.joinToString()}")
    println("Set dryRun=false to apply changes.")
    return
}

val baseClass = readAction { baseMethod.containingClass }
if (baseClass == null) {
    println("Base method has no containing class.")
    return
}

WriteCommandAction.runWriteCommandAction(project) {
    val substitutor = TypeConversionUtil.getSuperClassSubstitutor(
        baseClass,
        psiClass,
        PsiSubstitutor.EMPTY
    )
    val anchor = OverrideImplementUtil.getDefaultAnchorToOverrideOrImplement(psiClass, baseMethod, substitutor)
    val infos = OverrideImplementUtil.convert2GenerationInfos(prototypes)
    GenerateMembersUtil.insertMembersBeforeAnchor(psiClass, anchor, infos)
    FileDocumentManager.getInstance().saveAllDocuments()
}

println("Generated override(s) in class: $className")

/**
 * ## See Also
 *
 * Related IDE operations:
 * - [Generate Constructor](mcp-steroid://ide/generate-constructor) - Create constructors from fields
 * - [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find class inheritors
 * - [Extract Interface](mcp-steroid://ide/extract-interface) - Create interface from class
 * - [Pull Up Members](mcp-steroid://ide/pull-up-members) - Move members to base class
 *
 * Related LSP operations:
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
