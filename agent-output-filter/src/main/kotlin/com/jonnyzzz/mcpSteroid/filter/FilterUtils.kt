/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared JSON parser for all NDJSON filters.
 */
internal val filterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Extracts a human-readable detail string from tool input parameters.
 *
 * Used by all NDJSON filters to annotate tool calls with their key argument
 * (e.g. file path, command, pattern, reason).
 */
internal fun toolDetail(toolName: String, input: JsonObject?): String {
    if (input == null) return ""
    return when (toolName) {
        "steroid_execute_code" -> {
            val reason = input["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            if (reason.isNotEmpty()) " ($reason)" else ""
        }

        "read_mcp_resource" -> {
            val uri = input["uri"]?.jsonPrimitive?.contentOrNull ?: ""
            if (uri.isNotEmpty()) " ($uri)" else ""
        }

        "Bash", "bash", "run_shell_command" -> {
            val cmd = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
            if (cmd.isNotEmpty()) " ($cmd)" else ""
        }

        "read_file", "write_file", "edit_file", "replace",
        "Read", "read", "Edit", "edit", "Write", "write" -> {
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
