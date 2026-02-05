/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.docs.ResourceGraphPrompt

/**
 * Handler for the complete resource graph documentation.
 * Provides a visual map of all MCP resources and their relationships.
 */
class ResourceGraphHandler : McpRegistrar {
    private val resourceDescription = """
        Complete visual map of all MCP resources and their relationships.

        Includes:
        - Hierarchical structure of all 57 resources
        - Mermaid diagrams showing resource relationships
        - URI reference tables for all resources
        - Common navigation paths for different tasks
        - Usage examples and best practices

        This is the recommended starting point for exploring the MCP Steroid resource system.
    """.trimIndent()

    override fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://docs/resource-graph",
            name = "Complete Resource Graph",
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = {
                ResourceGraphPrompt().readPrompt()
            }
        )
    }
}
