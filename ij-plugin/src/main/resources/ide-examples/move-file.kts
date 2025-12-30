/**
 * IDE: Move File
 *
 * This example moves a file to another directory,
 * similar to "Refactor | Move".
 *
 * IntelliJ API used:
 * - MoveFilesOrDirectoriesProcessor
 *
 * Parameters to customize:
 * - filePath: Absolute path to the file to move
 * - targetDirPath: Absolute path to the target directory
 * - dryRun: Preview only (no changes)
 *
 * Output: Summary of move or error message
 */

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor

data class MoveData(
    val psiFile: PsiFile,
    val targetDir: PsiDirectory,
    val sourcePath: String,
    val targetPath: String,
    val fileName: String
)

execute {
    // Configuration - modify these for your use case
    val filePath = "/path/to/your/File.java" // TODO: Set your file path
    val targetDirPath = "/path/to/target/dir" // TODO: Set your target directory
    val dryRun = true

    waitForSmartMode()

    val moveData = readAction {
        val sourceFile = findFile(filePath) ?: return@readAction null
        val targetVf = findFile(targetDirPath) ?: return@readAction null
        val psi = PsiManager.getInstance(project).findFile(sourceFile)
        val dir = PsiManager.getInstance(project).findDirectory(targetVf)
        if (psi == null || dir == null) return@readAction null
        MoveData(
            psi,
            dir,
            psi.virtualFile.path,
            dir.virtualFile.path,
            psi.name
        )
    }

    if (moveData == null) {
        println("Source file or target directory not found.")
        return@execute
    }

    if (dryRun) {
        println("Move prepared: ${moveData.sourcePath}")
        println("Target dir: ${moveData.targetPath}")
        println("Set dryRun=false to apply changes.")
        return@execute
    }

    val processor = MoveFilesOrDirectoriesProcessor(
        project,
        arrayOf(moveData.psiFile),
        moveData.targetDir,
        true,
        false,
        false,
        null,
        null
    )

    val isTestMode = ApplicationManager.getApplication().isUnitTestMode
    if (isTestMode) {
        writeAction { processor.run() }
    } else {
        processor.run()
    }

    writeAction { FileDocumentManager.getInstance().saveAllDocuments() }
    println("Moved file: ${moveData.fileName}")
}
