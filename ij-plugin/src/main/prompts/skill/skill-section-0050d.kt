/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem


readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)

    if (psiFile != null) {
        val inspectionManager = InspectionManager.getInstance(project)
        // Note: Getting specific inspections requires more setup
        // This shows the basic pattern
        println("File analyzed: ${psiFile.name}")
    }
}
