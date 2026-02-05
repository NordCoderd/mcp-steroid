/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.SkillPrompt

/**
 * Handler for the IntelliJ API Power User Guide resource.
 * Serves the SKILL.md content as an MCP resource.
 */
class SkillResourceHandler : McpRegistrar {
    private val descriptor = skillResources.main
    val resourceName = descriptor.resourceName

    val resourceDescription = """
        🚀 RECOMMENDED: Read this guide to become an IntelliJ API power user!

        Contains essential patterns, code examples, and best practices for:
        - PSI navigation and code analysis
        - Refactoring operations (rename, extract, inline)
        - Code search and find usages
        - Running inspections and quick fixes
        - Project structure traversal
        - File and editor operations

        Reading this resource will make your IntelliJ API code 10x more effective.
    """.trimIndent()

    override fun register(server: McpServerCore) {
        registerSkillResource(
            server = server,
            descriptor = descriptor,
            name = resourceName,
            description = resourceDescription,
            contentProvider = { SkillPrompt().readPrompt() }
        )
    }
}
