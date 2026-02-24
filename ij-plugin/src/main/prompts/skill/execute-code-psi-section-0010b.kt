/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
// When you need to know a class's methods, fields, or call-sites — use PSI.
// This 1 call replaces reading 5-10 separate files just to trace code flow.
val cls = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.domain.FeatureService",   // ← actual FQN from pom.xml group + class name
        GlobalSearchScope.projectScope(project)
    )
}
// Print all methods (no file read needed):
cls?.methods?.forEach { m ->
    val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
    println("${m.name}($params): ${m.returnType?.presentableText}")
}
// Also print fields and implemented interfaces in the same call:
cls?.fields?.forEach { f -> println("field ${f.name}: ${f.type.presentableText}") }
cls?.implementsListTypes?.forEach { t -> println("implements: ${t.presentableText}") }
