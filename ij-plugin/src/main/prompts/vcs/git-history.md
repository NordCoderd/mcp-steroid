Git History Example

This script shows how to get the commit history for a file.

```kotlin
import com.intellij.openapi.vcs.ProjectLevelVcsManager
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
```
