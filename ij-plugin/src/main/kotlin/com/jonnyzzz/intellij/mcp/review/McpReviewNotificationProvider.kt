/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
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
        val reviewManager = project.service<ReviewManager>()
        if (!reviewManager.isReviewPending(file)) return null

        return Function { editor ->
            createPanel(project, file, editor)
        }
    }

    private fun createPanel(
        project: Project,
        file: VirtualFile,
        editor: FileEditor): EditorNotificationPanel {
        return EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
            text = "Review - Edit code to add comments, then Approve or Reject"

            createActionLabel("Approve & Execute") {
                project.service<ReviewManager>().approve(file)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }

            createActionLabel("Reject (send edits to LLM)") {
                project.service<ReviewManager>().reject(file)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
        }
    }
}
