// Git History Example
//
// This script shows how to get the commit history for a file.
// It retrieves commit hashes, messages, authors, and dates.
//
// Prerequisites:
// - File must be in a Git repository
// - Git4Idea plugin must be enabled

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.vcsUtil.VcsUtil

// Configure: path to the file you want history for
val filePath = project.basePath + "/src/main/kotlin/com/example/MyClass.kt"

val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
if (virtualFile == null) {
    println("ERROR: File not found: $filePath")
    return
}

val vcsManager = ProjectLevelVcsManager.getInstance(project)
val vcs = vcsManager.getVcsFor(virtualFile)

if (vcs == null) {
    println("ERROR: File is not under version control: $filePath")
    return
}

println("VCS: ${vcs.name}")
println("File: ${virtualFile.name}")
println()

val historyProvider = vcs.vcsHistoryProvider
if (historyProvider == null) {
    println("ERROR: VCS does not support history")
    return
}

// Create FilePath from VirtualFile
val vcsFilePath = VcsUtil.getFilePath(virtualFile)

// Get history session
println("Fetching history...")
val session = historyProvider.createSessionFor(vcsFilePath)

if (session == null) {
    println("ERROR: Could not create history session")
    return
}

val revisions = session.revisionList
println("Found ${revisions.size} revisions")
println()

// Print revision history
println("Revision | Author | Date | Message")
println("---------|--------|------|--------")

for ((index, revision) in revisions.take(20).withIndex()) {
    val revNum = revision.revisionNumber.asString().take(8)
    val author = revision.author ?: "?"
    val date = revision.revisionDate?.toString() ?: "?"
    val message = revision.commitMessage?.lines()?.firstOrNull()?.take(60) ?: ""

    println("$revNum | $author | $date | $message")
}

if (revisions.size > 20) {
    println("... (${revisions.size - 20} more revisions)")
}

/**
 * ## See Also
 *
 * Related VCS operations:
 * - [Git Annotations](mcp-steroid://vcs/git-annotations) - Get blame/annotations for a file
 *
 * Related IDE operations:
 * - [Project Dependencies](mcp-steroid://ide/project-dependencies) - Summarize module dependencies
 * - [Project Search](mcp-steroid://ide/project-search) - Search files by name or type
 *
 * Related LSP operations:
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 *
 * Overview resources:
 * - [VCS Examples Overview](mcp-steroid://vcs/overview) - All VCS operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
