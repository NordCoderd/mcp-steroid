/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiJavaFile
val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ProductServiceApiTests.java",
        GlobalSearchScope.projectScope(project)).firstOrNull()
}
val testImports = testFile?.let { vf -> readAction {
    (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)
        ?.importList?.importStatements?.map { it.qualifiedName ?: "" }
}}
println("Test imports (use these packages for new files):\n" + testImports?.joinToString("\n"))
// ↑ The package prefixes from these imports are the GROUND TRUTH for any new files to create.
