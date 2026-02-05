/**
 * IDE: Project Search (Index)
 *
 * This example searches project files by name or file type using indices.
 *
 * IntelliJ API used:
 * - FilenameIndex - Search files by name
 * - FileTypeIndex - Search files by file type
 * - FileTypeManager - Resolve file type by extension
 *
 * Parameters to customize:
 * - fileName: Exact file name to search for (optional)
 * - fileExtension: File extension to search for (optional)
 * - maxResults: Limit the number of results shown
 *
 * Output: Search results with file paths
 */

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

/**
 * ## See Also
 *
 * Related IDE operations:
 * - [Project Dependencies](mcp-steroid://ide/project-dependencies) - Summarize module dependencies
 * - [Workspace Symbol](mcp-steroid://lsp/workspace-symbol) - Search symbols across workspace
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
 *
 * Related LSP operations:
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 *
 * Overview resources:
 * - [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE power operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
