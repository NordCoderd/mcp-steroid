/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.review

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a notification panel in the editor when viewing code pending review.
 * Provides Approve and Reject buttons.
 *
 * The panel encourages users to edit the code to add comments or modifications
 * before rejecting - these edits will be sent back to the LLM.
 */
class McpReviewNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<FileEditor, JComponent?>? {
        // Check if this is a review file: .idea/mcp-run/{execution-id}/review.kts
        val path = file.path
        if (!path.contains("mcp-run/") || !path.endsWith("/review.kts")) {
            return null
        }

        val reviewManager = project.service<ReviewManager>()
        val executionId = reviewManager.getExecutionIdFromPath(path) ?: return null

        // Only show if there's actually a pending review
        if (!reviewManager.hasPendingReview(executionId)) {
            return null
        }

        return Function { editor ->
            createPanel(project, file, editor, executionId, reviewManager)
        }
    }

    private fun createPanel(
        project: Project,
        file: VirtualFile,
        editor: FileEditor,
        executionId: String,
        reviewManager: ReviewManager
    ): EditorNotificationPanel {
        return EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
            text = "MCP Script Review - Edit code to add comments, then Approve or Reject"

            createActionLabel("Approve & Execute") {
                reviewManager.approve(executionId)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }

            createActionLabel("Reject (send edits to LLM)") {
                // The reject action will capture any edits made to the code
                // and return them with a diff to the LLM
                reviewManager.reject(executionId)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
        }
    }
}
