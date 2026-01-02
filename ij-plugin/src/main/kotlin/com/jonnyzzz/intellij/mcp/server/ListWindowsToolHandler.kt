/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.McpJson
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.ToolCallParams
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.SwingUtilities
import com.jonnyzzz.intellij.mcp.vision.WindowIdUtil

/**
 * Handler for the steroid_list_windows MCP tool.
 */
@Service(Service.Level.APP)
class ListWindowsToolHandler {
    fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_list_windows",
            description = "List open IDE windows and their associated projects. Use this to choose project_name for screenshot/input tools in multi-window setups.",
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
        val windowInfos = withContext(Dispatchers.EDT) {
            val frames = WindowManager.getInstance().getAllProjectFrames().toList()
            val frameInfos = frames.map { frame ->
                val project = frame.project
                val component = frame.component
                val window = SwingUtilities.getWindowAncestor(component)
                val bounds = window?.bounds

                WindowInfo(
                    projectName = project?.name,
                    projectPath = project?.basePath,
                    title = (window as? java.awt.Frame)?.title,
                    isActive = window?.isActive ?: false,
                    isVisible = window?.isVisible ?: false,
                    bounds = bounds?.let { WindowBounds(it.x, it.y, it.width, it.height) },
                    windowId = WindowIdUtil.compute(window, component)
                )
            }

            val knownWindowIds = frameInfos.map { it.windowId }.toMutableSet()
            val extraInfos = java.awt.Window.getWindows()
                .filter { it.isDisplayable }
                .mapNotNull { window ->
                    val windowId = WindowIdUtil.compute(window, window)
                    if (!knownWindowIds.add(windowId)) return@mapNotNull null
                    val bounds = window.bounds
                    WindowInfo(
                        projectName = null,
                        projectPath = null,
                        title = (window as? java.awt.Frame)?.title,
                        isActive = window.isActive,
                        isVisible = window.isVisible,
                        bounds = WindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                        windowId = windowId
                    )
                }

            frameInfos + extraInfos
        }

        val openProjects = readAction {
            ProjectManager.getInstance().openProjects.toList()
        }
        openProjects.forEach { project ->
            project.executionStorage.writeToolCall(
                toolName = "steroid_list_windows",
                arguments = params.arguments
            )
        }

        val response = ListWindowsResponse(windowInfos)
        val json = McpJson.encodeToString(ListWindowsResponse.serializer(), response)
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

@Serializable
data class ListWindowsResponse(val windows: List<WindowInfo>)

@Serializable
data class WindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val title: String?,
    val isActive: Boolean,
    val isVisible: Boolean,
    val bounds: WindowBounds?,
    val windowId: String,
)

@Serializable
data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
