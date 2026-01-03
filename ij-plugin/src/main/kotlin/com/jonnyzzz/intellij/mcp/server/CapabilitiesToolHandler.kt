/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.McpJson
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.ToolCallContext
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class CapabilitiesResponse(
    val ide: IdeInfo,
    val plugins: List<PluginInfo>,
    val languages: List<LanguageInfo>,
)

@Serializable
data class IdeInfo(
    val name: String,
    val version: String,
    val build: String,
    val buildNumber: String,
    val productCode: String,
)

@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String?,
    val enabled: Boolean,
    val bundled: Boolean,
)

@Serializable
data class LanguageInfo(
    val id: String,
    val displayName: String,
)

/**
 * Handler for the steroid_capabilities MCP tool.
 */
@Service(Service.Level.APP)
class CapabilitiesToolHandler : McpRegistrar {
    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_capabilities",
            description = "List IDE capabilities such as installed plugins and registered languages.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("include_disabled_plugins") {
                        put("type", "boolean")
                        put("description", "When true, includes disabled plugins in the response (default: false).")
                    }
                }
                putJsonArray("required") { }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val includeDisabled = context.params.arguments
            ?.get("include_disabled_plugins")
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false

        val openProjects = readAction {
            ProjectManager.getInstance().openProjects.toList()
        }

        openProjects.forEach { project ->
            project.executionStorage.writeToolCall(
                toolName = "steroid_capabilities",
                arguments = context.params.arguments
            )
        }

        val ideInfo = readAction {
            val appInfo = ApplicationInfo.getInstance()
            IdeInfo(
                name = appInfo.fullApplicationName,
                version = appInfo.fullVersion,
                build = appInfo.build.asString(),
                buildNumber = appInfo.build.toString(),
                productCode = appInfo.build.productCode
            )
        }

        val plugins = readAction {
            PluginManagerCore.plugins
                .filter { includeDisabled || PluginManagerCore.isLoaded(it.pluginId) }
                .map { descriptor ->
                    val loaded = PluginManagerCore.isLoaded(descriptor.pluginId)
                    PluginInfo(
                        id = descriptor.pluginId.idString,
                        name = descriptor.name,
                        version = descriptor.version,
                        enabled = loaded,
                        bundled = descriptor.isBundled
                    )
                }
                .sortedBy { it.id }
        }

        val languages = readAction {
            Language.getRegisteredLanguages()
                .map { language ->
                    LanguageInfo(
                        id = language.id,
                        displayName = language.displayName
                    )
                }
                .sortedBy { it.id }
        }

        val response = CapabilitiesResponse(
            ide = ideInfo,
            plugins = plugins,
            languages = languages
        )

        val json = McpJson.encodeToString(CapabilitiesResponse.serializer(), response)
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}
