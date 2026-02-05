/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.vcsExamples.VcsExamplesIndex

/**
 * Handler for VCS (Version Control System) example resources.
 * Provides code snippets for Git annotations, history, and other VCS operations.
 */
class VcsExamplesResourceHandler : McpRegistrar {

    data class VcsExample(
        val id: String,
        val name: String,
        val description: String,
        val resourceFile: () -> String
    )

    override fun register(server: McpServerCore) {
        val index = VcsExamplesIndex()

        // Register overview resource
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://vcs/overview",
            name = "VCS Examples Overview",
            description = """
                Overview of all VCS operation examples for IntelliJ Platform.

                This resource lists all available code snippets that demonstrate
                how to use IntelliJ Platform APIs for version control operations
                like git blame, history, and more.

                Each example is a complete, runnable script for steroid_execute_code.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = {
                index.vcsOverviewMd.readPrompt()
            }
        )

        // Register each example as a separate resource
        index.articles.values.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://vcs/${example.path}",
                name = "VCS: ${example.name}",
                description = example.description,
                mimeType = "text/x-kotlin",
                contentProvider = {
                    example.provideMergedContent()
                }
            )
        }
    }
}
