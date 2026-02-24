/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
val scope = GlobalSearchScope.projectScope(project)
val adviceAnnotation = readAction {
    JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.ControllerAdvice", allScope())
        ?: JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.RestControllerAdvice", allScope())
}
val adviceClasses = if (adviceAnnotation != null) {
    AnnotatedElementsSearch.searchPsiClasses(adviceAnnotation, scope).findAll().toList()
} else emptyList()
println("@ControllerAdvice classes: " + adviceClasses.map { it.qualifiedName })
// Find which exceptions each @ExceptionHandler covers:
adviceClasses.forEach { cls ->
    readAction {
        cls.methods.forEach { m ->
            val handler = m.annotations.firstOrNull { it.qualifiedName?.endsWith("ExceptionHandler") == true }
            if (handler != null) {
                val exTypes = handler.findAttributeValue("value")?.text ?: "(all)"
                println("  ${cls.name}.${m.name} handles: $exTypes → HTTP ${
                    m.annotations.firstOrNull { it.qualifiedName?.endsWith("ResponseStatus") == true }
                        ?.findAttributeValue("code")?.text ?: "?"
                }")
            }
        }
    }
}
// If adviceClasses is empty: the project has NO global exception handler.
// Controllers that throw custom exceptions will return 500. Add a @RestControllerAdvice class.
