/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import java.io.File

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
    fun runInContainer(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val codexArgs = buildList {
            add("codex")
            addAll(args.toList())
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
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult {
        val codexArgs = buildList {
            add("exec")
            add("--skip-git-repo-check")
            add(prompt)
        }

        return runInContainer(
            *codexArgs.toTypedArray(),
            timeoutSeconds = timeoutSeconds
        )
    }

    companion object : AIAgentCompanion<DockerCodexSession>("codex-cli") {
        const val DISPLAY_NAME = "Codex"

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
