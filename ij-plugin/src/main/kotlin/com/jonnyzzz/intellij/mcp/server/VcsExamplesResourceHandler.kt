/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for VCS (Version Control System) example resources.
 * Provides code snippets for Git annotations, history, and other VCS operations.
 */
@Service(Service.Level.APP)
class VcsExamplesResourceHandler : McpRegistrar {

    data class VcsExample(
        val id: String,
        val name: String,
        val description: String,
        val resourceFile: String
    )

    private val examples = listOf(
        VcsExample(
            id = "git-annotations",
            name = "Git Annotations (Blame)",
            description = """
                Get git blame/annotations for a file.

                Shows how to:
                - Get VCS for a file
                - Use AnnotationProvider to get blame info
                - Access line-by-line revision, author, and date

                IntelliJ APIs: ProjectLevelVcsManager, AnnotationProvider, FileAnnotation
            """.trimIndent(),
            resourceFile = "/vcs-examples/git-annotations.kts"
        ),
        VcsExample(
            id = "git-history",
            name = "Git File History",
            description = """
                Get commit history for a file.

                Shows how to:
                - Create VcsHistorySession for a file
                - Access revision list
                - Get commit messages, authors, dates

                IntelliJ APIs: VcsHistoryProvider, VcsHistorySession, VcsFileRevision
            """.trimIndent(),
            resourceFile = "/vcs-examples/git-history.kts"
        )
    )

    override fun register(server: McpServerCore) {
        // Register overview resource
        server.resourceRegistry.registerResource(
            uri = "mcp-steroid://vcs/overview",
            name = "VCS Examples Overview",
            description = """
                Overview of all VCS operation examples for IntelliJ Platform.

                This resource lists all available code snippets that demonstrate
                how to use IntelliJ Platform APIs for version control operations
                like git blame, history, and more.

                Each example is a complete, runnable script for steroid_execute_code.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = ::loadOverview
        )

        // Register each example as a separate resource
        examples.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "mcp-steroid://vcs/${example.id}",
                name = "VCS: ${example.name}",
                description = example.description,
                mimeType = "text/x-kotlin",
                contentProvider = { loadExample(example.resourceFile) }
            )
        }
    }

    fun loadOverview(): String {
        return javaClass.getResourceAsStream("/vcs-examples/VCS_OVERVIEW.md")
            ?.bufferedReader()
            ?.readText()
            ?: error("VCS_OVERVIEW.md resource is not found")
    }

    fun loadExample(resourceFile: String): String {
        return javaClass.getResourceAsStream(resourceFile)
            ?.bufferedReader()
            ?.readText()
            ?: error("VCS example resource is not found: $resourceFile")
    }
}

inline val vcsExamplesResourceHandler: VcsExamplesResourceHandler get() = service()
