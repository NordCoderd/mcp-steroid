/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

class AiAgentDriver(
    scope: ContainerDriver,
    val intellijDriver: IntelliJDriver,
    val connectMcpSteroid: Boolean = true
) {
    private val scope by lazy {
        scope.withGuestWorkDir(intellijDriver.getGuestProjectDir())
    }

    /** Host port mapped to the MCP Steroid server inside the container. */
    val mcpSteroidHostPort: Int
        get() = scope.mapContainerPortToHostPort(IntelliJDriver.MCP_STEROID_PORT)

    val mcpSteroidHostUrl get() = "http://localhost:$mcpSteroidHostPort/mcp"
    val mcpSteroidGuestUrl get() = "http://localhost:${IntelliJDriver.MCP_STEROID_PORT.containerPort}/mcp"

    val mcpSteroidName: String = "mcp-steroid"

    private fun prepareAIAgent(agent: AiAgentSession): AiAgentSession {
        return if (connectMcpSteroid) {
            agent.registerMcp(mcpSteroidGuestUrl, mcpSteroidName)
        } else {
            agent
        }
    }

    val aiAgents: Map<String, AiAgentSession> by lazy {
        if (connectMcpSteroid) {
            waitForMcpReady()
        }

        val agents = mutableMapOf<String, AiAgentSession>()
        for ((name, factory) in agentFactories) {
            try {
                agents[name] = factory()
            } catch (e: Exception) {
                println("[IDE-AGENT] Failed to initialize agent '$name': ${e.message}")
                e.printStackTrace()
            }
        }
        require(agents.isNotEmpty()) { "At least one AI agent must initialize successfully" }
        agents
    }

    private val agentFactories: Map<String, () -> AiAgentSession> = mapOf(
        "claude" to { prepareAIAgent(DockerClaudeSession.create(scope)) },
        "codex" to { prepareAIAgent(DockerCodexSession.create(scope)) },
        "gemini" to { prepareAIAgent(DockerGeminiSession.create(scope)) },
    )

    fun waitForMcpReady() {
        val mcpInit = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

        waitFor(300_000, "Wait for MCP Steroid ready") {
            val result = scope.runInContainer(
                listOf(
                    "curl", "-s", "-f", "-X", "POST",
                    "http://localhost:${IntelliJDriver.MCP_STEROID_PORT.containerPort}/mcp",
                    "-H", "Content-Type: application/json",
                    "-d", mcpInit,
                ),
                timeoutSeconds = 5,
            )
            result.exitCode == 0
        }
        println("[IDE-AGENT] MCP Steroid is ready")
    }
}
