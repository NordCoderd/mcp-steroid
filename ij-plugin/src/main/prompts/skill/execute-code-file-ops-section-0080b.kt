/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
// Find all Java files containing a literal string — uses IDE index, no regex pitfalls
import com.intellij.psi.search.PsiSearchHelper
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
readAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("/api/", scope, { psiFile ->
        matchingFiles.add(psiFile.virtualFile.path)
        true  // continue searching
    }, true)
}
matchingFiles.forEach { println(it) }
// For broader substring search, filter by content after getting candidates:
val containing = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", scope)
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/v1") }
}
containing.forEach { println(it.path) }
