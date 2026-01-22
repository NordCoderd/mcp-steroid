/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for the IntelliJ Debugger skill guide resource.
 */
class DebuggerSkillResourceHandler : McpRegistrar {

    private val resourceUri = "intellij://skill/debugger-guide"
    private val resourceName = "IntelliJ Debugger Skill Guide"
    private val resourceDescription = """
        Debugger-focused guide for running debug sessions and inspecting threads.

        Covers breakpoints, starting debug run configurations, pausing/resuming,
        and building thread dumps with IntelliJ XDebugger APIs.
    """.trimIndent()

    override fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = resourceUri,
            name = resourceName,
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = ::loadSkillMd
        )
    }

    fun loadSkillMd(): String {
        return javaClass.getResourceAsStream("/skill/DEBUGGER_SKILL.md")
            ?.bufferedReader()
            ?.readText()
            ?: error("DEBUGGER_SKILL.md resource is not found")
    }
}
