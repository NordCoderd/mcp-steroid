LSP: workspace/symbol - Workspace Symbol Search

This example demonstrates how to search for symbols across the entire

```kotlin
import com.intellij.openapi.roots.ProjectFileIndex

// Helper function for substring / CamelCase matching
fun matchesQuery(name: String, query: String): Boolean {
    if (name.contains(query, ignoreCase = true)) return true
    val queryUpper = query.filter { it.isUpperCase() }
    val nameUpper = name.filter { it.isUpperCase() }
    if (queryUpper.isNotEmpty() && nameUpper.contains(queryUpper)) return true
    return false
}

// Configuration - modify these for your use case
val query = "Main"      // TODO: Set your search query
val searchType = "class"  // TODO: "class", "symbol", or "file"
val maxResults = 20

// Regex patterns for source-level detection (works for Kotlin and Java)
val classRegex = Regex("""^\s*(?:(?:public|private|protected|internal|abstract|open|data|sealed|enum|annotation)\s+)*class\s+(\w+)""")
val funRegex   = Regex("""^\s*(?:(?:public|private|protected|internal|override|suspend)\s+)*fun\s+(\w+)\s*\(""")

val result = readAction {
    buildString {
        appendLine("Workspace Symbol Search")
        appendLine("======================")
        appendLine()
        appendLine("Query: '$query'")
        appendLine("Type: $searchType")
        appendLine()

        val fileIndex = ProjectFileIndex.getInstance(project)
        var count = 0

        when (searchType.lowercase()) {
            "class" -> {
                appendLine("Searching for classes...")
                appendLine("-".repeat(30))

                fileIndex.iterateContent { vf ->
                    if (count >= maxResults) return@iterateContent false
                    val ext = vf.extension?.lowercase()
                    if (ext != "kt" && ext != "java") return@iterateContent true

                    val text = FileDocumentManager.getInstance().getDocument(vf)?.text
                        ?: return@iterateContent true
                    text.lines().forEachIndexed { idx, line ->
                        if (count >= maxResults) return@forEachIndexed
                        val name = classRegex.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                        if (!matchesQuery(name, query)) return@forEachIndexed
                        count++
                        appendLine()
                        appendLine("$count. $name")
                        appendLine("   File: ${vf.path}")
                        appendLine("   Line: ${idx + 1}")
                    }
                    true
                }

                if (count == 0) appendLine("No classes found matching '$query'")
            }

            "symbol" -> {
                appendLine("Searching for symbols (functions)...")
                appendLine("-".repeat(30))

                fileIndex.iterateContent { vf ->
                    if (count >= maxResults) return@iterateContent false
                    val ext = vf.extension?.lowercase()
                    if (ext != "kt" && ext != "java") return@iterateContent true

                    val text = FileDocumentManager.getInstance().getDocument(vf)?.text
                        ?: return@iterateContent true
                    text.lines().forEachIndexed { idx, line ->
                        if (count >= maxResults) return@forEachIndexed
                        val name = funRegex.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                        if (!matchesQuery(name, query)) return@forEachIndexed
                        count++
                        appendLine()
                        appendLine("$count. $name()")
                        appendLine("   File: ${vf.path}:${idx + 1}")
                    }
                    true
                }

                if (count == 0) appendLine("No symbols found matching '$query'")
            }

            "file" -> {
                appendLine("Searching for files...")
                appendLine("-".repeat(30))

                fileIndex.iterateContent { vf ->
                    if (count >= maxResults) return@iterateContent false
                    if (matchesQuery(vf.name, query)) {
                        count++
                        appendLine()
                        appendLine("$count. ${vf.name}")
                        appendLine("   Path: ${vf.path}")
                    }
                    true
                }

                if (count == 0) appendLine("No files found matching '$query'")
            }

            else -> {
                appendLine("Unknown search type: $searchType")
                appendLine("Use 'class', 'symbol', or 'file'")
            }
        }
    }
}

println(result)
```

# See also

IDE power operations:
- [Project Search](mcp-steroid://ide/project-search) - Search files by name or type
- [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find class inheritors

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
