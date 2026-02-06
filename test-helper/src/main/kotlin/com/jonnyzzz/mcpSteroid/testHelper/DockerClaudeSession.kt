/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import java.io.File

/**
 * Manages a Claude CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Claude config.
 */
class DockerClaudeSession(
    private val session: CloseableDockerSession,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession, AutoCloseable {

    fun toAiSession() : AiAgentSession = this

    fun registerMcp(mcpUrl: String, mcpName : String) = apply {
        var command = "claude mcp add --transport http $mcpName $mcpUrl"
            .split(" ")

        require(command[0] == "claude")
        command = command.drop(1)
        runInContainer(args = command.toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")
    }

    /**
     * Run a claude command inside the Docker container.
     * Debug mode is always enabled to see MCP connection details.
     */
    fun runInContainer(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val claudeArgs = buildList {
            add("claude")
            if (debug) {
                add("--debug")
                add("--mcp-debug")
                add("--verbose")
            }
            addAll(args.toList())
        }
        return session.runInContainer(
            args = claudeArgs.toTypedArray(),
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
     * Run claude in non-interactive mode with a prompt.
     *
     * @param prompt The prompt to send to Claude
     * @param timeoutSeconds Maximum time to wait for the command
     *
     * Note: Due to a bug in Claude CLI v1.0.73 (issue #5593), when using --mcp-config,
     * we need to use "--" separator before the prompt to prevent argument parsing issues.
     * See: https://github.com/anthropics/claude-code/issues/5593
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult {
        val claudeArgs = buildList {
            // Permission mode, necessary to allow MCP
            add("--permission-mode")
            add("bypassPermissions")
            add("-p")
            add(prompt)
        }
        return runInContainer(
            *claudeArgs.toTypedArray(),
            timeoutSeconds = timeoutSeconds
        )
    }

    override fun close() {
        session.close()
    }

    companion object {
        private fun readAnthropicApiKey(): String {
            System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".anthropic")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("ANTHROPIC_API_KEY is required for Claude CLI tests (set env or ~/.anthropic)")
        }

        fun create(
            secretPatterns: List<String> = listOf(),
        ): DockerClaudeSession {
            println("[DOCKER-CLAUDE] Creating new session")
            val apiKey = readAnthropicApiKey()
            val session = DockerSession.startDockerSession("claude-cli", listOf(apiKey) + secretPatterns)
            return DockerClaudeSession(session, apiKey)
        }
    }
}
