/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Writes the MCP server URL to user home directory and project description files.
 *
 * This service is responsible for creating marker files that provide
 * connection instructions to users and AI agents:
 * - Per-project: .idea/mcp-steroid.md (via IdeaDescriptionWriter)
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
        log.info("Writing MCP Steroid is ready\n$content")

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

    private fun buildMarkerContent(serverUrl: String): String = buildString {
        val plugin = PluginDescriptorProvider.getInstance()
        val appInfo = ApplicationInfo.getInstance()

        appendLine(serverUrl)
        appendLine()
        appendLine("IntelliJ MCP Steroid Server")
        appendLine("URL: $serverUrl")
        appendLine()
        appendLine("Created: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("Plugin Version: ${plugin.version}")
        appendLine("Plugin ID: ${plugin.pluginId}")
        appendLine("IDE Version: ${appInfo.fullVersion}")
        appendLine("IDE Build: ${appInfo.build.asString()}")
        appendLine()
        appendLine(IdeaDescriptionWriter.getInstance().buildDescriptionContent(serverUrl))
        appendLine()
        buildIdeInfo()
    }

    private fun StringBuilder.buildIdeInfo() {
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
        appendLine()
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
