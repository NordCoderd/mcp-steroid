/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val existing = readAction {
    com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
        "com.example.MyClass",
        com.intellij.psi.search.GlobalSearchScope.projectScope(project)
    )
}
println(if (existing == null) "NOT_FOUND: safe to create" else "EXISTS: " + existing.containingFile.virtualFile.path)
