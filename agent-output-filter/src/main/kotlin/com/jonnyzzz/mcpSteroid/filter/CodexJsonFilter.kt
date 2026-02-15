/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream

/**
 * Filter for Codex CLI NDJSON output (--json flag).
 *
 * Converts Codex NDJSON events to human-readable console text.
 * Handles item.completed, item.started, turn.completed, and error events.
 */
class CodexJsonFilter : OutputFilter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
                    val event = json.parseToJsonElement(trimmed).jsonObject
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
            type == "error" -> handleError(event, writer)
            // Silently skip: thread.started, turn.started, item.started/agent_message, etc.
        }
    }

    private fun handleAgentMessage(item: JsonObject, writer: java.io.BufferedWriter) {
        val text = item["text"]?.jsonPrimitive?.contentOrNull ?: return
        if (text.isEmpty()) return

        for (part in text.lines()) {
            val trimmed = part.trimEnd()
            if (trimmed.isNotEmpty()) {
                writer.write(trimmed)
                writer.newLine()
            }
        }
        writer.flush()
    }

    private fun handleCommandCompleted(item: JsonObject, writer: java.io.BufferedWriter) {
        val output = item["output"]?.jsonPrimitive?.contentOrNull ?: ""
        if (output.isNotEmpty()) {
            for (part in output.lines()) {
                val trimmed = part.trimEnd()
                if (trimmed.isNotEmpty()) {
                    writer.write("  ${truncate(trimmed)}")
                    writer.newLine()
                }
            }
        }

        val exitCode = item["exit_code"]?.jsonPrimitive?.intOrNull
        if (exitCode != null && exitCode != 0) {
            val cmd = item["command"]?.jsonPrimitive?.contentOrNull ?: ""
            var label = ">> exit $exitCode"
            if (cmd.isNotEmpty()) {
                label += " (${truncate(cmd, 80)})"
            }
            writer.write(label)
            writer.newLine()
        }

        writer.flush()
    }

    private fun handleToolCompleted(item: JsonObject, writer: java.io.BufferedWriter) {
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
            val summary = truncate(output.replace("\n", " "), 120)
            label += ": $summary"
        }

        writer.write(label)
        writer.newLine()
        writer.flush()
    }

    private fun handleCommandStarted(item: JsonObject, writer: java.io.BufferedWriter) {
        val cmd = item["command"]?.jsonPrimitive?.contentOrNull ?: return
        if (cmd.isNotEmpty()) {
            writer.write(">> $cmd")
            writer.newLine()
            writer.flush()
        }
    }

    private fun handleToolStarted(item: JsonObject, writer: java.io.BufferedWriter) {
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
                    json.parseToJsonElement(inputStr) as? JsonObject
                } catch (e: Exception) {
                    null
                }
            }
        }

        val detail = if (inputObj != null) toolDetail(name, inputObj) else ""
        writer.write(">> $name$detail")
        writer.newLine()
        writer.flush()
    }

    private fun handleTurnCompleted(event: JsonObject, writer: java.io.BufferedWriter) {
        val usage = event["usage"] as? JsonObject ?: return
        val inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0

        if (inputTokens > 0 || outputTokens > 0) {
            writer.write("[turn] in=$inputTokens out=$outputTokens")
            writer.newLine()
            writer.flush()
        }
    }

    private fun handleError(event: JsonObject, writer: java.io.BufferedWriter) {
        val error = event["error"] ?: event["message"]
        val message = when (error) {
            is JsonObject -> {
                val msg = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
                val etype = error["type"]?.jsonPrimitive?.contentOrNull
                    ?: error["code"]?.jsonPrimitive?.contentOrNull
                    ?: ""
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

    private fun toolDetail(name: String, input: JsonObject): String {
        return when (name) {
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
            "Bash", "bash" -> {
                val cmd = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
                if (cmd.isNotEmpty()) {
                    val truncated = if (cmd.length > 60) cmd.take(57) + "..." else cmd
                    " ($truncated)"
                } else ""
            }
            "Read", "read" -> {
                val path = input["file_path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (path.isNotEmpty()) " ($path)" else ""
            }
            "Edit", "edit", "Write", "write" -> {
                val path = input["file_path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (path.isNotEmpty()) " ($path)" else ""
            }
            "Grep", "grep" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pattern.isNotEmpty()) " ($pattern)" else ""
            }
            "Glob", "glob" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pattern.isNotEmpty()) " ($pattern)" else ""
            }
            else -> ""
        }
    }

    private fun truncate(text: String, maxLen: Int = 200): String {
        return if (text.length <= maxLen) text else text.take(maxLen) + "..."
    }
}
