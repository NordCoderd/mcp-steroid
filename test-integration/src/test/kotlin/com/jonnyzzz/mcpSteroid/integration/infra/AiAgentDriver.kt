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
    private val container: ContainerDriver,
    private val intellijDriver: IntelliJDriver,
    private val mcp: McpSteroidDriver,
    private val console: ConsoleDriver,
    private val agentsGuestDir: String,
    private val connectMcpSteroid: Boolean = true,
) {
    init {
        deployAgentOutputFilter()
    }

    private val scope by lazy {
        container.withGuestWorkDir(intellijDriver.getGuestProjectDir())
    }

    val mcpSteroidHostUrl by mcp::hostMcpUrl
    val mcpSteroidGuestUrl by mcp::guestMcpUrl
    val mcpSteroidName: String = "mcp-steroid"

    private fun deployAgentOutputFilter() {
        val filterZip = IdeTestFolders.agentOutputFilterZip
        println("[AiAgentDriver] Deploying agent-output-filter from ${filterZip.absolutePath}")

        container.copyToContainer(filterZip, "/tmp/agent-output-filter.zip")

        val deployScript = buildString {
            appendLine("#!/bin/bash")
            appendLine("set -e")
            appendLine("EXTRACT_TMP=\$(mktemp -d)")
            appendLine("unzip -q /tmp/agent-output-filter.zip -d \"\$EXTRACT_TMP\"")
            appendLine("TOP_DIR=\$(ls \"\$EXTRACT_TMP\" | head -1)")
            appendLine("mkdir -p /opt")
            appendLine("mv \"\$EXTRACT_TMP/\$TOP_DIR\" /opt/agent-output-filter")
            appendLine("rm -rf \"\$EXTRACT_TMP\" /tmp/agent-output-filter.zip")
            appendLine("chmod +x /opt/agent-output-filter/bin/*")
        }
        container.writeFileInContainer("/tmp/deploy-filter.sh", deployScript, executable = true)
        val result = container.runInContainer(listOf("bash", "/tmp/deploy-filter.sh"), timeoutSeconds = 60)
        require(result.exitCode == 0) {
            "agent-output-filter deployment failed (exit ${result.exitCode}):\n${result.output}\n${result.stderr}"
        }

        println("[AiAgentDriver] agent-output-filter deployed to /opt/agent-output-filter")
    }

    private fun <R : AiAgentSession> prepareAIAgent(factory: AIAgentCompanion<R>, filterType: String): AiAgentSession {
        val session = ConsolePumpingContainerDriver(
            scope, console, factory.displayName,
            filterType = filterType,
            agentsGuestDir = agentsGuestDir,
        )

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
        prepareAIAgent(DockerClaudeSession, "claude")
    }

    val codex by lazy {
        prepareAIAgent(DockerCodexSession, "codex")
    }

    val gemini by lazy {
        prepareAIAgent(DockerGeminiSession, "gemini")
    }
}
