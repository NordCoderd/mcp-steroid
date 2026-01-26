/**
 * LSP: workspace/symbol - Workspace Symbol Search
 *
 * This example demonstrates how to search for symbols across the entire
 * workspace/project, similar to Ctrl+N (Go to Class) or Ctrl+Shift+N
 * (Go to File) in IDEs.
 *
 * IntelliJ API used:
 * - GotoClassModel2 - Search for classes
 * - GotoSymbolModel2 - Search for all symbols
 * - PsiShortNamesCache - Fast lookup by short name
 * - AllClassesSearch - Search all classes
 *
 * Parameters to customize:
 * - query: Search pattern (supports camelCase matching)
 * - searchType: "class", "symbol", or "file"
 * - maxResults: Maximum number of results
 *
 * Output: List of matching symbols with their locations
 */

import com.intellij.ide.util.gotoByName.*
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

execute {
    // Configuration - modify these for your use case
    val query = "Main"      // TODO: Set your search query
    val searchType = "class"  // "class", "symbol", or "file"
    val maxResults = 20

    waitForSmartMode()

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
}

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

/**
 * ## See Also
 *
 * Related LSP examples:
 * - [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
 * - [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
 * - [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
 * - [Hover](mcp-steroid://lsp/hover) - Get documentation/type info
 *
 * IDE power operations:
 * - [Project Search](mcp-steroid://ide/project-search) - Search files by name or type
 * - [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find class inheritors
 *
 * Overview resources:
 * - [LSP Examples Overview](mcp-steroid://lsp/overview) - All LSP operations
 * - [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
 */
