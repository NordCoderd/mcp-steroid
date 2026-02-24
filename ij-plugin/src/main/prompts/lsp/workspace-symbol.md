LSP: workspace/symbol - Workspace Symbol Search

This example demonstrates how to search for symbols across the entire

```kotlin
import com.intellij.psi.search.PsiShortNamesCache

// Helper function for camelCase matching
fun matchesQuery(name: String, query: String): Boolean {
    // Simple substring match
    if (name.contains(query, ignoreCase = true)) return true

    // CamelCase matching (e.g., "MC" matches "MyClass")
    val queryUpper = query.filter { it.isUpperCase() }
    val nameUpper = name.filter { it.isUpperCase() }
    if (queryUpper.isNotEmpty() && nameUpper.contains(queryUpper)) return true

    return false
}


// Configuration - modify these for your use case
val query = "Main"      // TODO: Set your search query
val searchType = "class"  // TODO: "class", "symbol", or "file"
val maxResults = 20


val result = readAction {
    buildString {
        appendLine("Workspace Symbol Search")
        appendLine("======================")
        appendLine()
        appendLine("Query: '$query'")
        appendLine("Type: $searchType")
        appendLine()

        val scope = projectScope()
        val cache = PsiShortNamesCache.getInstance(project)

        when (searchType.lowercase()) {
            "class" -> {
                appendLine("Searching for classes...")
                appendLine("-".repeat(30))

                // Get all class names that match
                val allClassNames = cache.allClassNames.filter {
                    matchesQuery(it, query)
                }.take(maxResults * 2)

                var count = 0
                for (className in allClassNames) {
                    if (count >= maxResults) break

                    val classes = cache.getClassesByName(className, scope)
                    for (psiClass in classes) {
                        if (count >= maxResults) break

                        val file = psiClass.containingFile?.virtualFile?.path ?: "unknown"
                        val doc = psiClass.containingFile?.let {
                            FileDocumentManager.getInstance().getDocument(it.virtualFile)
                        }
                        val line = doc?.getLineNumber(psiClass.textOffset)?.plus(1) ?: 0

                        count++
                        appendLine()
                        appendLine("$count. ${psiClass.qualifiedName ?: psiClass.name}")
                        appendLine("   File: $file")
                        appendLine("   Line: $line")

                        // Show class details
                        val modifiers = buildList {
                            if (psiClass.isInterface) add("interface")
                            if (psiClass.isEnum) add("enum")
                            if (psiClass.isAnnotationType) add("annotation")
                        }
                        if (modifiers.isNotEmpty()) {
                            appendLine("   Type: ${modifiers.joinToString(", ")}")
                        }
                    }
                }

                if (count == 0) {
                    appendLine("No classes found matching '$query'")
                }
            }

            "symbol" -> {
                appendLine("Searching for symbols (methods, fields)...")
                appendLine("-".repeat(30))

                // Search methods
                val methodNames = cache.allMethodNames.filter {
                    matchesQuery(it, query)
                }.take(maxResults)

                var count = 0
                for (methodName in methodNames) {
                    if (count >= maxResults) break

                    val methods = cache.getMethodsByName(methodName, scope)
                    for (method in methods) {
                        if (count >= maxResults) break

                        val containingClass = method.containingClass?.qualifiedName ?: "?"
                        val file = method.containingFile?.virtualFile?.path ?: "unknown"
                        val doc = method.containingFile?.let {
                            FileDocumentManager.getInstance().getDocument(it.virtualFile)
                        }
                        val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0

                        count++
                        appendLine()
                        appendLine("$count. ${method.name}")
                        appendLine("   Class: $containingClass")
                        appendLine("   File: $file:$line")
                    }
                }

                // Search fields
                val fieldNames = cache.allFieldNames.filter {
                    matchesQuery(it, query)
                }.take(maxResults - count)

                for (fieldName in fieldNames) {
                    if (count >= maxResults) break

                    val fields = cache.getFieldsByName(fieldName, scope)
                    for (field in fields) {
                        if (count >= maxResults) break

                        val containingClass = field.containingClass?.qualifiedName ?: "?"
                        val file = field.containingFile?.virtualFile?.path ?: "unknown"
                        val doc = field.containingFile?.let {
                            FileDocumentManager.getInstance().getDocument(it.virtualFile)
                        }
                        val line = doc?.getLineNumber(field.textOffset)?.plus(1) ?: 0

                        count++
                        appendLine()
                        appendLine("$count. ${field.name} (field)")
                        appendLine("   Class: $containingClass")
                        appendLine("   File: $file:$line")
                    }
                }

                if (count == 0) {
                    appendLine("No symbols found matching '$query'")
                }
            }

            "file" -> {
                appendLine("Searching for files...")
                appendLine("-".repeat(30))

                // Use file index
                val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
                var count = 0

                fileIndex.iterateContent { file ->
                    if (count >= maxResults) return@iterateContent false

                    if (matchesQuery(file.name, query)) {
                        count++
                        appendLine()
                        appendLine("$count. ${file.name}")
                        appendLine("   Path: ${file.path}")
                    }
                    true
                }

                if (count == 0) {
                    appendLine("No files found matching '$query'")
                }
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
