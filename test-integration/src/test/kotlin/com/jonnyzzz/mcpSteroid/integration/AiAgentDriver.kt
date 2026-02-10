/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

class AiAgentDriver(
    container: ContainerDriver,
    val intellijDriver: IntelliJDriver,
    val connectMcpSteroid: Boolean = true,
    private val xcvbDriver: XcvbDriver,
    private val console: ConsoleDriver? = null,
) {
    private val scope by lazy {
        container.withGuestWorkDir(intellijDriver.getGuestProjectDir())
    }

    private fun scopeForAgent(windowTitle: String, useStreamJsonFilter: Boolean = false): ContainerDriver {
        val base = scope
        return if (console != null) {
            val filterScript = if (useStreamJsonFilter) {
                ConsolePumpingContainerDriver.deployStreamJsonFilter(base)
                ConsolePumpingContainerDriver.STREAM_JSON_FILTER_PATH
            } else null
            ConsolePumpingContainerDriver(base, console, windowTitle, consoleFilterScript = filterScript)
        } else {
            // Fallback: use visible xterm console
            xcvbDriver.wrapForAgentConsole(base, windowTitle)
        }
    }

    /** Host port mapped to the MCP Steroid server inside the container. */
    val mcpSteroidHostPort: Int
        get() = scope.mapContainerPortToHostPort(IntelliJDriver.MCP_STEROID_PORT)

    val mcpSteroidHostUrl get() = "http://localhost:$mcpSteroidHostPort/mcp"
    val mcpSteroidGuestUrl get() = "http://localhost:${IntelliJDriver.MCP_STEROID_PORT.containerPort}/mcp"

    val mcpSteroidName: String = "mcp-steroid"

    private fun prepareAIAgent(agent: AiAgentSession, displayName: String): AiAgentSession {
        val registered = if (connectMcpSteroid) {
            agent.registerMcp(mcpSteroidGuestUrl, mcpSteroidName)
        } else {
            agent
        }
        // Wrap with console-aware session if console is available
        return if (console != null) {
            ConsoleAwareAgentSession(registered, console, displayName)
        } else {
            registered
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
        "claude" to { prepareAIAgent(DockerClaudeSession.create(scopeForAgent(DockerClaudeSession.DISPLAY_NAME, useStreamJsonFilter = true)), DockerClaudeSession.DISPLAY_NAME) },
        "codex" to { prepareAIAgent(DockerCodexSession.create(scopeForAgent(DockerCodexSession.DISPLAY_NAME)), DockerCodexSession.DISPLAY_NAME) },
        "gemini" to { prepareAIAgent(DockerGeminiSession.create(scopeForAgent(DockerGeminiSession.DISPLAY_NAME)), DockerGeminiSession.DISPLAY_NAME) },
    )

    fun waitForMcpReady() {
        val mcpUrl = "http://localhost:${IntelliJDriver.MCP_STEROID_PORT.containerPort}/mcp"

        // First wait for the server to be reachable via a simple GET health check.
        // This avoids creating orphan sessions from repeated initialize requests.
        waitFor(300_000, "Wait for MCP Steroid ready") {
            val result = scope.runInContainer(
                listOf(
                    "curl", "-s", "-f",
                    mcpUrl,
                    "-H", "Accept: application/json",
                ),
                timeoutSeconds = 5,
            )
            result.exitCode == 0
        }

        // Verify the MCP protocol works with a proper initialize handshake
        val mcpInit = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0"}}}"""
        val result = scope.runInContainer(
            listOf(
                "curl", "-s", "-f", "-X", "POST",
                mcpUrl,
                "-H", "Content-Type: application/json",
                "-H", "Accept: application/json",
                "-d", mcpInit,
            ),
            timeoutSeconds = 10,
        )
        check(result.exitCode == 0) {
            "MCP initialize handshake failed (exit ${result.exitCode}): ${result.output}"
        }

        println("[IDE-AGENT] MCP Steroid is ready at $mcpUrl")
    }
}
