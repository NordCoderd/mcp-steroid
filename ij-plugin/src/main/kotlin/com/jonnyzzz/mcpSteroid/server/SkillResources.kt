/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.prompts.*

/**
 * Central registry of bundled Agent Skill resources.
 */
data class SkillDescriptor(
    val id: String,
    val resourceUri: String,
    val resourceName: String,
    val contentProvider: () -> String,
) {
    /** Legacy URI for backwards compatibility */
    val legacyResourceUri: String
        get() = "mcp-steroid://skill/$id"
}

@Service(Service.Level.APP)
class SkillResources {
    val main = SkillDescriptor(
        id = "intellij-api-poweruser-guide",
        resourceUri = "mcp-steroid://skill/intellij-api-poweruser-guide",
        resourceName = "IntelliJ API Power User Guide",
        contentProvider = { skillResourceHandler.loadSkillMd() },
    )

    val debugger = SkillDescriptor(
        id = "debugger-guide",
        resourceUri = "mcp-steroid://skill/debugger-guide",
        resourceName = "IntelliJ Debugger Skill Guide",
        contentProvider = { promptFactory.renderPrompt<PromptDebuggerSkill>() },
    )

    val test = SkillDescriptor(
        id = "test-runner-guide",
        resourceUri = "mcp-steroid://skill/test-runner-guide",
        resourceName = "IntelliJ Test Runner Skill Guide",
        contentProvider = { promptFactory.renderPrompt<PromptTestSkill>() },
    )

    val debugRemote = SkillDescriptor(
        id = "debug-remote-ide-guide",
        resourceUri = "mcp-steroid://skill/debug-remote-ide-guide",
        resourceName = "How to Debug Another IDE Instance",
        contentProvider = { promptFactory.renderPrompt<PromptDebugRemoteIdeSkill>() },
    )

    val all = listOf(main, debugger, test, debugRemote)

    companion object {
        fun getInstance(): SkillResources = service()
    }
}

inline val skillResources: SkillResources get() = service()
