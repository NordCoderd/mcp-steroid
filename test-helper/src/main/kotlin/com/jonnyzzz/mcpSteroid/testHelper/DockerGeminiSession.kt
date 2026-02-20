/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.GeminiOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import java.io.File
import java.util.Locale

/**
 * Manages a Gemini CLI session running inside a Docker container.
 */
class DockerGeminiSession(
    private val session: ContainerProcessRunner,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {
    override val displayName: String = Companion.displayName

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) : AiAgentSession {
        runInContainer(args = geminiMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")

        return this
    }

    override fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String): AiAgentSession {
        runInContainer(args = geminiMcpAddStdioArgs(npxCommand, mcpName))
            .assertExitCode(0, message = "NPX MCP server registration")
            .assertNoErrorsInOutput(message = "NPX MCP server registration")

        return this
    }

    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): ProcessResult {
        val geminiArgs = buildList {
            add("gemini")
            if (debug) {
                add("--debug")
            }
            addAll(args.toList())
        }
        val env = buildMap {
            put("GEMINI_API_KEY", apiKey)
            put("GOOGLE_API_KEY", apiKey)
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

    /**
     * Run Gemini in non-interactive mode with stream-json output enabled.
     *
     * Primary flags:
     * `--screen-reader true --sandbox-mode none --approval-mode yolo --output-format stream-json --prompt <prompt>`.
     *
     * Newer Gemini CLI versions replaced `--sandbox-mode none` with `--sandbox false`.
     * We retry once with modern syntax when the legacy flag is rejected.
     *
     * The raw NDJSON output is post-processed via [GeminiOutputFilter] to produce
     * human-readable text.
     */
    override fun runPrompt(prompt: String, timeoutSeconds: Long): ProcessResult {
        var effectiveResult = runPromptOnce(prompt, timeoutSeconds)

        // Gemini API occasionally drops the socket mid-stream (UND_ERR_SOCKET / terminated).
        // Retry once because this is an external transient failure unrelated to MCP behavior.
        if (shouldRetryTransientApiError(effectiveResult)) {
            effectiveResult = runPromptOnce(prompt, timeoutSeconds)
        }

        val resultText = outputFilter.filterText(effectiveResult.output)
        return ProcessResultValue(
            exitCode = effectiveResult.exitCode ?: -1,
            output = resultText,
            stderr = effectiveResult.stderr,
            rawOutput = effectiveResult.output,
        )
    }

    private fun runPromptOnce(prompt: String, timeoutSeconds: Long): ProcessResult {
        val rawResult = runInContainer(
            args = listOf(
                "--screen-reader", "true",
                "--sandbox-mode", "none",
                "--approval-mode", "yolo",
                "--output-format", "stream-json",
                "--prompt", prompt,
            ),
            timeoutSeconds = timeoutSeconds
        )

        val effectiveResult = if (shouldRetryWithModernSandboxFlag(rawResult)) {
            runInContainer(
                args = listOf(
                    "--screen-reader", "true",
                    "--sandbox", "false",
                    "--approval-mode", "yolo",
                    "--output-format", "stream-json",
                    "--prompt", prompt,
                ),
                timeoutSeconds = timeoutSeconds
            )
        } else {
            rawResult
        }
        return effectiveResult
    }

    private fun shouldRetryTransientApiError(result: ProcessResult): Boolean {
        if (result.exitCode == 0) return false
        val combined = (result.output + "\n" + result.stderr).lowercase(Locale.US)
        return combined.contains("api error: terminated") ||
                combined.contains("error when talking to gemini api") ||
                combined.contains("und_err_socket") ||
                combined.contains("other side closed")
    }

    private fun shouldRetryWithModernSandboxFlag(result: ProcessResult): Boolean {
        if (result.exitCode == 0) return false
        val combined = (result.output + "\n" + result.stderr).lowercase(Locale.US)
        return combined.contains("unknown arguments: sandbox-mode") ||
                combined.contains("unknown arguments: sandboxmode")
    }

    companion object : AIAgentCompanion<DockerGeminiSession>("gemini-cli") {
        override val displayName = "Gemini"
        override val outputFilter get() = GeminiOutputFilter()

        override fun readApiKey(): String {
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            System.getenv("GOOGLE_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val home = System.getProperty("user.home")
            for (filename in listOf(".vertes", ".vertex")) {
                val keyFile = File(home, filename)
                if (keyFile.exists()) {
                    val content = keyFile.readText().trim()
                    if (content.isNotBlank()) return content
                }
            }
            error("GEMINI_API_KEY required (set env GEMINI_API_KEY, GOOGLE_API_KEY, or ~/.vertes / ~/.vertex)")
        }

        override fun createImpl(session: ContainerProcessRunner, apiKey: String) = DockerGeminiSession(session, apiKey)
    }
}
