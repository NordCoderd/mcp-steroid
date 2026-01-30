/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.jonnyzzz.intellij.mcp.mcp.*
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import com.jonnyzzz.intellij.mcp.vision.WindowIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.SwingUtilities

/**
 * Handler for the steroid_list_windows MCP tool.
 */
class ListWindowsToolHandler : McpRegistrar {
    override fun register(server: McpServerCore) {
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
        val (windowInfos, progressTasks) = withContext(Dispatchers.EDT) {
            // Check if any modal dialog is showing in the IDE
            // Compare current modality state with nonModal - if different, modal is showing
            val isModalShowing = ModalityState.current() != ModalityState.nonModal()

            val frames = WindowManager.getInstance().allProjectFrames.toList()

            // Collect progress indicators from all frames
            val allProgressTasks = mutableListOf<ProgressTaskInfo>()

            val frameInfos = frames.map { frame ->
                val project = frame.project
                val component = frame.component
                val window = SwingUtilities.getWindowAncestor(component)
                val bounds = window?.bounds

                // Collect progress tasks from the status bar using new ProgressModel API
                val statusBar = frame.statusBar as? StatusBarEx
                statusBar?.let { bar ->
                    val tasks = bar.backgroundProcessModels
                    tasks.forEach { pair ->
                        val taskInfo = pair.first
                        val progressModel = pair.second
                        allProgressTasks.add(
                            ProgressTaskInfo(
                                title = taskInfo.title,
                                text = progressModel.getText() ?: "",
                                text2 = progressModel.getDetails() ?: "",
                                fraction = if (progressModel.isIndeterminate()) null else progressModel.getFraction(),
                                isIndeterminate = progressModel.isIndeterminate(),
                                isCancellable = progressModel.isCancellable(),
                                projectName = project?.name
                            )
                        )
                    }
                }

                WindowInfo(
                    projectName = project?.name,
                    projectPath = project?.basePath,
                    title = (window as? java.awt.Frame)?.title,
                    isActive = window?.isActive ?: false,
                    isVisible = window?.isVisible ?: false,
                    bounds = bounds?.let { WindowBounds(it.x, it.y, it.width, it.height) },
                    windowId = WindowIdUtil.compute(window, component),
                    modalDialogShowing = isModalShowing,
                    indexingInProgress = project?.let { DumbService.isDumb(it) },
                    projectInitialized = project?.isInitialized,
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
                        windowId = windowId,
                        modalDialogShowing = isModalShowing,
                    )
                }

            (frameInfos + extraInfos) to allProgressTasks.toList()
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

        val response = ListWindowsResponse(
            windows = windowInfos,
            backgroundTasks = progressTasks.ifEmpty { null }
        )
        val json = McpJson.encodeToString(ListWindowsResponse.serializer(), response)
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

@Serializable
data class ListWindowsResponse(
    val windows: List<WindowInfo>,
    /** List of background tasks currently running in the IDE (null if none) */
    val backgroundTasks: List<ProgressTaskInfo>? = null
)

@Serializable
data class WindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val title: String?,
    val isActive: Boolean,
    val isVisible: Boolean,
    val bounds: WindowBounds?,
    val windowId: String,
    /** True if a modal dialog is currently showing in the IDE */
    val modalDialogShowing: Boolean = false,
    /** True if the project is currently indexing (dumb mode) */
    val indexingInProgress: Boolean? = null,
    /** True if the project has been fully initialized */
    val projectInitialized: Boolean? = null,
)

@Serializable
data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Information about a background task/progress indicator */
@Serializable
data class ProgressTaskInfo(
    /** Task title (e.g., "Indexing", "Building") */
    val title: String,
    /** Current status text */
    val text: String,
    /** Secondary status text */
    val text2: String,
    /** Progress fraction (0.0 to 1.0), null if indeterminate */
    val fraction: Double?,
    /** True if progress is indeterminate (no percentage) */
    val isIndeterminate: Boolean,
    /** True if the task can be canceled */
    val isCancellable: Boolean,
    /** Project name this task belongs to (if known) */
    val projectName: String?
)
