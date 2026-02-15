/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Filter for Gemini CLI stream-json NDJSON output.
 *
 * Converts Gemini stream-json events to human-readable console text.
 * Handles: message (assistant text), tool_use, tool_result, result, error events.
 *
 * This is used when Gemini CLI is invoked with `--output-format stream-json`.
 * For text-mode output (ANSI stripping), use [GeminiFilter] instead.
 */
class GeminiStreamJsonFilter : OutputFilter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun process(input: InputStream, output: OutputStream) {
        val writer = output.bufferedWriter()
        val assistantTextBuffer = StringBuilder()
        val toolNamesById = mutableMapOf<String, String>()

        fun flushAssistantText() {
            val text = assistantTextBuffer.toString().trim()
            if (text.isEmpty()) return
            writer.write(text)
            writer.newLine()
            writer.flush()
            assistantTextBuffer.clear()
        }

        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // Non-JSON line - pass through
                if (!trimmed.startsWith('{')) {
                    flushAssistantText()
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    continue
                }

                val event = try {
                    json.parseToJsonElement(trimmed).jsonObject
                } catch (_: Exception) {
                    flushAssistantText()
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    continue
                }

                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: continue

                when (type) {
                    "message" -> {
                        if (event["role"]?.jsonPrimitive?.contentOrNull == "assistant") {
                            val content = event["content"]?.jsonPrimitive?.contentOrNull
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
                        val toolName = event["tool_name"]?.jsonPrimitive?.contentOrNull ?: "?"
                        event["tool_id"]?.jsonPrimitive?.contentOrNull?.let { toolNamesById[it] = toolName }
                        val detail = toolDetail(toolName, event["parameters"]?.jsonObject)
                        writer.write(">> $toolName$detail")
                        writer.newLine()
                        writer.flush()
                    }

                    "tool_result" -> {
                        flushAssistantText()
                        handleToolResult(event, toolNamesById, writer)
                    }

                    "result" -> {
                        flushAssistantText()
                        handleResultSummary(event["stats"]?.jsonObject, writer)
                    }

                    "error" -> {
                        flushAssistantText()
                        handleError(event, writer)
                    }
                }
            }
        }

        flushAssistantText()
        writer.flush()
    }

    private fun handleToolResult(
        event: JsonObject,
        toolNamesById: Map<String, String>,
        writer: java.io.BufferedWriter,
    ) {
        val toolId = event["tool_id"]?.jsonPrimitive?.contentOrNull
        val toolName = toolId?.let { toolNamesById[it] }
            ?: event["tool_name"]?.jsonPrimitive?.contentOrNull
            ?: toolId
            ?: "tool"

        val status = event["status"]?.jsonPrimitive?.contentOrNull
        val prefix = if (status != null && status != "success") "<< ERROR" else "<<"
        val summary = outputSummary(event["output"])

        if (summary.isNullOrEmpty()) {
            writer.write("$prefix $toolName")
        } else {
            writer.write("$prefix $toolName: $summary")
        }
        writer.newLine()
        writer.flush()
    }

    private fun outputSummary(output: JsonElement?): String? {
        return when (output) {
            is JsonPrimitive -> {
                val value = output.contentOrNull?.trim().orEmpty()
                if (value.isEmpty()) null else truncate(firstNonBlankLine(value), maxLength = 120)
            }

            is JsonObject -> {
                val value = output["message"]?.jsonPrimitive?.contentOrNull
                    ?: output["text"]?.jsonPrimitive?.contentOrNull
                    ?: output["error"]?.jsonPrimitive?.contentOrNull
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

    private fun handleResultSummary(stats: JsonObject?, writer: java.io.BufferedWriter) {
        if (stats == null) {
            writer.write("[done]")
            writer.newLine()
            writer.flush()
            return
        }

        val parts = buildList {
            val inputTokens = stats["input_tokens"]?.jsonPrimitive?.longOrNull
            val outputTokens = stats["output_tokens"]?.jsonPrimitive?.longOrNull
            if (inputTokens != null || outputTokens != null) {
                add("in=${inputTokens ?: 0} out=${outputTokens ?: 0}")
            }

            stats["tool_calls"]?.jsonPrimitive?.longOrNull?.let { add("tools=$it") }

            stats["duration_ms"]?.jsonPrimitive?.longOrNull?.let {
                val seconds = it.toDouble() / 1000.0
                add("time=${"%.1f".format(Locale.US, seconds)}s")
            }
        }

        if (parts.isEmpty()) {
            writer.write("[done]")
        } else {
            writer.write("[done] ${parts.joinToString(" ")}")
        }
        writer.newLine()
        writer.flush()
    }

    private fun handleError(event: JsonObject, writer: java.io.BufferedWriter) {
        val errorElement = event["error"]
        val errorObject = errorElement as? JsonObject

        val message = when {
            errorObject != null -> errorObject["message"]?.jsonPrimitive?.contentOrNull
                ?: event["message"]?.jsonPrimitive?.contentOrNull

            errorElement is JsonPrimitive -> errorElement.contentOrNull
            else -> event["message"]?.jsonPrimitive?.contentOrNull
        }?.trim()

        if (message.isNullOrEmpty()) return

        val errorType = errorObject?.get("type")?.jsonPrimitive?.contentOrNull
            ?: errorObject?.get("code")?.jsonPrimitive?.contentOrNull
            ?: event["code"]?.jsonPrimitive?.contentOrNull

        if (!errorType.isNullOrEmpty() && errorType != "error") {
            writer.write("[ERROR $errorType] $message")
        } else {
            writer.write("[ERROR] $message")
        }
        writer.newLine()
        writer.flush()
    }

    private fun toolDetail(toolName: String, input: JsonObject?): String {
        if (input == null) return ""
        return when (toolName) {
            "steroid_execute_code" -> {
                val reason = input["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                if (reason.isNotEmpty()) {
                    val truncated = if (reason.length > 80) reason.take(77) + "..." else reason
                    " ($truncated)"
                } else ""
            }

            "read_mcp_resource" -> {
                val uri = input["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                if (uri.isNotEmpty()) " ($uri)" else ""
            }

            "Bash", "bash", "run_shell_command" -> {
                val cmd = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
                if (cmd.isNotEmpty()) {
                    val truncated = if (cmd.length > 60) cmd.take(57) + "..." else cmd
                    " ($truncated)"
                } else ""
            }

            "read_file", "write_file", "edit_file", "replace", "Read", "read", "Edit", "edit", "Write", "write" -> {
                val path = input["file_path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (path.isNotEmpty()) " ($path)" else ""
            }

            "Grep", "grep", "Glob", "glob" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pattern.isNotEmpty()) " ($pattern)" else ""
            }

            else -> ""
        }
    }

    private fun truncate(value: String?, maxLength: Int): String? {
        if (value == null) return null
        if (value.length <= maxLength) return value
        if (maxLength <= 3) return value.take(maxLength)
        return value.take(maxLength - 3) + "..."
    }
}
