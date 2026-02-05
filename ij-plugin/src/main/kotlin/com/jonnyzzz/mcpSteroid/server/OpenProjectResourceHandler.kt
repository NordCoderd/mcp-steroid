/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.openProject.OpenProjectIndex

/**
 * Handler for open-project workflow resources.
 * Provides examples and guidance for opening projects via MCP.
 *
 * Resources are registered under the URI scheme `mcp-steroid://open-project/`.
 */
class OpenProjectResourceHandler : McpRegistrar {

    /**
     * Open project example resource definition.
     */
    data class OpenProjectExample(
        val id: String,
        val name: String,
        val description: String,
        val resourceFile: () -> String
    )

    override fun register(server: McpServerCore) {
        val index = OpenProjectIndex()

        val examples = listOf(
            OpenProjectExample(
                id = "open-trusted",
                name = "Open Project (Trusted)",
                description = """
                Open a project with automatic trust.

                This example shows how to:
                - Use steroid_open_project with trust_project=true
                - Skip the trust project dialog
                - Verify the project is open

                Best for: Projects you trust and want to open quickly.
            """.trimIndent(),
                resourceFile = {
                    index.openTrustedMd.readPrompt()
                }
            ),
            OpenProjectExample(
                id = "open-with-dialogs",
                name = "Open Project (With Dialog Handling)",
                description = """
                Open a project and handle dialogs interactively.

                This example shows how to:
                - Use steroid_open_project without trusting
                - Use steroid_take_screenshot to see dialogs
                - Use steroid_input to interact with dialogs

                Best for: When you need to review/handle trust dialogs.
            """.trimIndent(),
                resourceFile = {
                    index.openWithDialogsMd.readPrompt()
                }
            ),
            OpenProjectExample(
                id = "open-via-code.kts",
                name = "Open Project (Via Code)",
                description = """
                Open a project programmatically using IntelliJ APIs.

                This example shows how to:
                - Use TrustedProjects API to trust a project
                - Use ProjectManagerEx.openProjectAsync to open
                - Handle the async nature of project opening

                Best for: Advanced scenarios where you need more control.
            """.trimIndent(),
                resourceFile = {
                    index.openViaCodeKts.provideMergedContent()
                }
            )
        )

        // Register overview resource
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://open-project/overview",
            name = "Open Project Workflow Overview",
            description = """
                Overview of how to open projects in IntelliJ via MCP.

                This resource explains the workflow for opening projects,
                handling trust dialogs, and verifying project is open.

                Includes examples for both trusted and interactive workflows.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = {
                index.openProjectOverviewMd.readPrompt()
            }

        )

        // Register each example as a separate resource
        examples.forEach { example ->
            val mimeType = if (example.id.endsWith(".kts")) "text/x-kotlin" else "text/markdown"
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://open-project/${example.id}",
                name = "Open Project: ${example.name}",
                description = example.description,
                mimeType = mimeType,
                contentProvider = { example.resourceFile() }
            )
        }
    }
}
