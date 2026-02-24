/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

// NOTE: DaemonCodeAnalyzerEx.getFileLevelHighlights() was removed in IntelliJ 2025.3+
// Use runInspectionsDirectly() instead for reliable inspection results (see below).
readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt") ?: return@readAction
    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction

    val analyzer = DaemonCodeAnalyzer.getInstance(project)
    println("File: ${psiFile.name}")
    println("Use runInspectionsDirectly(vf) for reliable inspection results")
}
