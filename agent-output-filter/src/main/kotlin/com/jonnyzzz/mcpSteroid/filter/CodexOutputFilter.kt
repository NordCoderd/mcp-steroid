/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.*
import java.io.BufferedWriter

/**
 * Filter for Codex CLI NDJSON output (--json flag).
 *
 * Converts Codex NDJSON events to human-readable console text.
 * Handles item.completed, item.started, turn.completed, and error events.
 */
class CodexOutputFilter : AbstractOutputFilter() {

    override fun processEvent(event: JsonObject, writer: BufferedWriter) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        val item = event["item"] as? JsonObject
        val itemType = item?.get("type")?.jsonPrimitive?.contentOrNull ?: ""

        when {
            type == "item.completed" && itemType == "agent_message" && item != null -> handleAgentMessage(item, writer)
            type == "item.completed" && itemType == "command_execution" && item != null -> handleCommandCompleted(item, writer)
            type == "item.completed" && itemType in setOf("tool_call", "function_call", "mcp_tool_call") && item != null ->
                handleToolCompleted(item, writer)
            type == "item.started" && itemType == "command_execution" && item != null -> handleCommandStarted(item, writer)
            type == "item.started" && itemType in setOf("tool_call", "function_call", "mcp_tool_call") && item != null ->
                handleToolStarted(item, writer)
            type == "turn.completed" -> handleTurnCompleted(event, writer)
            type == "error" -> {
                val msg = formatErrorMessage(event) ?: return
                writer.writeLine(msg)
            }
            // Silently skip: thread.started, turn.started, item.started/agent_message, etc.
        }
    }

    private fun handleAgentMessage(item: JsonObject, writer: BufferedWriter) {
        val text = item["text"]?.jsonPrimitive?.contentOrNull ?: return
        if (text.isEmpty()) return

        for (part in text.lines()) {
            val trimmed = part.trimEnd()
            if (trimmed.isNotEmpty()) {
                writer.writeLine(trimmed)
            }
        }
    }

    private fun handleCommandCompleted(item: JsonObject, writer: BufferedWriter) {
        val output = item["output"]?.jsonPrimitive?.contentOrNull ?: ""
        if (output.isNotEmpty()) {
            for (part in output.lines()) {
                val trimmed = part.trimEnd()
                if (trimmed.isNotEmpty()) {
                    writer.writeLine("  $trimmed")
                }
            }
        }

        val exitCode = item["exit_code"]?.jsonPrimitive?.intOrNull
        if (exitCode != null && exitCode != 0) {
            val cmd = item["command"]?.jsonPrimitive?.contentOrNull ?: ""
            var label = ">> exit $exitCode"
            if (cmd.isNotEmpty()) {
                label += " ($cmd)"
            }
            writer.writeLine(label)
        }
    }

    private fun handleToolCompleted(item: JsonObject, writer: BufferedWriter) {
        val name = item["name"]?.jsonPrimitive?.contentOrNull
            ?: (item["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: "?"

        // Extract output - handle both string primitives and complex objects
        val output = when (val out = item["output"] ?: item["result"]) {
            is JsonPrimitive -> out.contentOrNull ?: out.toString()
            else -> out?.toString() ?: ""
        }

        val execId = item["id"]?.jsonPrimitive?.contentOrNull ?: ""

        var label = "<< $name"
        if (execId.isNotEmpty()) {
            label += " [$execId]"
        }
        if (output.isNotEmpty()) {
            label += ": ${output.replace("\n", " ")}"
        }

        writer.writeLine(label)
    }

    private fun handleCommandStarted(item: JsonObject, writer: BufferedWriter) {
        val cmd = item["command"]?.jsonPrimitive?.contentOrNull ?: return
        if (cmd.isNotEmpty()) {
            writer.writeLine(">> $cmd")
        }
    }

    private fun handleToolStarted(item: JsonObject, writer: BufferedWriter) {
        val name = item["name"]?.jsonPrimitive?.contentOrNull
            ?: (item["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: "?"

        var inputObj = (item["input"] as? JsonObject) ?: (item["arguments"] as? JsonObject)

        // Handle string-encoded JSON
        if (inputObj == null) {
            val inputStr = item["input"]?.jsonPrimitive?.contentOrNull
                ?: item["arguments"]?.jsonPrimitive?.contentOrNull
            if (inputStr != null) {
                inputObj = try {
                    filterJson.parseToJsonElement(inputStr) as? JsonObject
                } catch (_: Exception) {
                    null
                }
            }
        }

        val detail = if (inputObj != null) toolDetail(name, inputObj) else ""
        writer.writeLine(">> $name$detail")
    }

    private fun handleTurnCompleted(event: JsonObject, writer: BufferedWriter) {
        val usage = event["usage"] as? JsonObject ?: return
        val inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0

        if (inputTokens > 0 || outputTokens > 0) {
            writer.writeLine("[turn] in=$inputTokens out=$outputTokens")
        }
    }
}
