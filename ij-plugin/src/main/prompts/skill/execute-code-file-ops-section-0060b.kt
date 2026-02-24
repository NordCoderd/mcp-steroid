/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope  // ← REQUIRED — missing this causes "unresolved reference"
// Find by exact filename — O(1) IDE index lookup, respects project scope
val matches = readAction {
    FilenameIndex.getVirtualFilesByName("UserServiceImpl.java", GlobalSearchScope.projectScope(project))
}
matches.forEach { println(it.path) }
// Find by extension + path filter:
val filtered = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("user", ignoreCase = true) }
}
filtered.forEach { println(it.path) }
