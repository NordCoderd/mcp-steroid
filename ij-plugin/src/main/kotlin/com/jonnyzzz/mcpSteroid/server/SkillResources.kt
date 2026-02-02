/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Central registry of bundled Agent Skill resources.
 */
data class SkillDescriptor(
    val id: String,
    val resourcePath: String,
    val resourceUri: String,
    val resourceName: String,
)

@Service(Service.Level.APP)
class SkillResources {
    val main = SkillDescriptor(
        id = "intellij-api-poweruser-guide",
        resourcePath = "/skill/SKILL.md",
        resourceUri = "mcp-steroid://skill/intellij-api-poweruser-guide",
        resourceName = "IntelliJ API Power User Guide",
    )

    val debugger = SkillDescriptor(
        id = "debugger-guide",
        resourcePath = "/skill/DEBUGGER_SKILL.md",
        resourceUri = "mcp-steroid://skill/debugger-guide",
        resourceName = "IntelliJ Debugger Skill Guide",
    )

    val test = SkillDescriptor(
        id = "test-runner-guide",
        resourcePath = "/skill/TEST_SKILL.md",
        resourceUri = "mcp-steroid://skill/test-runner-guide",
        resourceName = "IntelliJ Test Runner Skill Guide",
    )

    val debugRemote = SkillDescriptor(
        id = "debug-remote-ide-guide",
        resourcePath = "/skill/DEBUG_REMOTE_IDE_SKILL.md",
        resourceUri = "mcp-steroid://skill/debug-remote-ide-guide",
        resourceName = "How to Debug Another IDE Instance",
    )

    val all = listOf(main, debugger, test, debugRemote)

    companion object {
        fun getInstance(): SkillResources = service()
    }
}

inline val skillResources: SkillResources get() = service()
