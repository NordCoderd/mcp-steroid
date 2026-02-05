/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.debuggerExamples.DebuggerExamplesIndex

/**
 * Handler for debugger operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating how to
 * work with IntelliJ debugger APIs (breakpoints, sessions, threads).
 *
 * Resource metadata (name, description) is dynamically parsed from
 * KDoc comments in the .kts files.
 */
class DebuggerExamplesResourceHandler : McpRegistrar {
    override fun register(server: McpServerCore) {
        val index = DebuggerExamplesIndex()

        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://debugger/overview",
            name = "Debugger Examples Overview",
            description = """
                Overview of IntelliJ debugger operation examples.

                This resource lists runnable scripts for debugger workflows
                such as breakpoints, starting debug sessions, and inspecting threads.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = {
                index.debuggerOverviewMd.readPrompt()
            }
        )

        index.articles.values.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://debugger/${example.path}",
                name = "Debugger: ${example.name}",
                description = example.description,
                mimeType = example.mimeType,
                contentProvider = {
                    example.provideMergedContent()
                }
            )
        }
    }
}
