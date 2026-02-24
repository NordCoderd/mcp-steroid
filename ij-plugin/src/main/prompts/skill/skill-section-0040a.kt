/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex

// smartReadAction = waitForSmartMode() + readAction
smartReadAction {
    // Find files by exact name using built-in projectScope()
    val files = FilenameIndex.getVirtualFilesByName("build.gradle.kts", projectScope())

    files.forEach { vf ->
        println("Found: ${vf.path}")
    }
}
