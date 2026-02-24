/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiJavaFile
val testVf = readAction {
    FilenameIndex.getVirtualFilesByName("UserRestControllerTests.java",
        GlobalSearchScope.projectScope(project)).firstOrNull()
}
val testImports = testVf?.let { vf -> readAction {
    (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)
        ?.importList?.importStatements?.map { it.qualifiedName ?: "" }
} }
println("Test imports (required class names):\n" + testImports?.joinToString("\n"))
// If imports include PasswordValidator → create src/main/.../util/PasswordValidator.java
// as a @Component, NOT a private method in UserServiceImpl.
