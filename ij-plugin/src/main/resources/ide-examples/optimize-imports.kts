/**
 * IDE: Optimize Imports
 *
 * This example removes unused imports and sorts remaining ones,
 * similar to "Code | Optimize Imports".
 *
 * IntelliJ API used:
 * - JavaCodeStyleManager.optimizeImports()
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file
 * - dryRun: Preview only (no changes)
 *
 * Output: Diff summary or confirmation
 */

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
    val dryRun = true

    waitForSmartMode()

    val (psiFile, document) = readAction {
        val virtualFile = findFile(filePath) ?: return@readAction null to null
        val psi = PsiManager.getInstance(project).findFile(virtualFile)
        val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
        psi to doc
    }

    if (psiFile == null || document == null) {
        println("File not found or no document: $filePath")
        return@execute
    }

    val originalText = document.text

    if (dryRun) {
        val preview = readAction {
            val copy = psiFile.copy() as PsiFile
            JavaCodeStyleManager.getInstance(project).optimizeImports(copy)
            copy.text
        }

        println("Optimize Imports Preview")
        println("=======================")
        println("File: $filePath")
        println()

        if (preview == originalText) {
            println("No import changes needed.")
        } else {
            val originalLines = originalText.lines()
            val newLines = preview.lines()
            println("Changes:")
            println("-".repeat(40))
            var changes = 0
            val maxLines = maxOf(originalLines.size, newLines.size)
            for (i in 0 until maxLines) {
                val origLine = originalLines.getOrNull(i) ?: ""
                val newLine = newLines.getOrNull(i) ?: ""
                if (origLine != newLine) {
                    changes++
                    if (changes <= 20) {
                        println("Line ${i + 1}:")
                        println("  - $origLine")
                        println("  + $newLine")
                    }
                }
            }
            if (changes > 20) {
                println("... and ${changes - 20} more changes")
            }
            println()
            println("Total lines changed: $changes")
        }

        println()
        println("(Dry run - no changes made. Set dryRun=false to optimize imports)")
        return@execute
    }

    WriteCommandAction.runWriteCommandAction(project) {
        JavaCodeStyleManager.getInstance(project).optimizeImports(psiFile)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    println("Optimized imports for: $filePath")
}
