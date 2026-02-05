/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.PromptRegistry

/**
 * Handler for debugger operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ debugger APIs (breakpoints, sessions, threads).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
class DebuggerExamplesResourceHandler : McpRegistrar {
    private val RESOURCE_DIR = "/debugger-examples"
    private val OVERVIEW_FILE = "DEBUGGER_OVERVIEW.md"

    private val EXAMPLE_FILES = listOf(
        "set-line-breakpoint.kts",
        "debug-run-configuration.kts",
        "debug-session-control.kts",
        "debug-list-threads.kts",
        "debug-thread-dump.kts",
        "demo-debug-test.kts",
    )

    /** Dynamically loaded examples with metadata parsed from KDoc comments */
    val examples: List<DynamicResource> by lazy {
        DynamicResourceScanner.loadResources(RESOURCE_DIR, EXAMPLE_FILES)
    }

    override fun register(server: McpServerCore) {
        // Validate all resources exist during registration (fail-fast)
        validateResourcesExist()

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
                uri = "mcp-steroid://debugger/${example.id}",
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

    /**
     * Validate all debugger example resources exist in PromptRegistry.
     * Called during registration to fail fast if resources are missing.
     */
    private fun validateResourcesExist() {
        // Validate overview (use path without leading slash for PromptRegistry)
        val overviewPath = RESOURCE_DIR.removePrefix("/") + "/$OVERVIEW_FILE"
        require(PromptRegistry.contains(overviewPath)) {
            "Debugger overview resource missing: $overviewPath"
        }

        // Validate all example files
        val missingFiles = EXAMPLE_FILES
            .map { RESOURCE_DIR.removePrefix("/") + "/$it" }
            .filter { !PromptRegistry.contains(it) }

        require(missingFiles.isEmpty()) {
            "Debugger example resources missing: ${missingFiles.joinToString()}"
        }
    }
}
