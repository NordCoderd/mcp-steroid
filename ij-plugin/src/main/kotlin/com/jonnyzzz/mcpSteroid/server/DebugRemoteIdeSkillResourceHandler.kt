/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Handler for the Remote IDE Debugging skill guide resource.
 *
 * This guide explains how AI agents can debug a target IDE (CLion, Rider, etc.)
 * from IntelliJ IDEA as the debugger host using MCP Steroid.
 */
class DebugRemoteIdeSkillResourceHandler : McpRegistrar {
    private val SKILL_RESOURCE_PATH = "/skill/DEBUG_REMOTE_IDE_SKILL.md"
    private val resourceUri = "mcp-steroid://skill/debug-remote-ide-guide"
    private val resourceName = "How to Debug Another IDE Instance"
    private val resourceDescription = """
        Guide for AI agents on debugging IntelliJ-based IDEs (CLion, Rider, etc.)
        using IntelliJ IDEA as the debugger host with MCP Steroid.

        Covers launching target IDE in debug mode, setting breakpoints programmatically,
        injecting code via debugger, monitoring logs, and validating plugin functionality.

        Written entirely by AI agents using MCP Steroid.
    """.trimIndent()

    /** Cached skill content - validated at load time */
    private val skillContent: String by lazy {
        javaClass.getResourceAsStream(SKILL_RESOURCE_PATH)
            ?.bufferedReader()
            ?.readText()
            ?: error("Debug Remote IDE skill resource not found: $SKILL_RESOURCE_PATH")
    }

    override fun register(server: McpServerCore) {
        // Validate resource exists during registration (fail-fast)
        require(javaClass.getResource(SKILL_RESOURCE_PATH) != null) {
            "Debug Remote IDE skill resource missing from JAR: $SKILL_RESOURCE_PATH"
        }

        server.resourceRegistry.registerResource(
            uri = resourceUri,
            name = resourceName,
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = ::loadSkillMd
        )
    }

    fun loadSkillMd(): String = skillContent
}
