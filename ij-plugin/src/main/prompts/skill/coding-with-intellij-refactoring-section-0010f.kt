/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val className = "com.intellij.openapi.project.Project"

readAction {
    val psiClass = JavaPsiFacade.getInstance(project)
        .findClass(className, GlobalSearchScope.allScope(project))

    if (psiClass == null) {
        println("Class not found: $className")
        return@readAction
    }

    println("=== Class: ${psiClass.qualifiedName} ===\n")

    val methods = psiClass.allMethods
    println("Methods (${methods.size}):")
    methods
        .filter { !it.isConstructor }
        .sortedBy { it.name }
        .take(30)
        .forEach { method ->
            val params = method.parameterList.parameters
                .joinToString(", ") { "${it.name}: ${it.type.presentableText}" }
            val returnType = method.returnType?.presentableText ?: "void"
            println("  ${method.name}($params): $returnType")
        }

    val fields = psiClass.allFields
    println("\nFields (${fields.size}):")
    fields.sortedBy { it.name }.forEach { field ->
        println("  ${field.name}: ${field.type.presentableText}")
    }
}
