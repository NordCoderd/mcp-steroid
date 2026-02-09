/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import java.io.File

/**
 * Manages a Gemini CLI session running inside a Docker container.
 */
class DockerGeminiSession(
    private val session: ContainerProcessRunner,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {
    private fun buildAgentEnv(visibleConsole: Boolean): Map<String, String> {
        return buildMap {
            put("GEMINI_API_KEY", apiKey)
            put("GOOGLE_API_KEY", apiKey)
            if (debug) {
                put("GEMINI_DEBUG", "1")
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

    override fun registerMcp(mcpUrl: String, mcpName: String) : AiAgentSession {
        var command = geminiMcpAddCommand(mcpUrl, mcpName)
            .split(" ")

        require(command[0] == "gemini")
        command = command.drop(1)
        command += "--scope"
        command += "user"
        command += "--trust"

        runInContainer(args = command.toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")

        return this
    }

    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 120,
        visibleConsole: Boolean = false,
    ): ProcessResult {
        val geminiArgs = buildList {
            add("gemini")
            if (debug) {
                add("--debug")
            }
            addAll(args.toList())
        }
        return session.runInContainer(
            args = geminiArgs,
            timeoutSeconds = timeoutSeconds,
            extraEnvVars = buildAgentEnv(visibleConsole = visibleConsole),
        )
    }

    override fun runPrompt(prompt: String, timeoutSeconds: Long): ProcessResult {
        return runInContainer(
            "--yolo", // Auto-approve all tool calls
            prompt,
            visibleConsole = true,
            timeoutSeconds = timeoutSeconds
        )
    }

    companion object : AIAgentCompanion<DockerGeminiSession>("gemini-cli") {
        const val DISPLAY_NAME = "Gemini"
        private const val VISIBLE_CONSOLE_ENV = "MCP_STEROID_VISIBLE_CONSOLE"

        override fun readApiKey(): String {
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            listOf(".vertes", ".vertex").forEach { filename ->
                val keyFile = File(System.getProperty("user.home"), filename)
                if (keyFile.exists()) {
                    val content = keyFile.readText().trim()
                    if (content.isNotBlank()) return content
                }
            }
            error("GEMINI_API_KEY required (set env or ~/.vertes or ~/.vertex)")
        }

        override fun createImpl(
            session: ContainerDriver,
            apiKey: String
        ): DockerGeminiSession {
            return DockerGeminiSession(session, apiKey)
        }
    }
}
