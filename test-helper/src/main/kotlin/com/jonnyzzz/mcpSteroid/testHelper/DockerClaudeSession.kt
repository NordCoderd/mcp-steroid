/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
     * Runs a Claude command inside the Docker container.
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
     * Runs Claude in non-interactive mode with a prompt.
     *
     * Uses `--output-format stream-json --verbose` so that tool calls, assistant
     * messages, and progress events stream to stdout in real time (instead of only
     * the final text response appearing at the end). The raw NDJSON output is
     * post-processed to extract the final result text for test assertions.
     *
     * Claude CLI flags for progress visibility:
     * `claude -p --permission-mode bypassPermissions --tools default --input-format text --output-format stream-json --verbose`
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
            *claudeArgs.toTypedArray(),
            timeoutSeconds = timeoutSeconds
        )

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
        private val streamJsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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
         * Scans all NDJSON event lines and extracts the final text from the last
         * `"type":"result"` event.
         *
         * Also collects `content_block_delta`/`text_delta` fragments from streaming
         * progress events as a secondary fallback in case the result event is missing
         * (for example, a timeout).
         *
         * Falls back to the raw output if no structured data can be extracted.
         */
        internal fun extractStreamJsonResult(rawOutput: String): String {
            var lastResultText: String? = null
            val textFragments = StringBuilder()

            for (line in rawOutput.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue

                val jsonElement = try {
                    streamJsonParser.parseToJsonElement(trimmed)
                } catch (_: Exception) {
                    // Skip malformed/partial lines in streamed output.
                    continue
                }

                walkObjects(jsonElement) { jsonObject ->
                    when (jsonObject.stringField("type")) {
                        "result" -> {
                            jsonObject.stringField("result")?.let {
                                lastResultText = it
                            }
                        }

                        "text_delta" -> {
                            jsonObject.stringField("text")?.let {
                                textFragments.append(it)
                            }
                        }
                    }
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

        private fun walkObjects(element: JsonElement, block: (JsonObject) -> Unit) {
            when (element) {
                is JsonObject -> {
                    block(element)
                    element.values.forEach { walkObjects(it, block) }
                }

                is JsonArray -> {
                    element.forEach { walkObjects(it, block) }
                }

                is JsonPrimitive -> Unit
            }
        }

        private fun JsonObject.stringField(fieldName: String): String? {
            val primitive = this[fieldName] as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            return primitive.contentOrNull
        }
    }
}
