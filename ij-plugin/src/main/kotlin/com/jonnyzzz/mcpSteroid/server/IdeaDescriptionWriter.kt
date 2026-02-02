/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.storage.storagePaths
import org.jsoup.internal.StringUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes the .idea/mcp-steroid.md description file in projects.
 * This file explains where MCP execution data is stored and asks users
 * to share data for plugin improvements.
 */
@Service(Service.Level.APP)
class IdeaDescriptionWriter {
    private val log = thisLogger()

    /**
     * Write the mcp-steroid.md description file to project's .idea folder.
     */
    fun writeDescriptionFile(project: Project, serverUrl: String) {
        try {
            val markerFilePath = project.storagePaths.getMarkerFilePath() ?: return
            val content = buildDescriptionContent(serverUrl)
            Files.writeString(markerFilePath, content)
        } catch (e: Exception) {
            log.warn("Failed to write MCP description file: ${project.name}", e)
        }
    }

    fun buildDescriptionContent(serverUrl: String): String = buildString {
        appendLine("# MCP Steroid Server")
        appendLine()
        appendLine("- **URL**: $serverUrl")
        appendLine("- **Generated**: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine()
        appendLine("=== Quick Start ===")
        appendLine()
        appendLine("Claude Code CLI:")
        appendLine("  " + claudeMcpAddCommand(serverUrl))
        appendLine()
        appendLine("Codex CLI:")
        appendLine("  " + codexMcpAddCommand(serverUrl))
        appendLine()
        appendLine("Gemini CLI:")
        appendLine("  " + geminiMcpAddCommand(serverUrl))
        appendLine()
        appendLine("Cursor and other's JSON config:")
        appendLine()
        appendLine("This is what `mcpServers` JSON may look like:")
        genericMcpServersJson(serverUrl).lines().forEach { append("  "); appendLine(it) }
        appendLine()
        appendLine("## Feedback")
        appendLine()
        appendLine("Report issues, Join Slack & Community: https://mcp-steroid.jonnyzzz.com")
        appendLine()
    }

    private val defaultServerName = "mcp-steroid"

    fun genericMcpServersJson(serverUrl: String, serverName: String = defaultServerName) = buildString {
        appendLine("{")
        appendLine("  \"mcpServers\": {")
        appendLine("    \"$serverName\": {")
        appendLine("      \"type\": \"http\",")
        appendLine("      \"url\": \"$serverUrl\"")
        appendLine("    }")
        appendLine("  }")
        appendLine("}")
    }

    fun geminiMcpAddCommand(serverUrl: String, serverName: String = defaultServerName): String {
        return "gemini mcp add $serverName --type http $serverUrl"
    }

    fun codexMcpAddCommand(serverUrl: String, serverName: String = defaultServerName): String {
        return "codex mcp add $serverName --url $serverUrl"
    }

    fun claudeMcpAddCommand(serverUrl: String, serverName: String = defaultServerName): String {
        return "claude mcp add --transport http $serverName $serverUrl"
    }

    companion object {
        fun getInstance(): IdeaDescriptionWriter = service()
    }
}
