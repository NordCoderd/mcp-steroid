/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.refactoring.RefactoringFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val psiClass = readAction {
    JavaPsiFacade.getInstance(project)
        .findClass("com.example.OldName", GlobalSearchScope.projectScope(project))
}

if (psiClass != null) {
    val factory = RefactoringFactory.getInstance(project)
    val rename = factory.createRename(psiClass, "NewName")
    rename.run()
    println("Refactoring completed")
}
