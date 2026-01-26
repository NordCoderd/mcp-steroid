/**
 * IDE: Hierarchy Search
 *
 * This example finds class inheritors and method overrides,
 * similar to "Find Implementations" / "Call Hierarchy" workflows.
 *
 * IntelliJ API used:
 * - ClassInheritorsSearch
 * - OverridingMethodsSearch
 *
 * Parameters to customize:
 * - classFqn: Fully qualified name of base class/interface
 * - methodName: Optional method name for override search
 *
 * Output: List of inheritors and overrides
 */

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch

data class HierarchyData(
    val baseName: String,
    val inheritors: List<String>,
    val inheritorsCount: Int,
    val methodName: String?,
    val overrides: List<String>,
    val overridesCount: Int
)

execute {
    // Configuration - modify these for your use case
    val classFqn = "com.example.BaseType" // TODO: Set base class/interface FQN
    val methodName = "doWork" // TODO: Set method name or leave as-is

    waitForSmartMode()

    val hierarchyData = readAction {
        val baseClass = JavaPsiFacade.getInstance(project)
            .findClass(classFqn, GlobalSearchScope.projectScope(project))
            ?: return@readAction null
        val inheritors = ClassInheritorsSearch.search(
            baseClass,
            GlobalSearchScope.projectScope(project),
            true
        ).findAll()
        val inheritorNames = inheritors.mapNotNull { it.qualifiedName ?: it.name }
        val method = baseClass.findMethodsByName(methodName, false).firstOrNull()
        val overrides = if (method == null) {
            emptyList()
        } else {
            OverridingMethodsSearch.search(
                method,
                GlobalSearchScope.projectScope(project),
                true
            ).findAll()
        }
        val overrideNames = overrides.mapNotNull { it.containingClass?.qualifiedName ?: it.containingClass?.name }
        HierarchyData(
            baseClass.qualifiedName ?: baseClass.name ?: classFqn,
            inheritorNames,
            inheritors.size,
            method?.name,
            overrideNames,
            overrides.size
        )
    }

    if (hierarchyData == null) {
        println("Base class not found: $classFqn")
        return@execute
    }

    println("Inheritors of ${hierarchyData.baseName}: ${hierarchyData.inheritorsCount}")
    hierarchyData.inheritors.take(20).forEach { inheritor ->
        println(" - $inheritor")
    }
    if (hierarchyData.inheritorsCount > 20) {
        println("... and ${hierarchyData.inheritorsCount - 20} more")
    }

    val resolvedMethodName = hierarchyData.methodName
    if (resolvedMethodName == null) {
        println("Method not found: $methodName")
        return@execute
    }

    println()
    println("Overrides of $resolvedMethodName: ${hierarchyData.overridesCount}")
    hierarchyData.overrides.take(20).forEach { overrideName ->
        println(" - $overrideName")
    }
    if (hierarchyData.overridesCount > 20) {
        println("... and ${hierarchyData.overridesCount - 20} more")
    }
}

/**
 * ## See Also
 *
 * Related IDE operations:
 * - [Call Hierarchy](mcp-steroid://ide/call-hierarchy) - Find method callers
 * - [Extract Interface](mcp-steroid://ide/extract-interface) - Create interface from class
 * - [Pull Up Members](mcp-steroid://ide/pull-up-members) - Move members to base class
 * - [Push Down Members](mcp-steroid://ide/push-down-members) - Move members to subclasses
 *
 * Related LSP operations:
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
