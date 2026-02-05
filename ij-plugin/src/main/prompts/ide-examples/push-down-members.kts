/**
 * IDE: Push Down Members
 *
 * This example pushes a member from a base class into its subclasses,
 * similar to "Refactor | Push Members Down".
 *
 * IntelliJ API used:
 * - PushDownProcessor
 * - MemberInfo
 * - DocCommentPolicy
 *
 * Parameters to customize:
 * - sourceClassFqn: Fully-qualified name of the base class
 * - memberName: Member (method/field) to push down
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of push down operation
 *
 * WARNING: This modifies code. Use dryRun=true to preview changes first.
 */

import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo

// Configuration - modify these for your use case
val sourceClassFqn = "com.example.Base"  // TODO: Set base class FQN
val memberName = "memberToMove"          // TODO: Set member name
val dryRun = true


data class PushDownPlan(
    val summary: String,
    val sourceFqn: String,
    val member: PsiMember
)

val (summary, plan) = readAction {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)

    val sourceClass = facade.findClass(sourceClassFqn, scope)
        ?: return@readAction "Source class not found: $sourceClassFqn" to null

    val inheritors = ClassInheritorsSearch.search(sourceClass, scope, false).findAll()
    if (inheritors.isEmpty()) {
        return@readAction "No inheritors found for $sourceClassFqn" to null
    }

    val member: PsiMember? =
        sourceClass.findMethodsByName(memberName, false).firstOrNull()
            ?: sourceClass.findFieldByName(memberName, false)

    if (member == null) {
        return@readAction "Member not found: $memberName in $sourceClassFqn" to null
    }

    val memberType = when (member) {
        is PsiMethod -> "method"
        is PsiField -> "field"
        else -> "member"
    }
    val summary = "Prepared to push down $memberType '$memberName' from $sourceClassFqn to ${inheritors.size} inheritors"
    summary to PushDownPlan(summary, sourceClassFqn, member)
}

if (plan == null || dryRun) {
    println(summary)
    if (plan != null && dryRun) {
        println("Set dryRun=false to apply changes.")
    }
    return
}

writeIntentReadAction {
    val sourceClass = JavaPsiFacade.getInstance(project)
        .findClass(plan.sourceFqn, GlobalSearchScope.projectScope(project))!!
    val processor = PushDownProcessor(
        sourceClass,
        listOf(MemberInfo(plan.member)),
        DocCommentPolicy(DocCommentPolicy.MOVE),
        false
    )
    processor.run()
}

println("Pushed down member '${plan.member.name}' from ${plan.sourceFqn}")

/**
 * ## See Also
 *
 * Related IDE refactorings:
 * - [Pull Up Members](mcp-steroid://ide/pull-up-members) - Move members to base class
 * - [Extract Interface](mcp-steroid://ide/extract-interface) - Create interface from class
 * - [Move Class](mcp-steroid://ide/move-class) - Move classes between packages
 * - [Safe Delete](mcp-steroid://ide/safe-delete) - Safely remove elements
 *
 * Related LSP operations:
 * - [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find class inheritors
 * - [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
