/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import kotlin.collections.plus

private const val DEFAULT_SERVER_NAME = "mcp-steroid"

data class StdioMcpCommand(
    val command: String,
    val args: List<String> = emptyList(),
)

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

fun geminiMcpAddArgs(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--type", "http", serverUrl, "--scope", "user", "--trust")

fun codexMcpAddArgs(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--url", serverUrl)

fun claudeMcpAddArgs(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", "--transport", "http", serverName, serverUrl)

fun geminiMcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", "--type", "stdio", "--scope", "user", "--trust", serverName, command.command) + command.args

fun codexMcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--", command.command) + command.args

fun claudeMcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--", command.command) + command.args

private fun renderCommand(binary: String, args: List<String>): String =
    (listOf(binary) + args).joinToString(" ")

fun geminiMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return renderCommand("gemini", geminiMcpAddArgs(serverUrl, serverName))
}

fun codexMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return renderCommand("codex", codexMcpAddArgs(serverUrl, serverName))
}

fun claudeMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return renderCommand("claude", claudeMcpAddArgs(serverUrl, serverName))
}
