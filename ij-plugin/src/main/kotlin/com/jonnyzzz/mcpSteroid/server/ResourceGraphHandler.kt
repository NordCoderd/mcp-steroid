/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.PromptRegistry

/**
 * Handler for the complete resource graph documentation.
 * Provides a visual map of all MCP resources and their relationships.
 */
class ResourceGraphHandler : McpRegistrar {
    private val RESOURCE_PATH = "docs/RESOURCE_GRAPH.md"
    private val resourceUri = "mcp-steroid://docs/resource-graph"
    private val resourceName = "Complete Resource Graph"
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

    /** Cached resource content - validated at load time */
    private val resourceContent: String by lazy {
        PromptRegistry.getContent(RESOURCE_PATH)
            ?: error("Resource graph resource not found: $RESOURCE_PATH")
    }

    override fun register(server: McpServerCore) {
        // Validate resource exists during registration (fail-fast)
        require(PromptRegistry.contains(RESOURCE_PATH)) {
            "Resource graph resource missing: $RESOURCE_PATH"
        }

        server.resourceRegistry.registerResource(
            uri = resourceUri,
            name = resourceName,
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = ::loadResourceGraph
        )
    }

    fun loadResourceGraph(): String = resourceContent
}
