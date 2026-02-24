/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.searches.AnnotatedElementsSearch
val entityAnnotation = readAction {
    JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
}
AnnotatedElementsSearch.searchPsiClasses(entityAnnotation!!, projectScope()).findAll()
    .forEach { println(it.qualifiedName) }
