/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private val userHome = "/home/codex"

    override fun registerHttpMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        runInContainer(args = codexMcpAddArgs(mcpUrl, mcpName).toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput("MCP server registration")

        return this
    }

    override fun registerNpxMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        val container = session as? ContainerDriver
            ?: error("Container driver is required for NPX registration")
        val npxCommand = container.prepareNpxProxyForUrl(mcpUrl, userHome)

        runInContainer(*codexMcpAddStdioArgs(npxCommand, mcpName).toTypedArray())
            .assertExitCode(0, message = "NPX MCP server registration")
            .assertNoErrorsInOutput("NPX MCP server registration")

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
     * Codex CLI flags for auto-approval and progress visibility:
     * `codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --json <prompt>`.
     * `--json` streams NDJSON events to stdout for real-time console visibility.
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
        private val codexJsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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
         * - `item.started` with `item.type=command_execution`: command progress
         * - `item.started` with `item.type=tool_call/function_call/mcp_tool_call`: tool progress
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

            for (line in rawOutput.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (!trimmed.startsWith("{")) {
                    // Non-JSON line, keep as-is (could be plain text output)
                    extracted.appendLine(trimmed)
                    continue
                }

                val event = parseJsonObject(trimmed) ?: continue
                when (event.stringField("type")) {
                    "item.started" -> appendStartedItem(extracted, event["item"] as? JsonObject)
                    "item.completed" -> appendCompletedItem(extracted, event["item"] as? JsonObject)
                    "turn.completed" -> appendTokenUsage(extracted, event["usage"] as? JsonObject)
                    "error" -> appendError(extracted, event)
                }
            }

            val result = extracted.toString().trim()
            return result.ifEmpty { rawOutput }
        }

        private fun appendStartedItem(extracted: StringBuilder, item: JsonObject?) {
            if (item == null) return

            when (item.stringField("type")) {
                "command_execution" -> {
                    item.stringField("command")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { extracted.appendLine(">> $it") }
                }

                "tool_call", "function_call", "mcp_tool_call" -> {
                    val name = item.stringField("name")
                        ?: (item["function"] as? JsonObject)?.stringField("name")
                        ?: "?"
                    val input = item["input"] ?: item["arguments"]
                    val detail = toolDetail(name, input)
                    extracted.appendLine(">> $name$detail")
                }
            }
        }

        private fun parseJsonObject(line: String): JsonObject? {
            val event = try {
                codexJsonParser.parseToJsonElement(line)
            } catch (_: Exception) {
                return null
            }
            return event as? JsonObject
        }

        private fun appendCompletedItem(extracted: StringBuilder, item: JsonObject?) {
            if (item == null) return

            when (item.stringField("type")) {
                "agent_message" -> {
                    item.stringField("text")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { extracted.appendLine(it) }
                }

                "command_execution" -> {
                    item.extractOutputText()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { extracted.appendLine(it) }
                }

                "tool_call", "function_call", "mcp_tool_call" -> {
                    item.extractOutputText()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { output ->
                            // Keep legacy behavior: only surface short tool outputs
                            // (or explicit error-like outputs) to avoid assertion noise.
                            if (output.length < 500 || output.startsWith("[ERROR")) {
                                extracted.appendLine(output)
                            }
                        }
                }
            }
        }

        private fun appendTokenUsage(extracted: StringBuilder, usage: JsonObject?) {
            if (usage == null) return
            val inputTokens = usage.longField("input_tokens")
            val outputTokens = usage.longField("output_tokens")
            if (inputTokens == null && outputTokens == null) return
            extracted.appendLine("[tokens] in=${inputTokens ?: 0} out=${outputTokens ?: 0}")
        }

        private fun appendError(extracted: StringBuilder, event: JsonObject) {
            val errorElement = event["error"]
            val errorObject = errorElement as? JsonObject

            val message = when {
                errorObject != null -> errorObject.stringField("message") ?: event.stringField("message")
                errorElement is JsonPrimitive -> errorElement.contentOrNull
                else -> event.stringField("message")
            }?.trim()

            if (message.isNullOrEmpty()) return

            val errorType = errorObject?.stringField("type")
                ?: errorObject?.stringField("code")
                ?: event.stringField("code")

            if (errorType != null && errorType != "error") {
                extracted.appendLine("[ERROR $errorType] $message")
            } else {
                extracted.appendLine("[ERROR] $message")
            }
        }

        private fun toolDetail(toolName: String, inputElement: JsonElement?): String {
            val input = toJsonObject(inputElement) ?: return ""
            val detailValue = when (toolName) {
                "steroid_execute_code" -> truncate(input.stringField("reason"), maxLength = 80)
                "read_mcp_resource" -> input.stringField("uri")
                "Bash", "bash" -> truncate(input.stringField("command"), maxLength = 60)
                "Read", "read", "Edit", "edit", "Write", "write" -> input.stringField("file_path")
                "Grep", "grep", "Glob", "glob" -> input.stringField("pattern")
                else -> null
            }?.trim()

            if (detailValue.isNullOrEmpty()) return ""
            return " ($detailValue)"
        }

        private fun toJsonObject(inputElement: JsonElement?): JsonObject? {
            return when (inputElement) {
                is JsonObject -> inputElement
                is JsonPrimitive -> {
                    val text = inputElement.contentOrNull ?: return null
                    val parsed = try {
                        codexJsonParser.parseToJsonElement(text)
                    } catch (_: Exception) {
                        return null
                    }
                    parsed as? JsonObject
                }

                else -> null
            }
        }

        private fun truncate(value: String?, maxLength: Int): String? {
            if (value == null) return null
            if (value.length <= maxLength) return value
            if (maxLength <= 3) return value.take(maxLength)
            return value.take(maxLength - 3) + "..."
        }

        private fun JsonObject.extractOutputText(): String? {
            val output = this["output"]?.jsonStringOrNull()
            if (output != null) return output
            return this["aggregated_output"]?.jsonStringOrNull()
        }

        private fun JsonObject.stringField(fieldName: String): String? {
            return this[fieldName].jsonStringOrNull()
        }

        private fun JsonObject.longField(fieldName: String): Long? {
            val primitive = this[fieldName] as? JsonPrimitive ?: return null
            return primitive.contentOrNull?.toLongOrNull()
        }

        private fun JsonElement?.jsonStringOrNull(): String? {
            val primitive = this as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            return primitive.contentOrNull
        }
    }
}
