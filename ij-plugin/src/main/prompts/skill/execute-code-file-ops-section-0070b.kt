/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
val targets = listOf(
    "UserServiceImpl.java", "UserRestControllerTests.java",
    "ExceptionControllerAdvice.java", "User.java"
)
val files = readAction {
    targets.flatMap {
        FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.projectScope(project)).toList()
    }
}
files.forEach { vf ->
    println("\n=== ${vf.name} (${vf.path}) ===")
    println(VfsUtil.loadText(vf))
}
