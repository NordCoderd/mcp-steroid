/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.PromptRegistry

/**
 * Handler for test execution and inspection examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ test runner APIs (run tests, inspect results, navigate test tree).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .md files.
 */
class TestExamplesResourceHandler : McpRegistrar {
    private val RESOURCE_DIR = "/test-examples"
    private val OVERVIEW_FILE = "TEST_OVERVIEW.md"

    private val EXAMPLE_FILES = listOf(
        "list-run-configurations.md",
        "run-tests.md",
        "wait-for-completion.md",
        "inspect-test-results.md",
        "test-tree-navigation.md",
        "test-statistics.md",
        "test-failure-details.md",
        "find-recent-test-run.md",
        "demo-debug-test.md",
    )

    /** Dynamically loaded examples with metadata parsed from KDoc comments */
    val examples: List<DynamicResource> by lazy {
        DynamicResourceScanner.loadResources(RESOURCE_DIR, EXAMPLE_FILES)
    }

    override fun register(server: McpServerCore) {
        // Validate all resources exist during registration (fail-fast)
        validateResourcesExist()

        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://test/overview",
            name = "Test Execution Examples Overview",
            description = """
                Overview of IntelliJ test execution and result inspection examples.

                This resource lists runnable scripts for test workflows
                such as running tests, inspecting results, and navigating test trees.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = ::loadOverview
        )

        examples.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://test/${example.id}",
                name = "Test: ${example.name}",
                description = example.description,
                mimeType = example.mimeType,
                contentProvider = {
                    DynamicResourceScanner.loadResourceContent(example.resourcePath)
                        ?: error("Test example resource not found: ${example.resourcePath}")
                }
            )
        }
    }

    fun loadOverview(): String {
        return DynamicResourceScanner.loadResourceContent("$RESOURCE_DIR/$OVERVIEW_FILE")
            ?: error("Test overview resource not found: $RESOURCE_DIR/$OVERVIEW_FILE")
    }

    /**
     * Load an example resource by its file path.
     * Used by tests for direct resource access.
     */
    fun loadExample(resourceFile: String): String {
        return DynamicResourceScanner.loadResourceContent(resourceFile)
            ?: error("Test example resource not found: $resourceFile")
    }

    /**
     * Validate all test example resources exist in PromptRegistry.
     * Called during registration to fail fast if resources are missing.
     */
    private fun validateResourcesExist() {
        // Validate overview (use path without leading slash for PromptRegistry)
        val overviewPath = RESOURCE_DIR.removePrefix("/") + "/$OVERVIEW_FILE"
        require(PromptRegistry.contains(overviewPath)) {
            "Test overview resource missing: $overviewPath"
        }

        // Validate all example files
        val missingFiles = EXAMPLE_FILES
            .map { RESOURCE_DIR.removePrefix("/") + "/$it" }
            .filter { !PromptRegistry.contains(it) }

        require(missingFiles.isEmpty()) {
            "Test example resources missing: ${missingFiles.joinToString()}"
        }
    }
}
