/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import java.io.File
import java.util.Base64

/**
 * Manages a Codex CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Codex config.
 *
 * The API key is read from ~/.openai mounted into the container.
 */
class DockerCodexSession(
    private val session: ContainerProcessRunner,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {
    private fun buildAgentEnv(visibleConsole: Boolean): Map<String, String> {
        return buildMap {
            put("OPENAI_API_KEY", apiKey)
            put("CODEX_API_KEY", apiKey)

            if (debug) {
                put("CODEX_DEBUG", "1")
                put("MCP_DEBUG", "1")
                put("DEBUG", "*")
            }
            if (visibleConsole) {
                put(VISIBLE_CONSOLE_ENV, "1")
            }
            put("RUNS_DIR", "/tmp/agent-runs")
            put("MESSAGE_BUS", "/tmp/MESSAGE-BUS.md")
        }
    }

    override fun registerMcp(mcpUrl: String, mcpName: String) : AiAgentSession{
        var command = codexMcpAddCommand(mcpUrl, mcpName)
            .split(" ")

        require(command[0] == "codex")
        command = command.drop(1)
        runInContainer(args = command.toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")

        return this
    }

    /**
     * Run a codex command inside the Docker container.
     * Note: Codex doesn't support --verbose flag like Claude does.
     */
    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 120,
        visibleConsole: Boolean = false,
    ): ProcessResult {
        val codexArgs = buildList {
            add("codex")
            addAll(args.toList())
        }
        return session.runInContainer(
            codexArgs,
            timeoutSeconds = timeoutSeconds,
            extraEnvVars = buildAgentEnv(visibleConsole = visibleConsole),
        )
    }

    private fun runPromptWithUnifiedRunner(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult? {
        val env = buildAgentEnv(visibleConsole = false)
        val hasRunner = session.runInContainer(
            listOf("bash", "-lc", "command -v run-agent.sh >/dev/null 2>&1"),
            timeoutSeconds = 5,
            extraEnvVars = env,
        ).exitCode == 0
        if (!hasRunner) return null

        val promptPath = "/tmp/agent-prompt-codex-${System.currentTimeMillis()}.md"
        val encodedPrompt = Base64.getEncoder().encodeToString(prompt.toByteArray(Charsets.UTF_8))
        val writePromptScript = "echo '$encodedPrompt' | base64 -d > '$promptPath'"
        session.runInContainer(
            listOf("bash", "-lc", writePromptScript),
            timeoutSeconds = 10,
            extraEnvVars = buildAgentEnv(visibleConsole = false),
        )
        return session.runInContainer(
            args = listOf("bash", "-lc", "run-agent.sh codex . $promptPath"),
            timeoutSeconds = timeoutSeconds,
            extraEnvVars = buildAgentEnv(visibleConsole = true),
        )
    }

    /**
     * Run codex exec for non-interactive mode.
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult {
        runPromptWithUnifiedRunner(prompt, timeoutSeconds)?.let { return it }

        val codexArgs = buildList {
            add("exec")
            add("--skip-git-repo-check")
            add(prompt)
        }

        return runInContainer(
            *codexArgs.toTypedArray(),
            visibleConsole = true,
            timeoutSeconds = timeoutSeconds
        )
    }

    companion object : AIAgentCompanion<DockerCodexSession>("codex-cli") {
        const val DISPLAY_NAME = "Codex"
        private const val VISIBLE_CONSOLE_ENV = "MCP_STEROID_VISIBLE_CONSOLE"

        override fun readApiKey(): String {
            System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".openai")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("OPENAI_API_KEY is required for Codex CLI tests (set env or ~/.openai)")
        }

        override fun createImpl(
            session: ContainerDriver,
            apiKey: String
        ): DockerCodexSession {
            return DockerCodexSession(session, apiKey)
        }
    }
}
