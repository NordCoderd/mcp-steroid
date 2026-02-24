LSP: textDocument/completion - Code Completion

This example demonstrates how to get code completion suggestions

```kotlin
import com.intellij.codeInsight.completion.CompletionContributor

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number
val column = 15    // TODO: 1-based column number (position where completion is triggered)
val maxResults = 20  // Maximum number of results to return


// Find the virtual file
val virtualFile = findFile(filePath)
    ?: return println("File not found: $filePath")

val (psiFile, document) = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
    psiFile to document
}
if (psiFile == null) {
    return println("Cannot parse file: $filePath")
}
if (document == null) {
    return println("Cannot get document for: $filePath")
}

// Convert line/column to offset
val offset = document.getLineStartOffset(line - 1) + (column - 1)

val result = readAction {
    // Get element at position
    val element = psiFile.findElementAt(offset)
        ?: psiFile.findElementAt(offset - 1)
        ?: return@readAction "No element at position ($line:$column)"

    // Get completion contributor for this file type
    val contributors = CompletionContributor.forLanguage(psiFile.language)

    buildString {
        appendLine("Completion at $filePath:$line:$column")
        appendLine("=========================================")
        appendLine()
        appendLine("Context element: ${element.text?.take(30)}")
        appendLine("Element type: ${element.javaClass.simpleName}")
        appendLine()

        // Check what's available at this position
        val parent = element.parent
        appendLine("Parent: ${parent?.javaClass?.simpleName}")
        appendLine()

        // List available contributors
        appendLine("Available completion contributors:")
        contributors.take(5).forEach { contributor ->
            appendLine("  - ${contributor.javaClass.simpleName}")
        }
        appendLine()

        // For a real completion, you would need to set up the full
        // completion infrastructure. Here we show what's available:
        appendLine("Note: Full programmatic completion requires")
        appendLine("      setting up CompletionProcess which is")
        appendLine("      typically triggered by user action.")
        appendLine()
        appendLine("Alternative approaches:")
        appendLine("1. Use the IDE's completion action directly")
        appendLine("2. Analyze the PSI context to suggest completions")
        appendLine("3. Use CodeInsightTestCase for testing completions")
    }
}

println(result)
```
