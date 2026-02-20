/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.CodexOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import java.io.File

/**
 * Manages a Codex CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Codex config.
 *
 * The API key is read from ~/.openai mounted into the container.
 */
class DockerCodexSession(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {
    private val userHome = "/home/codex"

    override fun registerHttpMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        runInContainer(args = codexMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")

        return this
    }

    override fun registerNpxMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        val npxCommand = session.prepareNpxProxyForUrl(mcpUrl, userHome)

        runInContainer(args = codexMcpAddStdioArgs(npxCommand, mcpName))
            .assertExitCode(0, message = "NPX MCP server registration")
            .assertNoErrorsInOutput("NPX MCP server registration")

        return this
    }

    /**
     * Run a codex command inside the Docker container.
     * Note: Codex doesn't support --verbose flag like Claude does.
     */
    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): ProcessResult {
        val codexArgs = buildList {
            add("codex")
            addAll(args)
        }
        val extraEnvVars = buildMap {
            put("OPENAI_API_KEY", apiKey)
            put("CODEX_API_KEY", apiKey)

            if (debug) {
                put("CODEX_DEBUG", "1")
                put("MCP_DEBUG", "1")
                put("DEBUG", "*")
            }
        }
        return session.runInContainer(
            codexArgs,
            timeoutSeconds = timeoutSeconds, extraEnvVars = extraEnvVars
        )
    }

    /**
     * Run codex exec for non-interactive mode.
     *
     * Codex CLI flags for auto-approval and progress visibility:
     * `codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --json <prompt>`.
     * `--json` streams NDJSON events to stdout for real-time console visibility.
     *
     * The raw NDJSON output is post-processed via [CodexOutputFilter] to produce
     * human-readable text.
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult {
        val codexArgs = buildList {
            add("exec")
            add("--dangerously-bypass-approvals-and-sandbox")
            add("--skip-git-repo-check")
            add("--json")
            add(prompt)
        }

        val rawResult = runInContainer(
            args = codexArgs,
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

    companion object : AIAgentCompanion<DockerCodexSession>("codex-cli") {
        const val DISPLAY_NAME = "Codex"
        private val outputFilter = CodexOutputFilter()

        override fun readApiKey(): String {
            System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".openai")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("OPENAI_API_KEY is required for Codex CLI tests (set env or ~/.openai)")
        }

        override fun createImpl(session: ContainerDriver,apiKey: String) = DockerCodexSession(session, apiKey)
    }
}
