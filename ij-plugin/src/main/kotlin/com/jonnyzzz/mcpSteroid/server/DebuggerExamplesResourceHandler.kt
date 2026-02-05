/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.DynamicResource
import com.jonnyzzz.mcpSteroid.prompts.DynamicResourceScanner
import com.jonnyzzz.mcpSteroid.prompts.generated.index.PromptIndexDebuggerExamples

/**
 * Handler for debugger operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ debugger APIs (breakpoints, sessions, threads).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
class DebuggerExamplesResourceHandler : McpRegistrar {
    /** Dynamically loaded examples with metadata parsed from KDoc comments */
    val examples by lazy {
        PromptIndexDebuggerExamples().listKotlinSources()
    }

    override fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://debugger/overview",
            name = "Debugger Examples Overview",
            description = """
                Overview of IntelliJ debugger operation examples.

                This resource lists runnable scripts for debugger workflows
                such as breakpoints, starting debug sessions, and inspecting threads.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = ::loadOverview
        )

        examples.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://debugger/${example.key}",
                name = "Debugger: ${example.name}",
                description = example.description,
                mimeType = example.mimeType,
                contentProvider = {
                    DynamicResourceScanner.loadResourceContent(example.resourcePath)
                        ?: error("Debugger example resource not found: ${example.resourcePath}")
                }
            )
        }
    }

    fun loadOverview(): String {
        return DynamicResourceScanner.loadResourceContent("$RESOURCE_DIR/$OVERVIEW_FILE")
            ?: error("Debugger overview resource not found: $RESOURCE_DIR/$OVERVIEW_FILE")
    }

    /**
     * Load an example resource by its file path.
     * Used by tests for direct resource access.
     */
    fun loadExample(resourceFile: String): String {
        return DynamicResourceScanner.loadResourceContent(resourceFile)
            ?: error("Debugger example resource not found: $resourceFile")
    }
}
