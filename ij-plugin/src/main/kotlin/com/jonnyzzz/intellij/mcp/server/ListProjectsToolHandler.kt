/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.intellij.mcp.mcp.*
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_list_projects MCP tool.
 */
@Service(Service.Level.APP)
class ListProjectsToolHandler {

    fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_list_projects",
            description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
                putJsonArray("required") { }
            }
        ) { context ->
            handle(context.params)
        }
    }

    private suspend fun handle(params: ToolCallParams): ToolCallResult {
        val openProjects = readAction {
            ProjectManager.getInstance().openProjects.toList()
        }

        val projects = openProjects.map { project ->
            ProjectInfo(
                name = project.name,
                path = project.basePath ?: ""
            )
        }

        openProjects.forEach { project ->
            project.executionStorage.writeToolCall(
                toolName = "steroid_list_projects",
                arguments = params.arguments
            )
        }

        val response = ListProjectsResponse(projects)
        val json = McpJson.encodeToString(ListProjectsResponse.serializer(), response)

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

@Serializable
data class ListProjectsResponse(val projects: List<ProjectInfo>)

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String
)
