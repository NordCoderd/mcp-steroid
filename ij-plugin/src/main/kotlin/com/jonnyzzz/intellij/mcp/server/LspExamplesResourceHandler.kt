/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for LSP-like operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to implement
 * a common LSP method using IntelliJ Platform APIs.
 */
@Service(Service.Level.APP)
class LspExamplesResourceHandler : McpRegistrar {

    /**
     * LSP example resource definition.
     */
    data class LspExample(
        val id: String,
        val lspMethod: String,
        val name: String,
        val description: String,
        val resourceFile: String
    )

    val examples = listOf(
        LspExample(
            id = "go-to-definition",
            lspMethod = "textDocument/definition",
            name = "Go to Definition",
            description = """
                Navigate to symbol definition (Ctrl+Click / F12).

                Shows how to:
                - Find element at position
                - Resolve references to find definition
                - Get file/line/column of definition

                IntelliJ APIs: PsiManager, PsiReference.resolve()
            """.trimIndent(),
            resourceFile = "/lsp-examples/go-to-definition.kts"
        ),
        LspExample(
            id = "find-references",
            lspMethod = "textDocument/references",
            name = "Find All References",
            description = """
                Find all usages of a symbol (Alt+F7).

                Shows how to:
                - Search for all references to a symbol
                - Get location of each reference
                - Show context for each usage

                IntelliJ APIs: ReferencesSearch.search()
            """.trimIndent(),
            resourceFile = "/lsp-examples/find-references.kts"
        ),
        LspExample(
            id = "hover",
            lspMethod = "textDocument/hover",
            name = "Hover Information",
            description = """
                Get documentation and type info on hover.

                Shows how to:
                - Get quick documentation for element
                - Extract type information
                - Use DocumentationProvider

                IntelliJ APIs: LanguageDocumentation, DocumentationProvider
            """.trimIndent(),
            resourceFile = "/lsp-examples/hover.kts"
        ),
        LspExample(
            id = "completion",
            lspMethod = "textDocument/completion",
            name = "Code Completion",
            description = """
                Get code completion suggestions (Ctrl+Space).

                Shows how to:
                - Analyze completion context
                - List available completion contributors
                - Understand completion infrastructure

                IntelliJ APIs: CompletionContributor, CompletionService
            """.trimIndent(),
            resourceFile = "/lsp-examples/completion.kts"
        ),
        LspExample(
            id = "document-symbols",
            lspMethod = "textDocument/documentSymbol",
            name = "Document Symbols",
            description = """
                List all symbols in a document (Structure view).

                Shows how to:
                - Use StructureViewBuilder for symbols
                - Traverse PSI tree for declarations
                - Get symbol type and location

                IntelliJ APIs: StructureViewBuilder, PsiRecursiveElementVisitor
            """.trimIndent(),
            resourceFile = "/lsp-examples/document-symbols.kts"
        ),
        LspExample(
            id = "rename",
            lspMethod = "textDocument/rename",
            name = "Rename Symbol",
            description = """
                Rename symbol across project (Shift+F6).

                Shows how to:
                - Preview rename changes (dry run)
                - Use RefactoringFactory for rename
                - Find all affected locations

                IntelliJ APIs: RefactoringFactory, RenamePsiElementProcessor

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/lsp-examples/rename.kts"
        ),
        LspExample(
            id = "formatting",
            lspMethod = "textDocument/formatting",
            name = "Format Document",
            description = """
                Format document according to code style.

                Shows how to:
                - Preview formatting changes
                - Use CodeStyleManager for formatting
                - Apply project code style

                IntelliJ APIs: CodeStyleManager.reformat()

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/lsp-examples/formatting.kts"
        ),
        LspExample(
            id = "code-action",
            lspMethod = "textDocument/codeAction",
            name = "Code Actions / Quick Fixes",
            description = """
                Get available quick fixes and refactorings (Alt+Enter).

                Shows how to:
                - List available intentions at position
                - Run inspections to find problems
                - Get fixes for inspection problems

                IntelliJ APIs: IntentionManager, LocalInspectionTool
            """.trimIndent(),
            resourceFile = "/lsp-examples/code-action.kts"
        ),
        LspExample(
            id = "signature-help",
            lspMethod = "textDocument/signatureHelp",
            name = "Signature Help",
            description = """
                Get function parameter hints inside call.

                Shows how to:
                - Find enclosing call expression
                - Resolve function reference
                - Extract parameter information

                IntelliJ APIs: LanguageParameterInfo, PsiReference
            """.trimIndent(),
            resourceFile = "/lsp-examples/signature-help.kts"
        ),
        LspExample(
            id = "workspace-symbol",
            lspMethod = "workspace/symbol",
            name = "Workspace Symbol Search",
            description = """
                Search for symbols across the workspace (Ctrl+N, Ctrl+Shift+N).

                Shows how to:
                - Search for classes by name
                - Search for methods and fields
                - Search for files
                - Support camelCase matching

                IntelliJ APIs: PsiShortNamesCache, ProjectFileIndex
            """.trimIndent(),
            resourceFile = "/lsp-examples/workspace-symbol.kts"
        )
    )

    override fun register(server: McpServerCore) {
        // Register overview resource
        server.resourceRegistry.registerResource(
            uri = "intellij://lsp/overview",
            name = "LSP Examples Overview",
            description = """
                Overview of all LSP-like operation examples for IntelliJ Platform.

                This resource lists all available code snippets that demonstrate
                how to implement common Language Server Protocol operations
                using IntelliJ Platform APIs.

                Each example is a complete, runnable script for steroid_execute_code.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = ::loadOverview
        )

        // Register each example as a separate resource
        examples.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "intellij://lsp/${example.id}",
                name = "LSP: ${example.name}",
                description = example.description,
                mimeType = "text/x-kotlin",
                contentProvider = { loadExample(example.resourceFile) }
            )
        }
    }

    fun loadOverview(): String {
        return javaClass.getResourceAsStream("/lsp-examples/LSP_OVERVIEW.md")
            ?.bufferedReader()
            ?.readText()
            ?: error("LSP_OVERVIEW.md resource is not found")
    }

    fun loadExample(resourceFile: String): String {
        return javaClass.getResourceAsStream(resourceFile)
            ?.bufferedReader()
            ?.readText()
            ?: error("LSP example resource is not found: $resourceFile")
    }
}

inline val lspExamplesResourceHandler: LspExamplesResourceHandler get() = service()
