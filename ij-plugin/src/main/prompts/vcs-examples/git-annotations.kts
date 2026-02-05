//
//
//
//
//
//
//
//

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem

// Configure: path to the file you want to annotate
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

val annotationProvider = vcs.annotationProvider
if (annotationProvider == null) {
    println("ERROR: VCS does not support annotations")
    return
}

// Get annotations - this may take time for large files
println("Fetching annotations...")
val annotation = annotationProvider?.annotate(virtualFile)

if (annotation == null) {
    println("ERROR: Could not get annotations for file")
    return
}

// Print line-by-line annotations
val lineCount = annotation.lineCount
println("Total lines: $lineCount")
println()
println("Line | Revision | Author | Date | Content Preview")
println("-----|----------|--------|------|----------------")

// Read file content for preview
val content = String(virtualFile.contentsToByteArray()).lines()

for (lineNum in 0 until minOf(lineCount, 50)) { // Limit to first 50 lines
    val revision = annotation.getLineRevisionNumber(lineNum)
    val author = annotation.getLineAuthorValue(lineNum)
    val date = annotation.getLineDate(lineNum)
    val contentPreview = content.getOrNull(lineNum)?.take(40) ?: ""

    println("${lineNum + 1} | ${revision?.asString()?.take(8) ?: "?"} | ${author ?: "?"} | ${date ?: "?"} | $contentPreview")
}

if (lineCount > 50) {
    println("... (${lineCount - 50} more lines)")
}

// Clean up
Disposer.dispose(annotation)
