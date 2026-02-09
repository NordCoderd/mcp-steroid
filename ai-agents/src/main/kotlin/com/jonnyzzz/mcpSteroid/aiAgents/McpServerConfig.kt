/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import kotlin.collections.plus

private const val DEFAULT_SERVER_NAME = "mcp-steroid"

fun genericMcpServersJson(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME) = buildString {
    appendLine("{")
    appendLine("  \"mcpServers\": {")
    appendLine("    \"$serverName\": {")
    appendLine("      \"type\": \"http\",")
    appendLine("      \"url\": \"$serverUrl\"")
    appendLine("    }")
    appendLine("  }")
    appendLine("}")
}

fun geminiMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return "gemini mcp add $serverName --type http $serverUrl --scope user --trust"
}

fun codexMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return "codex mcp add $serverName --url $serverUrl"
}

fun claudeMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return "claude mcp add --transport http $serverName $serverUrl"
}
