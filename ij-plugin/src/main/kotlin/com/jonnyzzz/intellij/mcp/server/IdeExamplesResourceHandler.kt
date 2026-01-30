/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for IDE power operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating advanced
 * IntelliJ IDE operations beyond LSP.
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
class IdeExamplesResourceHandler : McpRegistrar {
    private val RESOURCE_DIR = "/ide-examples"

    /** List of example file names in the ide-examples directory */
    private val EXAMPLE_FILES = listOf(
        "extract-method.kts",
        "introduce-variable.kts",
        "inline-method.kts",
        "change-signature.kts",
        "move-file.kts",
        "safe-delete.kts",
        "optimize-imports.kts",
        "generate-override.kts",
        "inspect-and-fix.kts",
        "hierarchy-search.kts",
        "call-hierarchy.kts",
        "run-configuration.kts",
        "demo-debug-test.kts",
        "pull-up-members.kts",
        "push-down-members.kts",
        "extract-interface.kts",
        "move-class.kts",
        "generate-constructor.kts",
        "project-dependencies.kts",
        "inspection-summary.kts",
        "project-search.kts",
    )

    /** Dynamically loaded examples with metadata parsed from KDoc comments */
    val examples: List<DynamicResource> by lazy {
        DynamicResourceScanner.loadResources(RESOURCE_DIR, EXAMPLE_FILES)
    }

    override fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://ide/overview",
            name = "IDE Examples Overview",
            description = """
                Overview of IntelliJ IDE power operation examples.

                This resource lists runnable scripts for advanced IDE operations
                such as refactorings, inspections, and code generation.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = ::loadOverview
        )

        examples.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://ide/${example.id}",
                name = "IDE: ${example.name}",
                description = example.description,
                mimeType = example.mimeType,
                contentProvider = {
                    DynamicResourceScanner.loadResourceContent(example.resourcePath)
                        ?: error("IDE example resource not found: ${example.resourcePath}")
                }
            )
        }
    }

    fun loadOverview(): String {
        return DynamicResourceScanner.loadResourceContent("$RESOURCE_DIR/IDE_OVERVIEW.md")
            ?: error("IDE_OVERVIEW.md resource is not found")
    }

    /**
     * Load an example resource by its file path.
     * Used by tests for direct resource access.
     */
    fun loadExample(resourceFile: String): String {
        return DynamicResourceScanner.loadResourceContent(resourceFile)
            ?: error("IDE example resource not found: $resourceFile")
    }
}
