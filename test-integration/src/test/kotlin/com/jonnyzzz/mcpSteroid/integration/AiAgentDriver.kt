/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

class McpSteroidDriver(
    val scope: ContainerDriver,

) {
    /** Host port mapped to the MCP Steroid server inside the container. */
    val mcpSteroidHostPort: Int
        get() = scope.mapContainerPortToHostPort(IntelliJDriver.MCP_STEROID_PORT)

    val mcpSteroidUrl get() = "http://127.0.0.1:$mcpSteroidHostPort"
    val mcpSteroidName: String = "mcp-steroid"

    private fun prepareAIAgent(agent: AiAgentSession): AiAgentSession = agent.registerMcp(mcpSteroidName, mcpSteroidUrl)

    val claudeCode by lazy {
        prepareAIAgent(
            DockerClaudeSession.create(scope)
        )
    }

    val codex by lazy {
        prepareAIAgent(
            DockerCodexSession.create(scope)
        )
    }

    val gemini by lazy {
        prepareAIAgent(
            DockerCodexSession.create(scope)
        )
    }

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
