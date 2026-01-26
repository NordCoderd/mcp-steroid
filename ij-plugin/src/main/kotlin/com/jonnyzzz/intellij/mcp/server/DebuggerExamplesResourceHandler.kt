/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for debugger operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ debugger APIs (breakpoints, sessions, threads).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
@Service(Service.Level.APP)
class DebuggerExamplesResourceHandler : McpRegistrar {

    companion object {
        private const val RESOURCE_DIR = "/debugger-examples"
        private const val OVERVIEW_FILE = "DEBUGGER_OVERVIEW.md"

        private val EXAMPLE_FILES = listOf(
            "set-line-breakpoint.kts",
            "debug-run-configuration.kts",
            "debug-session-control.kts",
            "debug-list-threads.kts",
            "debug-thread-dump.kts",
        )
    }

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
     * Validate all debugger example resources exist in the JAR.
     * Called during registration to fail fast if resources are missing.
     */
    private fun validateResourcesExist() {
        // Validate overview
        val overviewPath = "$RESOURCE_DIR/$OVERVIEW_FILE"
        require(javaClass.getResource(overviewPath) != null) {
            "Debugger overview resource missing from JAR: $overviewPath"
        }

        // Validate all example files
        val missingFiles = EXAMPLE_FILES
            .map { "$RESOURCE_DIR/$it" }
            .filter { javaClass.getResource(it) == null }

        require(missingFiles.isEmpty()) {
            "Debugger example resources missing from JAR: ${missingFiles.joinToString()}"
        }
    }
}

inline val debuggerExamplesResourceHandler: DebuggerExamplesResourceHandler get() = service()
