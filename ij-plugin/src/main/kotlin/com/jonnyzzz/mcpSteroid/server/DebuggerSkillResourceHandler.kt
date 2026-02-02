/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Handler for the IntelliJ Debugger skill guide resource.
 */
class DebuggerSkillResourceHandler : McpRegistrar {
    private val descriptor = skillResources.debugger
    private val skillResourcePath = descriptor.resourcePath
    private val resourceUri = descriptor.resourceUri
    private val resourceName = descriptor.resourceName
    private val resourceDescription = """
        Debugger-focused guide for running debug sessions and inspecting threads.

        Covers breakpoints, starting debug run configurations, pausing/resuming,
        and building thread dumps with IntelliJ XDebugger APIs.
    """.trimIndent()

    /** Cached skill content - validated at load time */
    private val skillContent: String by lazy {
        javaClass.getResourceAsStream(skillResourcePath)
            ?.bufferedReader()
            ?.readText()
            ?: error("Debugger skill resource not found: $skillResourcePath")
    }

    override fun register(server: McpServerCore) {
        // Validate resource exists during registration (fail-fast)
        require(javaClass.getResource(skillResourcePath) != null) {
            "Debugger skill resource missing from JAR: $skillResourcePath"
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
