/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private val userHome = "/home/gemini"

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) : AiAgentSession {
        runInContainer(args = geminiMcpAddArgs(mcpUrl, mcpName).toTypedArray())
            .assertExitCode(0, message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")

        return this
    }

    override fun registerNpxMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        val container = session as? ContainerDriver
            ?: error("Container driver is required for NPX registration")
        val npxCommand = container.prepareNpxProxyForUrl(mcpUrl, userHome)

        runInContainer(*geminiMcpAddStdioArgs(npxCommand, mcpName).toTypedArray())
            .assertExitCode(0, message = "NPX MCP server registration")
            .assertNoErrorsInOutput(message = "NPX MCP server registration")

        return this
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
     */
    override fun runPrompt(prompt: String, timeoutSeconds: Long): ProcessResult {
        var effectiveResult = runPromptOnce(prompt, timeoutSeconds)

        // Gemini API occasionally drops the socket mid-stream (UND_ERR_SOCKET / terminated).
        // Retry once because this is an external transient failure unrelated to MCP behavior.
        if (shouldRetryTransientApiError(effectiveResult)) {
            effectiveResult = runPromptOnce(prompt, timeoutSeconds)
        }

        val resultText = extractGeminiStreamJsonResult(effectiveResult.output)
        return ProcessResultValue(
            exitCode = effectiveResult.exitCode ?: -1,
            output = resultText,
            stderr = effectiveResult.stderr,
            rawOutput = effectiveResult.output,
        )
    }

    private fun runPromptOnce(prompt: String, timeoutSeconds: Long): ProcessResult {
        val rawResult = runInContainer(
            "--screen-reader", "true",
            "--sandbox-mode", "none",
            "--approval-mode", "yolo",
            "--output-format", "stream-json",
            "--prompt", prompt,
            timeoutSeconds = timeoutSeconds
        )

        val effectiveResult = if (shouldRetryWithModernSandboxFlag(rawResult)) {
            runInContainer(
                "--screen-reader", "true",
                "--sandbox", "false",
                "--approval-mode", "yolo",
                "--output-format", "stream-json",
                "--prompt", prompt,
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
        const val DISPLAY_NAME = "Gemini"
        private val geminiJsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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

        override fun createImpl(
            session: ContainerDriver,
            apiKey: String
        ): DockerGeminiSession {
            return DockerGeminiSession(session, apiKey)
        }

        /**
         * Extract human-readable output from Gemini stream-json NDJSON output.
         *
         * The parser keeps assistant text and progress markers while preserving
         * full raw NDJSON in [ProcessResult.rawOutput].
         */
        internal fun extractGeminiStreamJsonResult(rawOutput: String): String {
            val extracted = StringBuilder()
            val assistantTextBuffer = StringBuilder()
            val toolNamesById = mutableMapOf<String, String>()

            fun flushAssistantText() {
                val text = assistantTextBuffer.toString().trim()
                if (text.isEmpty()) return
                extracted.appendLine(text)
                assistantTextBuffer.clear()
            }

            for (line in rawOutput.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (!trimmed.startsWith("{")) {
                    flushAssistantText()
                    extracted.appendLine(trimmed)
                    continue
                }

                val event = parseJsonObject(trimmed)
                if (event == null) {
                    flushAssistantText()
                    extracted.appendLine(trimmed)
                    continue
                }

                when (event.stringField("type")) {
                    "message" -> {
                        if (event.stringField("role") == "assistant") {
                            val content = event.stringField("content")
                            if (!content.isNullOrEmpty()) {
                                assistantTextBuffer.append(content)
                                if (content.endsWith("\n")) {
                                    flushAssistantText()
                                }
                            }
                        }
                    }

                    "tool_use" -> {
                        flushAssistantText()
                        val toolName = event.stringField("tool_name") ?: "?"
                        event.stringField("tool_id")?.let { toolNamesById[it] = toolName }
                        val detail = toolDetail(toolName, event["parameters"])
                        extracted.appendLine(">> $toolName$detail")
                    }

                    "tool_result" -> {
                        flushAssistantText()
                        appendToolResult(extracted, event, toolNamesById)
                    }

                    "result" -> {
                        flushAssistantText()
                        appendResultSummary(extracted, event["stats"] as? JsonObject)
                    }

                    "error" -> {
                        flushAssistantText()
                        appendError(extracted, event)
                    }
                }
            }

            flushAssistantText()
            val result = extracted.toString().trim()
            return result.ifEmpty { rawOutput }
        }

        private fun parseJsonObject(line: String): JsonObject? {
            val element = try {
                geminiJsonParser.parseToJsonElement(line)
            } catch (_: Exception) {
                return null
            }
            return element as? JsonObject
        }

        private fun appendToolResult(
            extracted: StringBuilder,
            event: JsonObject,
            toolNamesById: Map<String, String>,
        ) {
            val toolId = event.stringField("tool_id")
            val toolName = toolId?.let { toolNamesById[it] }
                ?: event.stringField("tool_name")
                ?: toolId
                ?: "tool"

            val status = event.stringField("status")
            val prefix = if (status != null && status != "success") "<< ERROR" else "<<"
            val summary = outputSummary(event["output"])

            if (summary.isNullOrEmpty()) {
                extracted.appendLine("$prefix $toolName")
            } else {
                extracted.appendLine("$prefix $toolName: $summary")
            }
        }

        private fun outputSummary(output: JsonElement?): String? {
            return when (output) {
                is JsonPrimitive -> {
                    val value = output.contentOrNull?.trim().orEmpty()
                    if (value.isEmpty()) null else truncate(firstNonBlankLine(value), maxLength = 120)
                }

                is JsonObject -> {
                    val value = output.stringField("message")
                        ?: output.stringField("text")
                        ?: output.stringField("error")
                    value?.trim()?.takeIf { it.isNotEmpty() }?.let { truncate(firstNonBlankLine(it), maxLength = 120) }
                }

                else -> null
            }
        }

        private fun firstNonBlankLine(text: String): String {
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) return trimmed
            }
            return text.trim()
        }

        private fun appendResultSummary(extracted: StringBuilder, stats: JsonObject?) {
            if (stats == null) {
                extracted.appendLine("[done]")
                return
            }

            val parts = buildList {
                val inputTokens = stats.longField("input_tokens")
                val outputTokens = stats.longField("output_tokens")
                if (inputTokens != null || outputTokens != null) {
                    add("in=${inputTokens ?: 0} out=${outputTokens ?: 0}")
                }

                stats.longField("tool_calls")?.let { add("tools=$it") }

                stats.longField("duration_ms")?.let {
                    val seconds = it.toDouble() / 1000.0
                    add("time=${"%.1f".format(Locale.US, seconds)}s")
                }
            }

            if (parts.isEmpty()) {
                extracted.appendLine("[done]")
            } else {
                extracted.appendLine("[done] ${parts.joinToString(" ")}")
            }
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

            if (!errorType.isNullOrEmpty() && errorType != "error") {
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
                "Bash", "bash", "run_shell_command" -> truncate(input.stringField("command"), maxLength = 60)
                "read_file", "write_file", "edit_file", "replace", "Read", "read", "Edit", "edit", "Write", "write" -> input.stringField("file_path")
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
                    val content = inputElement.contentOrNull ?: return null
                    val parsed = try {
                        geminiJsonParser.parseToJsonElement(content)
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

        private fun JsonObject.stringField(fieldName: String): String? {
            val primitive = this[fieldName] as? JsonPrimitive ?: return null
            return primitive.contentOrNull
        }

        private fun JsonObject.longField(fieldName: String): Long? {
            val primitive = this[fieldName] as? JsonPrimitive ?: return null
            return primitive.contentOrNull?.toLongOrNull()
        }
    }
}
