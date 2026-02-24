/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex
val created = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", com.intellij.psi.search.GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("/src/main/java/") }
        .map { it.name + " @ " + it.path.substringAfter(project.basePath!!) }
}
println("Created Java files:\n" + created.joinToString("\n"))
// If a file you expected is missing, create ONLY that one — do not recreate the others
