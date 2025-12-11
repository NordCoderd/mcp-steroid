/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.review

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
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
 */
class McpReviewNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<FileEditor, JComponent?>? {
        // Check if this is a pending review file
        val path = file.path
        if (!path.contains("mcp-run/pending/") || !path.endsWith(".kt")) {
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
            text = "MCP Script Awaiting Review"

            createActionLabel("Approve & Execute") {
                reviewManager.approve(executionId)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }

            createActionLabel("Reject") {
                // Get the current code from the editor (in case user edited it)
                val document = FileDocumentManager.getInstance().getDocument(file)
                val editedCode = document?.text

                reviewManager.reject(
                    executionId = executionId,
                    reason = "Rejected by user",
                    editedCode = editedCode
                )
                EditorNotifications.getInstance(project).updateNotifications(file)
            }

            createActionLabel("Reject with Comment") {
                val document = FileDocumentManager.getInstance().getDocument(file)
                val editedCode = document?.text

                // Simple rejection - in a real implementation, you might show a dialog
                reviewManager.reject(
                    executionId = executionId,
                    reason = "Rejected with modifications",
                    editedCode = editedCode
                )
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
        }
    }
}
