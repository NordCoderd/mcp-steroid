/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.*
import java.io.BufferedWriter
import java.util.Locale

/**
 * Filter for Gemini CLI stream-json NDJSON output.
 *
 * Converts Gemini stream-json events to human-readable console text.
 * Handles: message (assistant text), tool_use, tool_result, result, error events.
 *
 * This is used when Gemini CLI is invoked with `--output-format stream-json`.
 */
class GeminiOutputFilter : AbstractOutputFilter() {

    private val assistantTextBuffer = StringBuilder()
    private val toolNamesById = mutableMapOf<String, String>()

    override fun beforeProcessing(writer: BufferedWriter) {
        assistantTextBuffer.clear()
        toolNamesById.clear()
    }

    override fun afterProcessing(writer: BufferedWriter) {
        flushAssistantText(writer)
    }

    override fun onNonJsonLine(line: String, writer: BufferedWriter) {
        flushAssistantText(writer)
        super.onNonJsonLine(line, writer)
    }

    override fun onMalformedJson(line: String, writer: BufferedWriter) {
        flushAssistantText(writer)
        super.onMalformedJson(line, writer)
    }

    override fun processEvent(event: JsonObject, writer: BufferedWriter) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (type) {
            "message" -> {
                if (event["role"]?.jsonPrimitive?.contentOrNull == "assistant") {
                    val content = event["content"]?.jsonPrimitive?.contentOrNull
                    if (!content.isNullOrEmpty()) {
                        assistantTextBuffer.append(content)
                        if (content.endsWith("\n")) {
                            flushAssistantText(writer)
                        }
                    }
                }
            }

            "tool_use" -> {
                flushAssistantText(writer)
                val toolName = event["tool_name"]?.jsonPrimitive?.contentOrNull ?: "?"
                event["tool_id"]?.jsonPrimitive?.contentOrNull?.let { toolNamesById[it] = toolName }
                val detail = toolDetail(toolName, event["parameters"]?.jsonObject)
                writer.writeLine(">> $toolName$detail")
            }

            "tool_result" -> {
                flushAssistantText(writer)
                handleToolResult(event, writer)
            }

            "result" -> {
                flushAssistantText(writer)
                handleResultSummary(event["stats"]?.jsonObject, writer)
            }

            "error" -> {
                flushAssistantText(writer)
                val msg = formatErrorMessage(event) ?: return
                writer.writeLine(msg)
            }
        }
    }

    private fun flushAssistantText(writer: BufferedWriter) {
        val text = assistantTextBuffer.toString().trim()
        if (text.isEmpty()) return
        writer.writeLine(text)
        assistantTextBuffer.clear()
    }

    private fun handleToolResult(event: JsonObject, writer: BufferedWriter) {
        val toolId = event["tool_id"]?.jsonPrimitive?.contentOrNull
        val toolName = toolId?.let { toolNamesById[it] }
            ?: event["tool_name"]?.jsonPrimitive?.contentOrNull
            ?: toolId
            ?: "tool"

        val status = event["status"]?.jsonPrimitive?.contentOrNull
        val prefix = if (status != null && status != "success") "<< ERROR" else "<<"
        val summary = outputSummary(event["output"])

        if (summary.isNullOrEmpty()) {
            writer.writeLine("$prefix $toolName")
        } else {
            writer.writeLine("$prefix $toolName: $summary")
        }
    }

    private fun outputSummary(output: JsonElement?): String? {
        return when (output) {
            is JsonPrimitive -> {
                val value = output.contentOrNull?.trim().orEmpty()
                if (value.isEmpty()) null else firstNonBlankLine(value)
            }

            is JsonObject -> {
                val value = output["message"]?.jsonPrimitive?.contentOrNull
                    ?: output["text"]?.jsonPrimitive?.contentOrNull
                    ?: output["error"]?.jsonPrimitive?.contentOrNull
                value?.trim()?.takeIf { it.isNotEmpty() }?.let { firstNonBlankLine(it) }
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

    private fun handleResultSummary(stats: JsonObject?, writer: BufferedWriter) {
        if (stats == null) {
            writer.writeLine("[done]")
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
            writer.writeLine("[done]")
        } else {
            writer.writeLine("[done] ${parts.joinToString(" ")}")
        }
    }
}
