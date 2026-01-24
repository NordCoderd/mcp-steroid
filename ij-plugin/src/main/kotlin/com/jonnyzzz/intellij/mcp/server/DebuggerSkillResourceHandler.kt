/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for the IntelliJ Debugger skill guide resource.
 */
@Service(Service.Level.APP)
class DebuggerSkillResourceHandler : McpRegistrar {

    companion object {
        private const val SKILL_RESOURCE_PATH = "/skill/DEBUGGER_SKILL.md"
    }

    private val resourceUri = "intellij://skill/debugger-guide"
    private val resourceName = "IntelliJ Debugger Skill Guide"
    private val resourceDescription = """
        Debugger-focused guide for running debug sessions and inspecting threads.

        Covers breakpoints, starting debug run configurations, pausing/resuming,
        and building thread dumps with IntelliJ XDebugger APIs.
    """.trimIndent()

    /** Cached skill content - validated at load time */
    private val skillContent: String by lazy {
        javaClass.getResourceAsStream(SKILL_RESOURCE_PATH)
            ?.bufferedReader()
            ?.readText()
            ?: error("Debugger skill resource not found: $SKILL_RESOURCE_PATH")
    }

    override fun register(server: McpServerCore) {
        // Validate resource exists during registration (fail-fast)
        require(javaClass.getResource(SKILL_RESOURCE_PATH) != null) {
            "Debugger skill resource missing from JAR: $SKILL_RESOURCE_PATH"
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

inline val debuggerSkillResourceHandler: DebuggerSkillResourceHandler get() = service()
