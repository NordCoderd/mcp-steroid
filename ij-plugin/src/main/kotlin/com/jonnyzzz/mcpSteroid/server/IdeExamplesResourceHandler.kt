/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.ideExamples.IdeExamplesIndex

/**
 * Handler for IDE power operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating advanced
 * IntelliJ IDE operations beyond LSP.
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
class IdeExamplesResourceHandler : McpRegistrar {
    override fun register(server: McpServerCore) {
        val ideExamplesIndex = IdeExamplesIndex()

        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://ide/overview",
            name = "IDE Examples Overview",
            description = """
                Overview of IntelliJ IDE power operation examples.

                This resource lists runnable scripts for advanced IDE operations
                such as refactorings, inspections, and code generation.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = {
                ideExamplesIndex.ideOverviewMd.readPrompt()
            }
        )

        ideExamplesIndex.articles.values.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://ide/${example.name}",
                name = "IDE: ${example.name}",
                description = example.description,
                mimeType = example.mimeType,
                contentProvider = {
                    example.provideMergedContent()
                }
            )
        }
    }
}
