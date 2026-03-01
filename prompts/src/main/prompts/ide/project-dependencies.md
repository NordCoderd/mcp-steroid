IDE: Project Dependencies

This example summarizes module dependencies and libraries in a project.

```kotlin
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEntry

// Configuration - modify these for your use case
val maxEntries = 20


val result = readAction {
    val modules = ModuleManager.getInstance(project).modules
    buildString {
        appendLine("Project Dependencies (${modules.size} modules)")
        appendLine()
        modules.forEach { module ->
            appendLine("Module: ${module.name}")
            val entries = mutableListOf<OrderEntry>()
            ModuleRootManager.getInstance(module).orderEntries().forEach { entry ->
                entries.add(entry)
                true
            }
            entries.take(maxEntries).forEach { entry ->
                appendLine("  - ${entry.presentableName}")
            }
            if (entries.size > maxEntries) {
                appendLine("  ... and ${entries.size - maxEntries} more")
            }
            appendLine()
        }
    }
}

println(result)
```

# See also

- [Workspace Symbol](mcp-steroid://lsp/workspace-symbol) - Search symbols across workspace
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API patterns
