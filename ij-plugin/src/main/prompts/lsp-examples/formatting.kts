//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val dryRun = true  // Set to false to actually format the file


// Get the original content
val originalContent = readAction {
    val virtualFile = findFile(filePath)
        ?: return@readAction null to "File not found: $filePath"

    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction null to "Cannot get document for: $filePath"

    document.text to null
}

val (original, error) = originalContent
if (error != null) {
    println(error)
    return
}

if (dryRun) {
    // For dry run, we need to work with a copy
    val formattedPreview = readAction {
        val virtualFile = findFile(filePath)!!
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@readAction "Cannot parse file: $filePath"

        // Create a copy of the PSI file for preview
        val copy = psiFile.copy()

        val codeStyleManager = CodeStyleManager.getInstance(project)

        // Format the copy (in-memory only)
        try {
            codeStyleManager.reformat(copy)
            copy.text
        } catch (e: Exception) {
            "Error during formatting preview: ${e.message}"
        }
    }

    println("Format Preview")
    println("=============")
    println()
    println("File: $filePath")
    println()

    if (formattedPreview == original) {
        println("No formatting changes needed.")
    } else {
        // Show simple diff
        val originalLines = original!!.lines()
        val formattedLines = formattedPreview.lines()

        println("Changes:")
        println("-".repeat(40))

        var changes = 0
        val maxLines = maxOf(originalLines.size, formattedLines.size)
        for (i in 0 until maxLines) {
            val origLine = originalLines.getOrNull(i) ?: ""
            val newLine = formattedLines.getOrNull(i) ?: ""
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
    println("(Dry run - no changes made. Set dryRun=false to format)")
} else {
    // Actually format the file
    writeAction {
        val virtualFile = findFile(filePath)!!
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)!!
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!

        val codeStyleManager = CodeStyleManager.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project, "Format Document", null, {
            codeStyleManager.reformat(psiFile)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        })

        println("Format Complete")
        println("===============")
        println()
        println("File: $filePath")
        println("File has been formatted according to project code style.")
    }
}
