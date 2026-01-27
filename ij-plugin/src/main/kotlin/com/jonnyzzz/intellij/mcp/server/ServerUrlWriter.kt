/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Writes the MCP server URL to project .idea folders and user home directory.
 *
 * This service is responsible for creating marker files that provide
 * connection instructions to users and AI agents:
 * - Per-project: .idea/mcp-steroids.txt
 * - User home: .<pid>.mcp-steroid (PID-based marker file)
 */
@Service(Service.Level.APP)
class ServerUrlWriter : Disposable {
    private val log = thisLogger()
    private var markerFile: Path? = null

    /**
     * Write the MCP server URL to user home directory.
     * Creates a PID-based marker file that is cleaned up on IDE exit.
     *
     * @param serverUrl The MCP server URL (e.g., "http://localhost:<port>/mcp")
     */
    fun writeServerUrlToUserHome(serverUrl: String) {
        val userHome = Path.of(System.getProperty("user.home"))

        // Clean up stale marker files from dead processes
        cleanupStaleMarkerFiles(userHome)

        val pid = ProcessHandle.current().pid()
        val file = userHome.resolve(".$pid.mcp-steroid")

        val content = buildMarkerContent(serverUrl)

        try {
            Files.writeString(file, content)
            markerFile = file
            log.info("MCP Steroid marker file created: $file")
        } catch (e: Exception) {
            log.warn("Failed to create marker file in user home", e)
        }

        // Register cleanup on dispose
        Disposer.register(this) {
            try {
                markerFile?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Write the MCP server URL to a project's .idea folder.
     * Creates the mcp-steroids.txt file with connection instructions.
     *
     * @param project The project to write the URL file to
     * @param serverUrl The MCP server URL (e.g., "http://localhost:<port>/mcp")
     */
    fun writeServerUrl(project: Project, serverUrl: String) {
        try {
            val basePath = project.basePath ?: return
            val ideaDir = Path.of(basePath, ".idea")
            if (Files.exists(ideaDir)) {
                val mcpFile = ideaDir.resolve("mcp-steroids.txt")
                val content = buildMarkerContent(serverUrl)
                Files.writeString(mcpFile, content)
                log.info("MCP Steroid server URL written to: $mcpFile")
            }
        } catch (e: Exception) {
            log.error("Failed to write server URL to project folder: ${project.name}", e)
        }
    }

    private fun buildMarkerContent(serverUrl: String): String = buildString {
        appendLine(serverUrl)
        appendLine()
        appendLine("IntelliJ MCP Steroid Server")
        appendLine("URL: $serverUrl")
        appendLine()
        appendLine("Created: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine()
        append(buildIdeInfo())
        appendLine()
        appendLine("=== Quick Start ===")
        appendLine()
        appendLine("Claude Code CLI:")
        appendLine("  claude mcp add --transport http intellij-steroid $serverUrl")
        appendLine()
        appendLine("Codex CLI:")
        appendLine("  codex mcp add intellij --url $serverUrl")
        appendLine()
        appendLine("Gemini CLI:")
        appendLine("  gemini mcp add intellij-steroid --type http $serverUrl")
    }

    private fun buildIdeInfo(): String = buildString {
        val appInfo = ApplicationInfo.getInstance()
        val namesInfo = ApplicationNamesInfo.getInstance()

        appendLine("${namesInfo.fullProductName} ${appInfo.fullVersion}")
        appendLine("Build #${appInfo.build.asString()}")

        val buildDate = appInfo.buildDate
        if (buildDate != null) {
            val formatter = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
            appendLine("Built on ${formatter.format(buildDate.time)}")
        }

        appendLine()
        appendLine("Runtime: ${System.getProperty("java.runtime.version", "unknown")}")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
    }

    /**
     * Remove marker files for processes that are no longer running.
     */
    private fun cleanupStaleMarkerFiles(userHome: Path) {
        val markerPattern = Regex("\\.(\\d+)\\.mcp-steroid")

        try {
            Files.list(userHome).use { stream ->
                stream.filter { file ->
                    val match = markerPattern.find(file.fileName.toString())
                    if (match != null) {
                        val pid = match.groupValues[1].toLongOrNull()
                        pid != null && !ProcessHandle.of(pid).isPresent
                    } else {
                        false
                    }
                }.forEach { staleFile ->
                    try {
                        Files.deleteIfExists(staleFile)
                        log.info("Removed stale MCP marker file: $staleFile")
                    } catch (_: Exception) {
                        // Ignore individual file deletion errors
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup stale marker files", e)
        }
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): ServerUrlWriter = service()
    }
}
