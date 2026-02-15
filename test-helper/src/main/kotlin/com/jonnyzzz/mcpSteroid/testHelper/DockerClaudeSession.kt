/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.ClaudeStreamJsonFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import java.io.File

/**
 * Manages a Claude CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Claude config.
 */
class DockerClaudeSession(
    private val session: ContainerProcessRunner,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {
    private val userHome = "/home/claude"

    override fun registerHttpMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        runInContainer(args = claudeMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")

        return this
    }

    override fun registerNpxMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        val container = session as? ContainerDriver
            ?: error("Container driver is required for NPX registration")
        val npxCommand = container.prepareNpxProxyForUrl(mcpUrl, userHome)

        runInContainer(args = claudeMcpAddStdioArgs(npxCommand, mcpName))
            .assertExitCode(0, message = "NPX MCP server registration")
            .assertNoErrorsInOutput("NPX MCP server registration")

        return this
    }

    /**
     * Runs a Claude command inside the Docker container.
     * Debug mode is always enabled to see MCP connection details.
     */
    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): ProcessResult {
        val claudeArgs = buildList {
            add("claude")
            if (debug) {
                add("--debug")
                add("--mcp-debug")
                add("--verbose")
            }
            addAll(args)
        }
        return session.runInContainer(
            args = claudeArgs,
            timeoutSeconds = timeoutSeconds,
            extraEnvVars = buildMap {
                put("ANTHROPIC_API_KEY", apiKey)
                if (debug) {
                    put("CLAUDE_CODE_DEBUG", "1")
                    put("DEBUG", "*")
                }
            }
        )
    }

    /**
     * Runs Claude in non-interactive mode with a prompt.
     *
     * Uses `--output-format stream-json --verbose` so that tool calls, assistant
     * messages, and progress events stream to stdout in real time (instead of only
     * the final text response appearing at the end). The raw NDJSON output is
     * post-processed via [ClaudeStreamJsonFilter] to produce human-readable text.
     *
     * @param prompt The prompt to send to Claude
     * @param timeoutSeconds Maximum time to wait for the command
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult {
        val claudeArgs = buildList {
            add("--permission-mode")
            add("bypassPermissions")
            add("--tools")
            add("default")
            add("--input-format")
            add("text")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("-p")
            add(prompt)
        }
        val rawResult = runInContainer(
            args = claudeArgs,
            timeoutSeconds = timeoutSeconds
        )

        val resultText = outputFilter.filterText(rawResult.output)
        return ProcessResultValue(
            exitCode = rawResult.exitCode ?: -1,
            output = resultText,
            stderr = rawResult.stderr,
            rawOutput = rawResult.output,
        )
    }

    companion object : AIAgentCompanion<DockerClaudeSession>("claude-cli") {
        const val DISPLAY_NAME = "Claude Code"
        private val outputFilter = ClaudeStreamJsonFilter()

        override fun readApiKey(): String {
            System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".anthropic")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("ANTHROPIC_API_KEY is required for Claude CLI tests (set env or ~/.anthropic)")
        }

        override fun createImpl(session: ContainerDriver, apiKey: String): DockerClaudeSession {
            return DockerClaudeSession(session.withSecretPattern(apiKey), apiKey)
        }
    }
}
