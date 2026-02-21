/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.AIAgentCompanion
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlin.getValue

/**
 * Determines which MCP transport is registered with agents when they are created.
 *
 * Set via [AiMode] on [IntelliJContainer.create]; the factory translates the mode
 * to the appropriate [McpConnectionMode] before constructing [AiAgentDriver].
 */
sealed class McpConnectionMode {
    /** Agents are available but MCP Steroid is NOT registered. */
    data object None : McpConnectionMode()

    /** Agents connect to MCP Steroid via direct HTTP. */
    data object Http : McpConnectionMode()

    /** Agents connect to MCP Steroid via an NPX stdio proxy. */
    data class Npx(val driver: NpxSteroidDriver) : McpConnectionMode()
}

/**
 * Manages AI agent sessions (Claude, Codex, Gemini) within an IntelliJ test container.
 *
 * On construction the agent-output-filter is deployed so all agent runs can pipe
 * NDJSON output through it for human-readable logging and UI console display.
 *
 * MCP Steroid connectivity is determined by [mcpConnection]:
 * - [McpConnectionMode.None]  — no MCP registered (infrastructure / smoke tests)
 * - [McpConnectionMode.Http]  — HTTP transport ([AiMode.AI_MCP])
 * - [McpConnectionMode.Npx]   — NPX stdio proxy ([AiMode.AI_NPX])
 */
class AiAgentDriver(
    private val container: ContainerDriver,
    private val intellijDriver: IntelliJDriver,
    private val mcp: McpSteroidDriver,
    private val console: ConsoleDriver,
    private val agentsGuestDir: String,
    private val mcpConnection: McpConnectionMode = McpConnectionMode.Http,
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
            appendLine("rm -rf \"\$EXTRACT_TMP\"")
            appendLine("chmod +x /opt/agent-output-filter/bin/*")
        }
        container.writeFileInContainer("/tmp/deploy-filter.sh", deployScript, executable = true)
        val result = container.runInContainer(listOf("bash", "/tmp/deploy-filter.sh"), timeoutSeconds = 60)
        result.assertExitCode(0) {
            "agent-output-filter deployment failed:\n${result.output}\n${result.stderr}"
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

        when (val conn = mcpConnection) {
            is McpConnectionMode.None -> { /* no MCP registered */ }
            is McpConnectionMode.Http -> agent.registerHttpMcp(mcpSteroidGuestUrl, mcpSteroidName)
            is McpConnectionMode.Npx  -> agent.registerNpxMcp(conn.driver.npxCommand, mcpSteroidName)
        }

        // Wrap with console-aware session for real-time UI feedback
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
