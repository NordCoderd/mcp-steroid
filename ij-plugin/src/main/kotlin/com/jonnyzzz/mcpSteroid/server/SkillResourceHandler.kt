/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.PromptSKILL
import com.jonnyzzz.mcpSteroid.prompts.promptFactory

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
            contentProvider = { skillResourceHandler.loadSkillMd() }
        )
    }
}

@Service(Service.Level.APP)
class SkillResource {
    /**
     * Load the SKILL.md content from generated prompt class.
     * Can be used by both MCP resource and HTTP endpoints.
     */
    fun loadSkillMd(): String {
        val content = promptFactory.renderPrompt<PromptSKILL>()
        return injectPluginVersion(content)
    }

    private fun injectPluginVersion(content: String): String {
        val version = com.jonnyzzz.mcpSteroid.PluginDescriptorProvider.getInstance().version
        val headerEnd = content.indexOf("\n---", startIndex = 3)
        if (content.startsWith("---") && headerEnd > 0) {
            val header = content.substring(0, headerEnd)
            val rest = content.substring(headerEnd)
            @Suppress("RegExpRepeatedSpace") // Intentional: matches YAML indentation
            val updatedHeader = header.replaceFirst(Regex("(?m)^  version:.*$"), "  version: \"$version\"")
            return updatedHeader + rest
        }
        return content
    }
}

inline val skillResourceHandler: SkillResource get() = service()
