IDE: Pull Up Members
[IU]
This example pulls a member from a subclass into a base class,

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo

// Configuration - modify these for your use case
val sourceClassFqn = "com.example.Child"   // TODO: Set subclass FQN
val targetClassFqn = "com.example.Base"    // TODO: Set base class FQN
val memberName = "memberToMove"            // TODO: Set member name
val dryRun = true


data class PullUpPlan(
    val summary: String,
    val sourceFqn: String,
    val targetFqn: String,
    val member: PsiMember
)

val (summary, plan) = readAction {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)

    val sourceClass = facade.findClass(sourceClassFqn, scope)
        ?: return@readAction "Source class not found: $sourceClassFqn" to null
    val targetClass = facade.findClass(targetClassFqn, scope)
        ?: return@readAction "Target class not found: $targetClassFqn" to null

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
    val summary = "Prepared to pull up $memberType '$memberName' from $sourceClassFqn to $targetClassFqn"
    summary to PullUpPlan(summary, sourceClassFqn, targetClassFqn, member)
}

if (plan == null || dryRun) {
    println(summary)
    if (plan != null && dryRun) {
        println("Set dryRun=false to apply changes.")
    }
    return
}

writeIntentReadAction {
    val processor = PullUpProcessor(
        JavaPsiFacade.getInstance(project).findClass(plan.sourceFqn, GlobalSearchScope.projectScope(project))!!,
        JavaPsiFacade.getInstance(project).findClass(plan.targetFqn, GlobalSearchScope.projectScope(project))!!,
        arrayOf(MemberInfo(plan.member)),
        DocCommentPolicy(DocCommentPolicy.MOVE)
    )
    processor.run()
}

println("Pulled up member '${plan.member.name}' to ${plan.targetFqn}")
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
