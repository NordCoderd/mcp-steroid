/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Handler for the steroid_list_projects MCP tool.
 */
@Service(Service.Level.APP)
class ListProjectsToolHandler {

    fun register(server: Server) {
        server.addTool(
            name = "steroid_list_projects",
            description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code."
        ) { _ ->
            handle()
        }
    }

    private fun handle(): CallToolResult {
        val projects = ApplicationManager.getApplication().runReadAction<List<ProjectInfo>> {
            ProjectManager.getInstance().openProjects.map { project ->
                ProjectInfo(
                    name = project.name,
                    path = project.basePath ?: ""
                )
            }
        }

        return CallToolResult(
            content = listOf(
                TextContent(text = Json.encodeToString(ListProjectsResponse.serializer(), ListProjectsResponse(projects)))
            )
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
