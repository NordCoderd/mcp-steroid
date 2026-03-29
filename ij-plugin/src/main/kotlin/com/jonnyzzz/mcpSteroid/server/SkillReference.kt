/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Application service providing MCP resource references for LLM agents.
 *
 * Usage:
 * ```kotlin
 * val skillRef = service<SkillReference>()
 * val url = skillRef.skillUrl
 * ```
 */
@Service(Service.Level.APP)
class SkillReference {

    private val mcpServer: SteroidsMcpServer
        get() = SteroidsMcpServer.getInstance()

    /**
     * Returns the SKILL.md URL from the MCP server.
     */
    @Suppress("unused")
    val skillUrl: String
        get() = mcpServer.skillUrl

    companion object {
        fun getInstance(): SkillReference = service()
    }
}
