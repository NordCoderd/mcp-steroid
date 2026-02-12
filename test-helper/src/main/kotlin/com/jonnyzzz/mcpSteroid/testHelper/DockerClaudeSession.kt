/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddCommand
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

    override fun registerMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        var command = claudeMcpAddCommand(mcpUrl, mcpName)
            .split(" ")

        require(command[0] == "claude")
        command = command.drop(1)
        runInContainer(args = command.toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")

        return this
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
     * Run claude in non-interactive mode with a prompt.
     *
     * Uses `--output-format stream-json --verbose` so that tool calls, assistant
     * messages, and progress events stream to stdout in real time (instead of only
     * the final text response appearing at the end). The raw NDJSON output is
     * post-processed to extract the final result text for test assertions.
     *
     * Flags align with run-agent.sh:
     * `claude -p --input-format text --output-format stream-json --verbose --tools default --permission-mode bypassPermissions`
     *
     * @param prompt The prompt to send to Claude
     * @param timeoutSeconds Maximum time to wait for the command
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): ProcessResult {
        val claudeArgs = buildList {
            // Permission mode, necessary to allow MCP
            add("--permission-mode")
            add("bypassPermissions")
            // Enable all tools including MCP (aligns with run-agent.sh)
            add("--tools")
            add("default")
            // Explicit input format (text prompt on stdin)
            add("--input-format")
            add("text")
            // Stream JSON events in real time for console visibility
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("-p")
            add(prompt)
        }
        val rawResult = runInContainer(
            *claudeArgs.toTypedArray(),
            timeoutSeconds = timeoutSeconds
        )

        // Extract the final result text from stream-json for assertion compatibility.
        // The last JSON event with "type":"result" contains the final text in "result" field.
        val resultText = extractStreamJsonResult(rawResult.output)
        return ProcessResultValue(
            exitCode = rawResult.exitCode ?: -1,
            output = resultText,
            stderr = rawResult.stderr,
            rawOutput = rawResult.output,
        )
    }

    companion object : AIAgentCompanion<DockerClaudeSession>("claude-cli") {
        const val DISPLAY_NAME = "Claude Code"

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

        /**
         * Extract the final result text from Claude's stream-json NDJSON output.
         *
         * Scans NDJSON lines for events with `"type":"result"` and extracts the
         * `"result"` field from the last such event. Uses JSON parsing for
         * reliability (handles nested objects, escaped strings, etc.) with a
         * regex fallback for edge cases.
         *
         * Also collects all `content_block_delta` text_delta fragments as a
         * secondary fallback, in case the result event is missing (e.g. timeout).
         *
         * Falls back to the raw output if no structured data can be extracted.
         */
        internal fun extractStreamJsonResult(rawOutput: String): String {
            var lastResultText: String? = null
            val textFragments = StringBuilder()

            for (line in rawOutput.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue

                // Quick pre-filter: only parse lines that mention relevant types
                if (!trimmed.contains("\"type\"")) continue

                try {
                    // Check if this is a result event by looking for "type":"result" pattern
                    // Using regex to ensure we're matching the actual type field, not "result" in other contexts
                    if (Regex(""""type"\s*:\s*"result"""").containsMatchIn(trimmed)) {
                        val resultText = extractJsonStringField(trimmed, "result")
                        if (resultText != null) {
                            lastResultText = resultText
                        }
                    }

                    // Also collect text_delta fragments as fallback
                    // Check for type":"text_delta" to ensure we're in the right event
                    if (Regex(""""type"\s*:\s*"text_delta"""").containsMatchIn(trimmed)) {
                        val text = extractJsonStringField(trimmed, "text")
                        if (text != null) {
                            textFragments.append(text)
                        }
                    }
                } catch (_: Exception) {
                    // Skip unparseable lines - this is expected for malformed JSON or partial lines
                }
            }

            // Prefer the result event text
            if (lastResultText != null) return lastResultText

            // Fall back to collected text_delta fragments
            val fragments = textFragments.toString().trim()
            if (fragments.isNotEmpty()) return fragments

            // Last resort: return raw output (may contain NDJSON)
            return rawOutput
        }

        /**
         * Extract a JSON string field value from a JSON line using regex.
         * Handles standard JSON string escaping (\\, \", \n, \r, \t, \b, \f, \/, \uXXXX).
         */
        private fun extractJsonStringField(jsonLine: String, fieldName: String): String? {
            val regex = Regex(""""${Regex.escape(fieldName)}"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val match = regex.find(jsonLine) ?: return null
            val escaped = match.groupValues[1]

            // Process escape sequences in order: backslash must be last to avoid double-processing
            return escaped
                .replace("\\b", "\b")
                .replace("\\f", "\u000C")  // form feed
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                // Unicode escapes (\uXXXX) are rare in Claude output; skip for simplicity
        }
    }
}
