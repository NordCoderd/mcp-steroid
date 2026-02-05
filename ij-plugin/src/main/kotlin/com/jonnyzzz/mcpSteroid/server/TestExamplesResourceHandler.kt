/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.testExamples.TestExamplesIndex

/**
 * Handler for test execution and inspection examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ test runner APIs (run tests, inspect results, navigate test tree).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .md files.
 */
class TestExamplesResourceHandler : McpRegistrar {
    override fun register(server: McpServerCore) {

        val index = TestExamplesIndex()

        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://test/overview",
            name = "Test Execution Examples Overview",
            description = """
                Overview of IntelliJ test execution and result inspection examples.

                This resource lists runnable scripts for test workflows
                such as running tests, inspecting results, and navigating test trees.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider =  {
                index.testOverviewMd.readPrompt()
            }
        )

        index.articles.values.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://test/${example.path}",
                name = "Test: ${example.name}",
                description = example.description,
                mimeType = example.mimeType,
                contentProvider = {
                    example.provideMergedContent()
                }
            )
        }
    }
}
