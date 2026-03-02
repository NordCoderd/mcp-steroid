LSP: textDocument/rename - Rename Symbol

This example demonstrates how to rename a symbol across the project,

```kotlin
import com.intellij.openapi.util.TextRange

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number where symbol is defined
val column = 15    // TODO: 1-based column number
val newName = "newSymbolName"  // TODO: Set the new name
val dryRun = true  // Set to false to actually perform the rename


// First, analyze what would be renamed (always in read action)
data class RenamePlan(
    val analysis: String,
    val virtualFile: com.intellij.openapi.vfs.VirtualFile,
    val oldName: String,
    // Pre-computed absolute ranges in the document, sorted descending for safe replacement
    val ranges: List<TextRange>
)

val analysisResult = readAction {
    // Find the virtual file
    val virtualFile = findFile(filePath)
        ?: return@readAction "File not found: $filePath" to null

    // Get document
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction "Cannot get document for: $filePath" to null

    // Convert line/column to offset
    val startOffset = document.getLineStartOffset(line - 1) + (column - 1)
    val text = document.text

    // Extract the symbol name (word) at the given position
    var endOffset = startOffset
    while (endOffset < text.length && (text[endOffset].isLetterOrDigit() || text[endOffset] == '_')) {
        endOffset++
    }
    val oldName = text.substring(startOffset, endOffset)
    if (oldName.isEmpty()) {
        return@readAction "No symbol at position ($line:$column)" to null
    }

    // Find all occurrences of the symbol name using word-boundary matching.
    // Sort descending so later replacements don't shift earlier offsets.
    val pattern = Regex("""\b${Regex.escape(oldName)}\b""")
    val ranges = pattern.findAll(text)
        .map { TextRange(it.range.first, it.range.last + 1) }
        .sortedByDescending { it.startOffset }
        .toList()

    val analysis = buildString {
        appendLine("Rename Analysis")
        appendLine("===============")
        appendLine()
        appendLine("Symbol: $oldName")
        appendLine("New name: $newName")
        appendLine()
        appendLine("Occurrences that would be updated: ${ranges.size}")
        appendLine()

        // List affected locations
        ranges.take(20).forEach { range ->
            val rangeLine = document.getLineNumber(range.startOffset) + 1
            val rangeCol = range.startOffset - document.getLineStartOffset(rangeLine - 1) + 1
            appendLine("  - $filePath:$rangeLine:$rangeCol")
        }
        if (ranges.size > 20) {
            appendLine("  ... and ${ranges.size - 20} more")
        }
    }

    analysis to RenamePlan(analysis, virtualFile, oldName, ranges)
}

val (analysis, renamePlan) = analysisResult

if (renamePlan == null || dryRun) {
    println(analysis)
    if (dryRun && renamePlan != null) {
        println()
        println("(Dry run - no changes made. Set dryRun=false to perform rename)")
    }
    return
}

// Perform the actual rename via direct document manipulation.
// Ranges are pre-computed in the read action; get document directly from VirtualFile.
writeAction {
    val document = FileDocumentManager.getInstance().getDocument(renamePlan.virtualFile)!!
    CommandProcessor.getInstance().executeCommand(project, {
        for (range in renamePlan.ranges) {
            document.replaceString(range.startOffset, range.endOffset, newName)
        }
    }, "Rename ${renamePlan.oldName} to $newName", null)
    PsiDocumentManager.getInstance(project).commitDocument(document)
    FileDocumentManager.getInstance().saveDocument(document)
}

println(analysis)
println()
println("Rename completed: ${renamePlan.oldName} → $newName")
```

# See also

IDE power operations:
- [Change Signature](mcp-steroid://ide/change-signature) - Add/reorder parameters
- [Move Class](mcp-steroid://ide/move-class) - Move classes between packages

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
