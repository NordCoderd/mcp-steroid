/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.ClaudeOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.docker.builder
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
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
    override val displayName: String = Companion.displayName

    override fun registerHttpMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        runInContainer(args = claudeMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")

        return this
    }

    override fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String): AiAgentSession {
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
        val env = buildMap {
            put("ANTHROPIC_API_KEY", apiKey)
            if (debug) {
                put("CLAUDE_CODE_DEBUG", "1")
                put("DEBUG", "*")
            }
        }
        val req = ContainerProcessRunRequest
            .builder()
            .command(command = claudeArgs)
            .workingDirInContainer(null)
            .timeoutSeconds(timeoutSeconds = timeoutSeconds)
            .quietly(false)
            .description(claudeArgs.joinToString(" ").take(80))
            .secretPatterns(apiKey)
            .extraEnv(env)
            .build()

        return session.runInContainer(req)
    }

    /**
     * Runs Claude in non-interactive mode with a prompt.
     *
     * Uses `--output-format stream-json --verbose` so that tool calls, assistant
     * messages, and progress events stream to stdout in real time (instead of only
     * the final text response appearing at the end). The raw NDJSON output is
     * post-processed via [ClaudeOutputFilter] to produce human-readable text.
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

        val resultText = outputFilter.filterText(rawResult.stdout)
        return ProcessResultValue(
            exitCode = rawResult.exitCode ?: -1,
            stdout = resultText,
            stderr = rawResult.stderr,
        )
    }

    companion object : AIAgentCompanion<DockerClaudeSession>("claude-cli") {
        override val displayName = "Claude Code"
        override val outputFilter get() = ClaudeOutputFilter()

        override fun readApiKey(): String {
            System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".anthropic")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("ANTHROPIC_API_KEY is required for Claude CLI tests (set env or ~/.anthropic)")
        }

        override fun createImpl(session: ContainerProcessRunner, apiKey: String): DockerClaudeSession {
            return DockerClaudeSession(session, apiKey)
        }
    }
}
