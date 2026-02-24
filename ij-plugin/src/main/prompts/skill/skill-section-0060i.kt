/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope


// Discover methods available on common interfaces
val interfaces = listOf(
    "com.intellij.openapi.project.Project",
    "com.intellij.psi.PsiFile",
    "com.intellij.psi.PsiElement",
    "com.intellij.openapi.editor.Editor"
)

readAction {
    interfaces.forEach { className ->
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project))

        if (psiClass == null) {
            println("Not found: $className")
            return@forEach
        }

        val simpleName = className.substringAfterLast('.')
        println("\n=== $simpleName ===")

        // Show key methods (non-deprecated, public)
        psiClass.methods
            .filter { !it.isDeprecated && it.hasModifierProperty("public") }
            .sortedBy { it.name }
            .take(15)
            .forEach { method ->
                val params = method.parameterList.parameters.size
                val returnType = method.returnType?.presentableText ?: "void"
                println("  ${method.name}($params params): $returnType")
            }
    }
}
