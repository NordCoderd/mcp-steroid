/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.lspExamples.LspExamplesIndex

/**
 * Handler for LSP-like operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to implement
 * a common LSP method using IntelliJ Platform APIs.
 */
class LspExamplesResourceHandler : McpRegistrar {
    override fun register(server: McpServerCore) {
        val index = LspExamplesIndex()

        // Register overview resource
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://lsp/overview",
            name = "LSP Examples Overview",
            description = """
                Overview of all LSP-like operation examples for IntelliJ Platform.

                This resource lists all available code snippets that demonstrate
                how to implement common Language Server Protocol operations
                using IntelliJ Platform APIs.

                Each example is a complete, runnable script for steroid_execute_code.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = {
                index.lspOverviewMd.readPrompt()
            }
        )

        // Register each example as a separate resource
        index.articles.values.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://lsp/${example.path}",
                name = "LSP: ${example.name}",
                description = example.description,
                mimeType = "text/x-kotlin",
                contentProvider = {
                    example.provideMergedContent()
                }
            )
        }
    }
}
