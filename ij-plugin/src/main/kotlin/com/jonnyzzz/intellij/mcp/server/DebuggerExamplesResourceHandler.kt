/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for debugger operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ debugger APIs (breakpoints, sessions, threads).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
class DebuggerExamplesResourceHandler : McpRegistrar {

    private val resourceDir = "/debugger-examples"

    /** List of example file names in the debugger-examples directory */
    private val exampleFiles = listOf(
        "set-line-breakpoint.kts",
        "debug-run-configuration.kts",
        "debug-session-control.kts",
        "debug-list-threads.kts",
        "debug-thread-dump.kts",
    )

    /** Dynamically loaded examples with metadata parsed from KDoc comments */
    val examples: List<DynamicResource> by lazy {
        DynamicResourceScanner.loadResources(resourceDir, exampleFiles)
    }

    override fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = "intellij://debugger/overview",
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
                uri = "intellij://debugger/${example.id}",
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
        return DynamicResourceScanner.loadResourceContent("$resourceDir/DEBUGGER_OVERVIEW.md")
            ?: error("DEBUGGER_OVERVIEW.md resource is not found")
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
