/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream

/**
 * Filter for Claude's stream-json NDJSON output.
 *
 * Converts Claude stream-json events to human-readable console text.
 * Handles all known event types including tool_use, tool_result, text deltas, etc.
 */
class ClaudeStreamJsonFilter : OutputFilter {

    override fun process(input: InputStream, output: OutputStream) {
        val writer = output.bufferedWriter()

        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // Non-JSON line - pass through
                if (!trimmed.startsWith('{')) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    continue
                }

                try {
                    val event = filterJson.parseToJsonElement(trimmed).jsonObject
                    processEvent(event, writer)
                } catch (e: Exception) {
                    // Malformed JSON - pass through for debugging visibility
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }
            }
        }

        writer.flush()
    }

    private fun processEvent(event: JsonObject, writer: java.io.BufferedWriter) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (type) {
            "content_block_delta" -> handleContentBlockDelta(event, writer)
            "content_block_start" -> handleContentBlockStart(event, writer)
            "tool_use" -> handleToolUse(event, writer)
            "tool_result" -> handleToolResult(event, writer)
            "message_start" -> handleMessageStart(event, writer)
            "message_delta" -> handleMessageDelta(event, writer)
            "result" -> handleResult(event, writer)
            "error" -> handleError(event, writer)
            "system" -> handleSystem(event, writer)
            // Silently skip: ping, content_block_stop, message_stop
        }
    }

    private fun handleContentBlockDelta(event: JsonObject, writer: java.io.BufferedWriter) {
        val delta = event["delta"]?.jsonObject ?: return
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull ?: return

        if (deltaType == "text_delta") {
            val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: return
            if (text.isNotEmpty()) {
                writer.write(text)
                writer.flush()
            }
        }
        // input_json_delta carries partial tool input - skip (noisy JSON fragments)
    }

    private fun handleContentBlockStart(event: JsonObject, writer: java.io.BufferedWriter) {
        val contentBlock = event["content_block"]?.jsonObject ?: return
        val blockType = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: return

        if (blockType == "tool_use") {
            val name = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "?"
            val input = contentBlock["input"]?.jsonObject ?: JsonObject(emptyMap())
            val detail = toolDetail(name, input)
            writer.write("\n>> $name$detail")
            writer.newLine()
            writer.flush()
        }
        // text block start - content comes via deltas, no action needed
    }

    private fun handleToolUse(event: JsonObject, writer: java.io.BufferedWriter) {
        // Fallback for older stream-json format with standalone tool_use events
        val name = event["name"]?.jsonPrimitive?.contentOrNull ?: "?"
        val input = event["input"]?.jsonObject ?: JsonObject(emptyMap())
        val detail = toolDetail(name, input)
        writer.write(">> $name$detail")
        writer.newLine()
        writer.flush()
    }

    private fun handleToolResult(event: JsonObject, writer: java.io.BufferedWriter) {
        val isError = event["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
        val summary = toolResultSummary(event)

        val prefix = if (isError) "<< ERROR" else "<<"
        val parts = mutableListOf(prefix)
        if (summary.isNotEmpty()) {
            parts.add(summary)
        }

        writer.write(parts.joinToString(" "))
        writer.newLine()
        writer.flush()
    }

    private fun handleMessageStart(event: JsonObject, writer: java.io.BufferedWriter) {
        val message = event["message"]?.jsonObject ?: return
        val model = message["model"]?.jsonPrimitive?.contentOrNull ?: return
        if (model.isNotEmpty()) {
            writer.write("[model] $model")
            writer.newLine()
            writer.flush()
        }
    }

    private fun handleMessageDelta(event: JsonObject, writer: java.io.BufferedWriter) {
        val delta = event["delta"]?.jsonObject ?: return
        val stopReason = delta["stop_reason"]?.jsonPrimitive?.contentOrNull ?: return

        // Only show non-standard stop reasons
        if (stopReason.isNotEmpty() && stopReason != "end_turn") {
            writer.write("[stop] $stopReason")
            writer.newLine()
            writer.flush()
        }
    }

    private fun handleResult(event: JsonObject, writer: java.io.BufferedWriter) {
        // The result event carries the final answer text in the "result" field
        val resultText = event["result"]?.jsonPrimitive?.contentOrNull
        if (!resultText.isNullOrBlank()) {
            writer.write(resultText)
            if (!resultText.endsWith("\n")) {
                writer.newLine()
            }
        }

        val cost = event["cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val duration = event["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val totalCost = event["total_cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val turns = event["num_turns"]?.jsonPrimitive?.intOrNull ?: 0

        val durSeconds = duration / 1000.0
        val parts = mutableListOf<String>()

        if (cost > 0) {
            parts.add("cost=${'$'}%.4f".format(cost))
        }
        if (totalCost > 0 && totalCost != cost) {
            parts.add("total=${'$'}%.4f".format(totalCost))
        }
        if (durSeconds > 0) {
            parts.add("time=%.1fs".format(durSeconds))
        }
        if (turns > 0) {
            parts.add("turns=$turns")
        }

        if (parts.isNotEmpty()) {
            writer.write("[done] ${parts.joinToString(" ")}")
        } else {
            writer.write("[done]")
        }
        writer.newLine()
        writer.flush()
    }

    private fun handleError(event: JsonObject, writer: java.io.BufferedWriter) {
        val error = event["error"]
        val message = when (error) {
            is JsonObject -> {
                val msg = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
                val etype = error["type"]?.jsonPrimitive?.contentOrNull ?: ""
                if (etype.isNotEmpty()) {
                    "[ERROR $etype] $msg"
                } else {
                    "[ERROR] $msg"
                }
            }
            else -> "[ERROR] ${error?.toString() ?: ""}"
        }

        writer.write(message)
        writer.newLine()
        writer.flush()
    }

    private fun handleSystem(event: JsonObject, writer: java.io.BufferedWriter) {
        val message = event["message"]?.jsonPrimitive?.contentOrNull ?: return
        if (message.isNotEmpty()) {
            writer.write("[system] $message")
            writer.newLine()
            writer.flush()
        }
    }

    private fun toolResultSummary(event: JsonObject): String {
        val content = event["content"] ?: return ""

        return when (content) {
            is JsonPrimitive -> {
                val text = content.contentOrNull ?: return ""
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: ""
            }
            is JsonArray -> {
                val parts = mutableListOf<String>()
                for (block in content) {
                    if (block is JsonObject && block["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        val text = block["text"]?.jsonPrimitive?.contentOrNull ?: continue
                        text.lineSequence()
                            .map { it.trim() }
                            .firstOrNull { it.isNotEmpty() }
                            ?.let { parts.add(it) }
                        if (parts.isNotEmpty()) break
                    }
                }
                parts.take(2).joinToString("; ")
            }
            else -> ""
        }
    }
}
