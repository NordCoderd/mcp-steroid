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

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex

// Configuration - modify these for your use case
val fileName = "RefactorSample.java" // Leave empty to skip name search
val fileExtension = "java"           // Leave empty to skip extension search
val maxResults = 20


val result = readAction {
    buildString {
        appendLine("Project Search Results")
        appendLine("======================")
        appendLine()

        if (fileName.isNotBlank()) {
            val files = FilenameIndex.getVirtualFilesByName(fileName, projectScope())
            appendLine("By name '$fileName' (${files.size}):")
            files.take(maxResults).forEach { vf ->
                appendLine("  - ${vf.path}")
            }
            if (files.size > maxResults) {
                appendLine("  ... and ${files.size - maxResults} more")
            }
            appendLine()
        }

        if (fileExtension.isNotBlank()) {
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(fileExtension)
            val files = FileTypeIndex.getFiles(fileType, projectScope())
            appendLine("By extension '.$fileExtension' (${files.size}):")
            files.take(maxResults).forEach { vf ->
                appendLine("  - ${vf.path}")
            }
            if (files.size > maxResults) {
                appendLine("  ... and ${files.size - maxResults} more")
            }
        }
    }
}

println(result)
