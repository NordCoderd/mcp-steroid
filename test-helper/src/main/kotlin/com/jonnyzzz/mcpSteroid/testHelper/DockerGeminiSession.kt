/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerSession
import java.io.File

/**
 * Manages a Gemini CLI session running inside a Docker container.
 */
class DockerGeminiSession(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {

    fun toAiSession(): AiAgentSession = this

    fun registerMcp(mcpUrl: String, mcpName: String) = apply {
        var command = "gemini mcp add $mcpName --type http $mcpUrl"
            .split(" ")

        require(command[0] == "gemini")
        command = command.drop(1)
        command += "--trust"

        runInContainer(args = command.toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")
    }

    fun runInContainer(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val geminiArgs = buildList {
            add("gemini")
            if (debug) {
                add("--debug")
            }
            addAll(args.toList())
        }
        val env = buildMap {
            put("GEMINI_API_KEY", apiKey)
            if (debug) {
                put("GEMINI_DEBUG", "1")
                put("MCP_DEBUG", "1")
                put("DEBUG", "*")
            }
        }

        return session.runInContainer(
            args = geminiArgs,
            timeoutSeconds = timeoutSeconds,
            extraEnvVars = env
        )
    }

    override fun runPrompt(prompt: String, timeoutSeconds: Long): ProcessResult {
        return runInContainer(
            "--yolo", // Auto-approve all tool calls
            prompt,
            timeoutSeconds = timeoutSeconds
        )
    }

    companion object : AIAgentCompanion<DockerGeminiSession>("gemini-cli") {
        override fun readApiKey(): String {
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".vertex")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("GEMINI_API_KEY required (set env or ~/.vertex)")
        }

        override fun createImpl(
            session: ContainerDriver,
            apiKey: String
        ): DockerGeminiSession {
            return DockerGeminiSession(session, apiKey)
        }
    }
}
