/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapperDialog
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Window

/**
 * Result of dialog killer operation.
 */
data class DialogKillerResult(
    /** Number of dialogs that were closed */
    val dialogsClosed: Int,
    /** Screenshot path if dialogs were found and killed, null otherwise */
    val screenshotPath: String? = null,
    /** Error message if screenshot capture failed */
    val screenshotError: String? = null,
)

/**
 * Utility for closing pending modal dialogs before code execution.
 *
 * This helps avoid execution failures when modal dialogs are blocking the IDE.
 * The utility:
 * 1. Checks if modal state is active
 * 2. Finds all DialogWrapper instances owned by the project frame
 * 3. Calls doCancelAction() on each dialog to close it gracefully
 * 4. Captures a screenshot of the dialogs before closing (saved to execution folder)
 * 5. Logs all actions to the IDE log
 */
object DialogKiller {
    private val log = Logger.getInstance(DialogKiller::class.java)

    /**
     * Check if dialog killer is enabled via registry.
     */
    fun isEnabled(): Boolean {
        return Registry.`is`("mcp.steroid.dialog.killer.enabled", true)
    }

    /**
     * Kill all modal dialogs owned by the project frame.
     *
     * This must be called from a context where EDT access is allowed.
     * It will capture a screenshot before closing dialogs.
     *
     * @param project The project whose frame dialogs should be closed
     * @param executionId Execution ID for logging and screenshot naming
     * @return Result indicating how many dialogs were closed and screenshot path
     */
    suspend fun killProjectDialogs(
        project: Project,
        executionId: ExecutionId,
    ): DialogKillerResult {
        if (!isEnabled()) {
            log.info("Dialog killer is disabled via registry")
            return DialogKillerResult(dialogsClosed = 0)
        }

        // Use EDT with ModalityState.any() to ensure we can run even when modal dialogs are present
        return withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                // Check if we're in modal state
                val isModalState = ModalityState.current() != ModalityState.nonModal()
                if (!isModalState) {
                    log.info("No modal dialogs detected (modality state is NON_MODAL)")
                    return@withContext DialogKillerResult(dialogsClosed = 0)
                }

                log.info("Modal state detected, searching for dialogs to close (execution: $executionId)")

                // Get the project frame
                val projectFrame = WindowManager.getInstance().getFrame(project)
                if (projectFrame == null) {
                    log.warn("Cannot find project frame for project: ${project.name}")
                    return@withContext DialogKillerResult(dialogsClosed = 0)
                }

                // Find all dialog wrappers owned by the project frame
                val dialogsToClose = findDialogsOwnedBy(projectFrame)

                if (dialogsToClose.isEmpty()) {
                    log.info("No dialogs found owned by project frame: ${project.name}")
                    return@withContext DialogKillerResult(dialogsClosed = 0)
                }

                log.warn("Found ${dialogsToClose.size} dialog(s) to close for execution $executionId")

                // Capture screenshot before closing dialogs
                val (screenshotPath, screenshotError) = captureDialogScreenshot(project, executionId)

                // Close all dialogs
                dialogsToClose.forEachIndexed { index, dialog ->
                    try {
                        val window = dialog.window
                        val title = (window as? java.awt.Frame)?.title
                            ?: (window as? java.awt.Dialog)?.title
                            ?: "Unknown"

                        log.warn("Closing dialog ${index + 1}/${dialogsToClose.size}: '$title' (execution: $executionId)")
                        dialog.doCancelAction()
                        log.info("Closed dialog: '$title'")
                    } catch (e: Exception) {
                        log.error("Failed to close dialog: ${e.message}", e)
                    }
                }

                if (screenshotPath != null) {
                    log.info("Dialog screenshot saved to: $screenshotPath")
                }

                DialogKillerResult(
                    dialogsClosed = dialogsToClose.size,
                    screenshotPath = screenshotPath,
                    screenshotError = screenshotError,
                )
            } catch (e: Exception) {
                log.error("Dialog killer failed: ${e.message}", e)
                DialogKillerResult(dialogsClosed = 0, screenshotError = e.message)
            }
        }
    }

    /**
     * Find all DialogWrapper instances that are owned (directly or transitively) by the given window.
     */
    private fun findDialogsOwnedBy(ownerWindow: Window): List<DialogWrapper> {
        val result = mutableListOf<DialogWrapper>()

        // Get all windows in the JVM
        val allWindows = Window.getWindows()

        for (window in allWindows) {
            // Check if this window is a DialogWrapperDialog
            if (window !is DialogWrapperDialog) continue

            // Check if this window is owned by the project frame (directly or transitively)
            if (!isOwnedBy(window, ownerWindow)) continue

            // Get the DialogWrapper
            val dialogWrapper = window.dialogWrapper
            result.add(dialogWrapper)
        }

        return result
    }

    /**
     * Check if a window is owned (directly or transitively) by another window.
     */
    private fun isOwnedBy(window: Window, potentialOwner: Window): Boolean {
        var current: Window? = window
        while (current != null) {
            if (current == potentialOwner) {
                return true
            }
            current = current.owner
        }
        return false
    }

    /**
     * Capture a screenshot of the IDE with the dialogs visible.
     * Returns a pair of (screenshot path, error message).
     */
    private suspend fun captureDialogScreenshot(
        project: Project,
        executionId: ExecutionId,
    ): Pair<String?, String?> {
        return try {
            // Create a special execution ID for dialog screenshots
            val dialogExecutionId = ExecutionId("$executionId-dialog-killer")

            // Capture screenshot using VisionService
            val artifacts = VisionService.capture(project, dialogExecutionId)

            // Return the screenshot path
            Pair(artifacts.imagePath.toAbsolutePath().toString(), null)
        } catch (e: Exception) {
            log.warn("Failed to capture dialog screenshot: ${e.message}", e)
            Pair(null, e.message ?: "Unknown error")
        }
    }
}
