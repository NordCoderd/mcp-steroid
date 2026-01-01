# LSP-like Operations in IntelliJ Platform

This collection provides example code snippets implementing common LSP (Language Server Protocol) operations
using IntelliJ Platform APIs. Each example is a complete script for `steroid_execute_code`.

## Available Examples

### Navigation

| Resource | LSP Method | Description |
|----------|-----------|-------------|
| `intellij://lsp/go-to-definition` | `textDocument/definition` | Navigate to symbol definition |
| `intellij://lsp/find-references` | `textDocument/references` | Find all references to a symbol |
| `intellij://lsp/workspace-symbol` | `workspace/symbol` | Search for symbols across workspace |

### Code Intelligence

| Resource | LSP Method | Description |
|----------|-----------|-------------|
| `intellij://lsp/hover` | `textDocument/hover` | Get documentation/type info for symbol |
| `intellij://lsp/completion` | `textDocument/completion` | Code completion suggestions |
| `intellij://lsp/signature-help` | `textDocument/signatureHelp` | Parameter hints for function calls |

### Document Operations

| Resource | LSP Method | Description |
|----------|-----------|-------------|
| `intellij://lsp/document-symbols` | `textDocument/documentSymbol` | List all symbols in document |
| `intellij://lsp/formatting` | `textDocument/formatting` | Format entire document |

### Refactoring

| Resource | LSP Method | Description |
|----------|-----------|-------------|
| `intellij://lsp/rename` | `textDocument/rename` | Rename symbol across project |
| `intellij://lsp/code-action` | `textDocument/codeAction` | Quick fixes and refactoring actions |

## Usage

1. Read a specific example resource to get the complete code snippet
2. Adapt the code to your needs (file paths, positions, etc.)
3. Execute via `steroid_execute_code`

## Key IntelliJ Concepts

- **PSI (Program Structure Interface)**: IntelliJ's AST representation
- **PsiElement**: Base class for all PSI nodes
- **PsiFile**: Root of a file's PSI tree
- **PsiReference**: Links between PSI elements (e.g., variable usage -> declaration)
- **ReadAction**: Required for reading PSI (use `readAction { }`)
- **WriteAction**: Required for modifying PSI (use `writeAction { }`)

## Language Availability

These examples rely on language plugins to provide PSI, references, intentions, and refactorings.
IntelliJ IDEA ships Java + Kotlin out of the box. Other languages (JavaScript/TypeScript, Python, Go, etc.)
require their plugins to be installed and enabled.

If a script returns "No references found" or "No element at position", first check language availability.
You can probe what is available at runtime:

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.lang.Language

execute {
    val actionIds = ActionManager.getInstance().getActionIdList("").toSet()
    println("Has Java actions: " + actionIds.contains("NewJavaSpecialFile"))
    println("Has Kotlin actions: " + actionIds.contains("Kotlin.NewFile"))
    println("Languages: " + Language.getRegisteredLanguages().map { it.id }.sorted())
}
```

If a language lacks reference providers, fall back to PSI traversal (`PsiNamedElement`),
Structure View (`LanguageStructureViewBuilder`), or inspection output.

## Important Notes

- Always call `waitForSmartMode()` before accessing indices
- Use `readAction { }` for all PSI read operations
- Use `writeAction { }` for modifications
- The `project` variable is available in all scripts
