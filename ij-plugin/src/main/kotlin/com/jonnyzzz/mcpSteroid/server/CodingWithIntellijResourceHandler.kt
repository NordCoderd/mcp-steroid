/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.PromptCodingWithIntelliJ
import com.jonnyzzz.mcpSteroid.prompts.promptFactory

/**
 * Handler for the Coding with IntelliJ guide resource.
 * Serves comprehensive IntelliJ API coding patterns and examples.
 */
class CodingWithIntellijResourceHandler : McpRegistrar {
    val resourceUri = "mcp-steroid://coding-with-intellij"
    val resourceName = "Coding with IntelliJ - Comprehensive Guide"

    val resourceDescription = """
        📚 Comprehensive guide for writing IntelliJ API code via steroid_execute_code.

        This guide covers:
        - Script execution model and suspend functions
        - McpScriptContext API reference
        - Threading rules (read/write actions, smart mode)
        - Common patterns and complete examples
        - Advanced PSI operations
        - Code analysis and inspections
        - Best practices and troubleshooting

        Essential reading for AI agents using IntelliJ MCP Steroid.
    """.trimIndent()

    override fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = resourceUri,
            name = resourceName,
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = ::loadCodingGuide
        )
    }

    /**
     * Load the CODING_WITH_INTELLIJ.md content from generated prompt class.
     */
    fun loadCodingGuide(): String {
        return promptFactory.renderPrompt<PromptCodingWithIntelliJ>()
    }
}
