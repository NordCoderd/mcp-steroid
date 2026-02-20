/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.storage.storagePaths
import java.nio.file.Files

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

    fun buildDescriptionContent(serverUrl: String): String =
        McpConnectionInfo.build(serverUrl).toMarkdown()

    companion object {
        fun getInstance(): IdeaDescriptionWriter = service()
    }
}
