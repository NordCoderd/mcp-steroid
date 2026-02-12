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

    override fun registerMcp(mcpUrl: String, mcpName: String): AiAgentSession {
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
     *
     * Uses `--dangerously-bypass-approvals-and-sandbox` to auto-approve all
     * tool calls and sandbox operations (matching run-agent.sh behavior).
     * Uses `--json` to stream NDJSON events to stdout for real-time console
     * visibility via the console pump filter.
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
            *codexArgs.toTypedArray(),
            timeoutSeconds = timeoutSeconds
        )

        // Extract the final result text from NDJSON for assertion compatibility.
        val resultText = extractCodexJsonResult(rawResult.output)
        return ProcessResultValue(
            exitCode = rawResult.exitCode ?: -1,
            output = resultText,
            stderr = rawResult.stderr,
            rawOutput = rawResult.output,
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

        /**
         * Extract the final result text from Codex's NDJSON output.
         *
         * Codex `--json` emits newline-delimited JSON events:
         * - `item.completed` with `item.type=agent_message`: contains `item.text`
         * - `item.completed` with `item.type=command_execution`: contains `item.output`
         * - `item.completed` with `item.type=tool_call/mcp_tool_call`: contains `item.output`
         * - `turn.completed`: contains usage/token info
         * - `error`: contains error details
         * - `thread.started`, `turn.started`: protocol bookkeeping
         *
         * We extract agent_message text as primary output for assertion compatibility,
         * plus command execution output and error events for completeness.
         * Falls back to the raw output if no structured events are found.
         */
        internal fun extractCodexJsonResult(rawOutput: String): String {
            val extracted = StringBuilder()

            // Regex for extracting "text" field value from agent_message events
            val textRegex = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            // Regex for extracting "output" field value from command_execution/tool_call events
            val outputRegex = Regex(""""output"\s*:\s*"((?:[^"\\]|\\.)*)"""")

            for (line in rawOutput.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (!trimmed.startsWith("{")) {
                    // Non-JSON line, keep as-is (could be plain text output)
                    extracted.appendLine(trimmed)
                    continue
                }

                // Try to extract meaningful content from Codex NDJSON events.
                try {
                    // item.completed with agent_message: primary agent response text
                    if (trimmed.contains("\"item.completed\"") && trimmed.contains("\"agent_message\"")) {
                        val textMatch = textRegex.find(trimmed)
                        if (textMatch != null) {
                            extracted.appendLine(unescapeJson(textMatch.groupValues[1]))
                        }
                    }
                    // item.completed with command_execution: capture command output
                    else if (trimmed.contains("\"item.completed\"") && trimmed.contains("\"command_execution\"")) {
                        val outputMatch = outputRegex.find(trimmed)
                        if (outputMatch != null) {
                            val output = unescapeJson(outputMatch.groupValues[1]).trim()
                            if (output.isNotEmpty()) {
                                extracted.appendLine(output)
                            }
                        }
                    }
                    // item.completed with tool_call/function_call/mcp_tool_call: capture tool output
                    else if (trimmed.contains("\"item.completed\"") &&
                        (trimmed.contains("\"tool_call\"") || trimmed.contains("\"function_call\"") || trimmed.contains("\"mcp_tool_call\""))) {
                        val outputMatch = outputRegex.find(trimmed)
                        if (outputMatch != null) {
                            val output = unescapeJson(outputMatch.groupValues[1]).trim()
                            if (output.isNotEmpty()) {
                                // For tool calls, only extract short outputs (likely error messages)
                                // Long outputs are usually data that clutters assertion checks
                                if (output.length < 500 || output.startsWith("[ERROR")) {
                                    extracted.appendLine(output)
                                }
                            }
                        }
                    }
                    // turn.completed: extract token usage summary for debugging/assertions
                    else if (trimmed.contains("\"type\"") && trimmed.contains("\"turn.completed\"")) {
                        val inTokensRegex = Regex(""""input_tokens"\s*:\s*(\d+)""")
                        val outTokensRegex = Regex(""""output_tokens"\s*:\s*(\d+)""")
                        val inMatch = inTokensRegex.find(trimmed)
                        val outMatch = outTokensRegex.find(trimmed)
                        if (inMatch != null || outMatch != null) {
                            val inTok = inMatch?.groupValues?.get(1) ?: "0"
                            val outTok = outMatch?.groupValues?.get(1) ?: "0"
                            extracted.appendLine("[tokens] in=$inTok out=$outTok")
                        }
                    }
                    // error events: capture error messages with type/code if available
                    else if (trimmed.contains("\"type\"") && trimmed.contains("\"error\"")
                        && !trimmed.contains("\"item.")) {
                        val msgRegex = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                        val typeRegex = Regex(""""type"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                        val codeRegex = Regex(""""code"\s*:\s*"((?:[^"\\]|\\.)*)"""")

                        val msgMatch = msgRegex.find(trimmed)
                        val typeMatch = typeRegex.find(trimmed)
                        val codeMatch = codeRegex.find(trimmed)

                        if (msgMatch != null) {
                            val msg = unescapeJson(msgMatch.groupValues[1])
                            val errorType = typeMatch?.groupValues?.get(1) ?: codeMatch?.groupValues?.get(1)
                            if (errorType != null && errorType != "error") {
                                extracted.appendLine("[ERROR $errorType] $msg")
                            } else {
                                extracted.appendLine("[ERROR] $msg")
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip unparseable JSON lines
                }
            }

            val result = extracted.toString().trim()
            return if (result.isNotEmpty()) result else rawOutput
        }

        private fun unescapeJson(s: String): String {
            // Process in correct order: \\ first, then other escapes
            var result = s
                .replace("\\\\", "\u0000") // Temporary placeholder for literal backslash
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\b", "\b")
                .replace("\\f", "\u000C")
                .replace("\\/", "/")
                .replace("\u0000", "\\") // Restore literal backslash

            // Handle unicode escapes \uXXXX
            val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
            result = unicodeRegex.replace(result) { match ->
                val codePoint = match.groupValues[1].toInt(16)
                codePoint.toChar().toString()
            }

            return result
        }
    }
}