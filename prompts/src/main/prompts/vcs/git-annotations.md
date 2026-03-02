Git Annotations (Blame) Example

This script shows how to get git blame/annotations for a file.

```kotlin
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager

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
val annotation = annotationProvider.annotate(virtualFile)

try {
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
        val author = annotation.getAuthorsMappingProvider()?.getAuthors()?.get(revision)
        val date = annotation.getLineDate(lineNum)
        val contentPreview = content.getOrNull(lineNum)?.take(40) ?: ""

        println("${lineNum + 1} | ${revision?.asString()?.take(8) ?: "?"} | ${author ?: "?"} | ${date ?: "?"} | $contentPreview")
    }

    if (lineCount > 50) {
        println("... (${lineCount - 50} more lines)")
    }
} finally {
    annotation.close()
}
```

# See also

Related IDE operations:
- [Project Dependencies](mcp-steroid://ide/project-dependencies) - Summarize module dependencies
- [Project Search](mcp-steroid://ide/project-search) - Search files by name or type

Related LSP operations:
- [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol

- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
