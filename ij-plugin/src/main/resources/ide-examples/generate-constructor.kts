/**
 * IDE: Generate Constructor
 *
 * This example generates a constructor for selected fields,
 * similar to "Generate | Constructor".
 *
 * IntelliJ API used:
 * - GenerateConstructorHandler
 * - GenerateMembersUtil
 *
 * Parameters to customize:
 * - classFqn: Fully-qualified name of the class
 * - fieldNames: Fields to include in the constructor
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of generated constructor
 *
 * WARNING: This modifies code. Use dryRun=true to preview changes first.
 */

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.psi.PsiDocumentManager

execute {
    // Configuration - modify these for your use case
    val classFqn = "com.example.Sample" // TODO: Set class FQN
    val fieldNames = listOf("id", "name")
    val dryRun = true

    waitForSmartMode()

    data class FieldSpec(val name: String, val typeText: String)

    data class ConstructorPlan(
        val summary: String,
        val classFqn: String,
        val constructorText: String
    )

    val (summary, plan) = readAction {
        val scope = GlobalSearchScope.projectScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(classFqn, scope)
            ?: return@readAction "Class not found: $classFqn" to null

        val fields = fieldNames.mapNotNull { name -> psiClass.findFieldByName(name, false) }
        if (fields.isEmpty()) {
            return@readAction "No fields found in $classFqn for $fieldNames" to null
        }

        val className = psiClass.name ?: return@readAction "Class name not available: $classFqn" to null
        val specs = fields.map { field -> FieldSpec(field.name ?: "?", field.type.presentableText) }
        val params = specs.joinToString(", ") { spec -> "${spec.typeText} ${spec.name}" }
        val assignments = specs.joinToString("\n") { spec -> "        this.${spec.name} = ${spec.name};" }
        val constructorText = buildString {
            append("public $className($params) {")
            if (assignments.isNotBlank()) {
                append("\n")
                append(assignments)
                append("\n")
            }
            append("}")
        }

        val fieldList = specs.joinToString { it.name }
        val summary = "Prepared constructor for $className with fields: $fieldList"
        summary to ConstructorPlan(summary, classFqn, constructorText)
    }

    if (plan == null || dryRun) {
        println(summary)
        if (plan != null && dryRun) {
            println("Set dryRun=false to apply changes.")
        }
        return@execute
    }

    WriteCommandAction.runWriteCommandAction(project) {
        val scope = GlobalSearchScope.projectScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(plan.classFqn, scope)
        if (psiClass == null) {
            println("Class not found: ${plan.classFqn}")
            return@runWriteCommandAction
        }
        val factory = JavaPsiFacade.getElementFactory(project)
        val constructor = factory.createMethodFromText(plan.constructorText, psiClass)
        GenerateMembersUtil.setupGeneratedMethod(constructor)
        psiClass.add(constructor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    println("Generated constructor for ${plan.classFqn}")
}
