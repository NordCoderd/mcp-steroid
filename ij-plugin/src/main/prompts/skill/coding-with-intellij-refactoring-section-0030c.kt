/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

// Before adding a field to CreateReleaseCommand — find every constructor call site first
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.commands.CreateReleaseCommand",
        GlobalSearchScope.projectScope(project)
    )
}
if (cmdClass != null) {
    val usages = readAction {
        ReferencesSearch.search(cmdClass, GlobalSearchScope.projectScope(project)).findAll()
    }
    usages.forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
        println("$file:${ref.element.textOffset} → " + ref.element.parent.text.take(120))
    }
} else println("class not found — check FQN")
// Fix every listed call site BEFORE running the compiler.
// This 1 call replaces reading 3-5 files just to find constructors.
