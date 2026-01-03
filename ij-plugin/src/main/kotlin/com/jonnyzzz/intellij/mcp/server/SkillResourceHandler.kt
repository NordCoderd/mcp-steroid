/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for the IntelliJ API Power User Guide resource.
 * Serves the SKILL.md content as an MCP resource.
 */
@Service(Service.Level.APP)
class SkillResourceHandler {

    val resourceUri = "intellij://skill/intellij-api-poweruser-guide"
    val resourceName = "IntelliJ API Power User Guide"

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

    fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = resourceUri,
            name = resourceName,
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = ::loadSkillMd
        )
    }

    /**
     * Load the SKILL.md content from resources.
     * Can be used by both MCP resource and HTTP endpoints.
     */
    fun loadSkillMd(): String {
        val content = javaClass.getResourceAsStream("/skill/SKILL.md")
            ?.bufferedReader()
            ?.readText()
            ?: error("SKILL.md resource is not found")
        return injectPluginVersion(content)
    }

    private fun injectPluginVersion(content: String): String {
        val version = PluginVersionResolver.resolve(javaClass.classLoader)
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

inline val skillResourceHandler: SkillResourceHandler get() = service()
