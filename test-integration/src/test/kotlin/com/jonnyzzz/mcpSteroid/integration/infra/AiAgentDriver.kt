/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import kotlin.collections.iterator

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

    private fun scopeForAgent(windowTitle: String, useStreamJsonFilter: Boolean = false): ContainerDriver {
        val base = scope
        return run {
            val filterScript = if (useStreamJsonFilter) {
                ConsolePumpingContainerDriver.deployStreamJsonFilter(base)
                ConsolePumpingContainerDriver.STREAM_JSON_FILTER_PATH
            } else null
            ConsolePumpingContainerDriver(base, console, windowTitle, consoleFilterScript = filterScript)
        }
    }

    val mcpSteroidHostUrl by mcp::hostMcpUrl
    val mcpSteroidGuestUrl by mcp::guestMcpUrl
    val mcpSteroidName: String = "mcp-steroid"

    private fun prepareAIAgent(agent: AiAgentSession, displayName: String): AiAgentSession {
        if (connectMcpSteroid) {
            agent.registerMcp(mcpSteroidGuestUrl, mcpSteroidName)
        }

        // Wrap with console-aware session if console is available
        return ConsoleAwareAgentSession(agent, console, displayName)
    }

    val aiAgents: Map<String, AiAgentSession> by lazy {
        val agents = mutableMapOf<String, AiAgentSession>()
        for ((name, factory) in agentFactories) {
            agents[name] = factory()
        }
        require(agents.isNotEmpty()) { "At least one AI agent must initialize successfully" }
        agents
    }

    private val agentFactories: Map<String, () -> AiAgentSession> = mapOf(
        "claude" to { prepareAIAgent(DockerClaudeSession.create(scopeForAgent(DockerClaudeSession.DISPLAY_NAME, useStreamJsonFilter = true)), DockerClaudeSession.DISPLAY_NAME) },
        "codex" to { prepareAIAgent(DockerCodexSession.create(scopeForAgent(DockerCodexSession.DISPLAY_NAME)), DockerCodexSession.DISPLAY_NAME) },
        "gemini" to { prepareAIAgent(DockerGeminiSession.create(scopeForAgent(DockerGeminiSession.DISPLAY_NAME)), DockerGeminiSession.DISPLAY_NAME) },
    )
}
