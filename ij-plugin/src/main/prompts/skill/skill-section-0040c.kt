/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FileTypeIndex
import org.jetbrains.kotlin.idea.KotlinFileType

smartReadAction {
    // Find all Kotlin files using built-in projectScope()
    val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, projectScope())

    println("Found ${kotlinFiles.size} Kotlin files")
    kotlinFiles.take(20).forEach { vf ->
        println("  ${vf.path}")
    }
}
