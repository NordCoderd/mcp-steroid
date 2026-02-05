/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.DebuggerSkillPrompt

/**
 * Handler for the IntelliJ Debugger skill guide resource.
 */
class DebuggerSkillResourceHandler : McpRegistrar {
    private val descriptor = skillResources.debugger
    private val resourceName = descriptor.resourceName
    private val resourceDescription = """
        Debugger-focused guide for running debug sessions and inspecting threads.

        Covers breakpoints, starting debug run configurations, pausing/resuming,
        and building thread dumps with IntelliJ XDebugger APIs.
    """.trimIndent()

    override fun register(server: McpServerCore) {
        registerSkillResource(
            server = server,
            descriptor = descriptor,
            name = resourceName,
            description = resourceDescription,
            contentProvider = {
                DebuggerSkillPrompt().readPrompt()
            }
        )
    }
}
