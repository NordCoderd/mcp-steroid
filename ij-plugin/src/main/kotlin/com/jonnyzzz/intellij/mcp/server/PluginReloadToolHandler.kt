/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.jonnyzzz.intellij.mcp.mcp.*
import com.jonnyzzz.intellij.mcp.reload.PluginReloadHelper
import com.jonnyzzz.intellij.mcp.reload.PluginReloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_plugin_info MCP tool.
 *
 * Provides information about the MCP Steroid plugin and its reload capability.
 * This is useful for debugging and understanding the plugin state.
 */
@Service(Service.Level.APP)
class PluginReloadToolHandler {

    fun register(server: McpServerCore) {
        // Register plugin info tool
        server.toolRegistry.registerTool(
            name = "steroid_plugin_info",
            description = """Get information about the MCP Steroid plugin.

Returns:
- Plugin ID, version, and installation path
- Whether the plugin can be dynamically reloaded without IDE restart
- If reload is blocked, the reason why
- Path to IDE log file for monitoring

Use this to check plugin status before attempting reload operations.""",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
                putJsonArray("required") { }
            }
        ) { _, _ ->
            handlePluginInfo()
        }

        // Register plugin reload tool
        server.toolRegistry.registerTool(
            name = "steroid_plugin_reload",
            description = """Schedule a reload of the MCP Steroid plugin.

This schedules the plugin to be unloaded and reloaded from disk, allowing
you to apply plugin updates without restarting the IDE.

IMPORTANT:
- Only works if the plugin supports dynamic reload (check with steroid_plugin_info first)
- The reload is scheduled to run after the current execution completes
- After reload, the MCP connection will be lost and needs to be re-established
- Check the IDE log file for reload status

Returns the scheduling result and path to the log file for monitoring.""",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
                putJsonArray("required") { }
            }
        ) { _, _ ->
            handlePluginReload()
        }
    }

    private fun handlePluginInfo(): ToolCallResult {
        val info = PluginReloadHelper.getPluginInfo()
        val logPath = PluginReloadHelper.getIdeaLogFile().toString()

        val response = PluginInfoResponse(
            pluginId = info.pluginId,
            isInstalled = info.isInstalled,
            isEnabled = info.isEnabled,
            version = info.version,
            pluginPath = info.pluginPath,
            canReloadWithoutRestart = info.canReloadWithoutRestart,
            reloadBlockingReason = info.reloadBlockingReason,
            logFilePath = logPath
        )

        val json = McpJson.encodeToString(PluginInfoResponse.serializer(), response)
        return ToolCallResult(content = listOf(ContentItem.Text(text = json)))
    }

    private fun handlePluginReload(): ToolCallResult {
        // First check if reload is possible
        val info = PluginReloadHelper.getPluginInfo()

        if (!info.isInstalled) {
            return ToolCallResult(
                content = listOf(ContentItem.Text(text = "Error: Plugin is not installed")),
                isError = true
            )
        }

        if (!info.canReloadWithoutRestart) {
            val response = PluginReloadResponse(
                success = false,
                message = "Plugin cannot be reloaded without restart",
                reason = info.reloadBlockingReason,
                logFilePath = PluginReloadHelper.getIdeaLogFile().toString()
            )
            val json = McpJson.encodeToString(PluginReloadResponse.serializer(), response)
            return ToolCallResult(
                content = listOf(ContentItem.Text(text = json)),
                isError = true
            )
        }

        // Schedule the reload
        val result = PluginReloader.scheduleReload()

        val (success, message, reason) = when (result) {
            is PluginReloader.ReloadResult.Success -> Triple(true, result.message, null)
            is PluginReloader.ReloadResult.Scheduled -> Triple(true, result.message, null)
            is PluginReloader.ReloadResult.Failure -> Triple(false, "Reload failed", result.reason)
        }

        val response = PluginReloadResponse(
            success = success,
            message = message,
            reason = reason,
            logFilePath = PluginReloadHelper.getIdeaLogFile().toString()
        )

        val json = McpJson.encodeToString(PluginReloadResponse.serializer(), response)
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json)),
            isError = !success
        )
    }
}

@Serializable
data class PluginInfoResponse(
    val pluginId: String,
    val isInstalled: Boolean,
    val isEnabled: Boolean,
    val version: String?,
    val pluginPath: String?,
    val canReloadWithoutRestart: Boolean,
    val reloadBlockingReason: String?,
    val logFilePath: String
)

@Serializable
data class PluginReloadResponse(
    val success: Boolean,
    val message: String,
    val reason: String?,
    val logFilePath: String
)
