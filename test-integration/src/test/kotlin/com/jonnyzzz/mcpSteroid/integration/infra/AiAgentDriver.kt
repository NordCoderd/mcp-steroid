/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.AIAgentCompanion
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import kotlin.getValue

class AiAgentDriver(
    container: ContainerDriver,
    private val intellijDriver: IntelliJDriver,
    private val mcp: McpSteroidDriver,
    private val console: ConsoleDriver,
    private val connectMcpSteroid: Boolean = true,
) {
    private val scope by lazy {
        container.withGuestWorkDir(intellijDriver.getGuestProjectDir())
    }

    val mcpSteroidHostUrl by mcp::hostMcpUrl
    val mcpSteroidGuestUrl by mcp::guestMcpUrl
    val mcpSteroidName: String = "mcp-steroid"

    private fun <R : AiAgentSession> prepareAIAgent(factory: AIAgentCompanion<R>): AiAgentSession {
        val session = ConsolePumpingContainerDriver(scope, console, factory.displayName, outputFilter = factory.outputFilter)

        val agent: AiAgentSession = factory.create(session)
        val displayName: String = factory.displayName

        if (connectMcpSteroid) {
            agent.registerHttpMcp(mcpSteroidGuestUrl, mcpSteroidName)
        }

        // Wrap with console-aware session if console is available
        return ConsoleAwareAgentSession(agent, console, displayName)
    }

    val aiAgents: Map<String, AiAgentSession> by lazy {
        mapOf(
            "claude" to claude,
            "codex" to codex,
            "gemini" to gemini,
        )
    }

    val claude by lazy {
        prepareAIAgent(DockerClaudeSession)
    }

    val codex by lazy {
        prepareAIAgent(DockerCodexSession)
    }

    val gemini by lazy {
        prepareAIAgent(DockerGeminiSession)
    }
}
