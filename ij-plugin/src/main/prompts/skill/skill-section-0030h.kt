/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope


readAction {
    val facade = JavaPsiFacade.getInstance(project)
    val psiClass = facade.findClass(
        "com.example.MyClass",
        GlobalSearchScope.allScope(project)
    )

    if (psiClass != null) {
        println("Found class: ${psiClass.qualifiedName}")
        println("File: ${psiClass.containingFile.virtualFile.path}")

        // List methods
        psiClass.methods.forEach { method ->
            val params = method.parameterList.parameters
                .joinToString { "${it.name}: ${it.type.presentableText}" }
            println("  ${method.name}($params): ${method.returnType?.presentableText}")
        }

        // List fields
        psiClass.fields.forEach { field ->
            println("  field ${field.name}: ${field.type.presentableText}")
        }
    }
}
