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
import kotlinx.coroutines.yield
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
 * 3. Sorts dialogs by hierarchy depth (closes child dialogs first)
 * 4. Closes dialogs one at a time with message pump yielding
 * 5. Re-checks for new dialogs after each close (handles cascading dialogs)
 * 6. Captures a screenshot of the dialogs before closing (saved to execution folder)
 * 7. Logs all actions to the IDE log
 * 8. Protects against infinite loops by tracking closed dialog identities
 */
object DialogKiller {
    private val log = Logger.getInstance(DialogKiller::class.java)

    /** Maximum iterations to prevent infinite loops */
    private const val MAX_ITERATIONS = 20

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

        // Check modal state first (quick check without EDT)
        val isModalState = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            ModalityState.current() != ModalityState.nonModal()
        }

        if (!isModalState) {
            log.info("No modal dialogs detected (modality state is NON_MODAL)")
            return DialogKillerResult(dialogsClosed = 0)
        }

        log.info("Modal state detected, starting dialog killer (execution: $executionId)")

        // Get project frame (quick EDT task with ModalityState.any())
        val projectFrame = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            WindowManager.getInstance().getFrame(project)
        }

        if (projectFrame == null) {
            log.warn("Cannot find project frame for project: ${project.name}")
            return DialogKillerResult(dialogsClosed = 0)
        }

        // Track closed dialog identities to prevent infinite loops
        val closedDialogIdentities = mutableSetOf<DialogIdentity>()
        var screenshotPath: String? = null
        var screenshotError: String? = null
        var totalClosed = 0
        var iteration = 0

        try {
            // Iteratively close dialogs ONE AT A TIME until no more are found or max iterations reached
            // After each close, we re-check for dialogs to allow EDT to process side effects
            while (iteration < MAX_ITERATIONS) {
                iteration++

                // Yield to allow other coroutines to run
                yield()

                // Find and pick ONE dialog to close in a single EDT task with ModalityState.any()
                // IMPORTANT: All dialog inspection must be on EDT!
                val dialogToClose = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    val dialogs = findDialogsOwnedBy(projectFrame)

                    // Filter out dialogs we've already tried to close (must be on EDT!)
                    val newDialogs = dialogs.filter { dialog ->
                        val identity = DialogIdentity.from(dialog)
                        identity !in closedDialogIdentities
                    }

                    if (newDialogs.isEmpty()) {
                        null  // No more dialogs to close
                    } else {
                        // Sort by hierarchy depth and pick the DEEPEST one (child dialog)
                        // Closing leaf dialogs first prevents cascading closures
                        sortDialogsByDepth(newDialogs).firstOrNull()
                    }
                }

                if (dialogToClose == null) {
                    log.info("No more new dialogs found (iteration $iteration)")
                    break
                }

                // TODO: Capture screenshot on first iteration
                // Currently disabled because VisionService.capture() uses withContext(Dispatchers.EDT)
                // without ModalityState.any(), which hangs when modal dialogs are present.
                // Need to make VisionService modal-aware before re-enabling screenshot capture.
                if (false && iteration == 1) {
                    val (path, error) = captureDialogScreenshot(project, executionId)
                    screenshotPath = path
                    screenshotError = error
                }

                // Mark this dialog as processed before closing (must be on EDT for DialogIdentity.from)
                val identity = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    DialogIdentity.from(dialogToClose)
                }
                closedDialogIdentities.add(identity)

                log.info("Found dialog to close (iteration $iteration)")

                // Close the dialog in a dedicated EDT launch with ModalityState.any()
                val closed = closeDialog(dialogToClose, 1, 1, executionId)
                if (closed) {
                    totalClosed++
                }

                // Wait 500ms after closing to give EDT time to process any delayed tasks
                // This is CRITICAL - closing a dialog may schedule more EDT tasks
                // The delay allows the message pump to fully process the closure
                kotlinx.coroutines.delay(500)

                // Yield to give other coroutines a chance to run
                yield()
            }

            if (iteration >= MAX_ITERATIONS) {
                log.warn("Dialog killer stopped after $MAX_ITERATIONS iterations (infinite loop protection)")
            }

            if (screenshotPath != null) {
                log.info("Dialog screenshot saved to: $screenshotPath")
            }

            log.info("Dialog killer completed: $totalClosed dialog(s) closed in $iteration iteration(s)")

            return DialogKillerResult(
                dialogsClosed = totalClosed,
                screenshotPath = screenshotPath,
                screenshotError = screenshotError,
            )
        } catch (e: Exception) {
            log.error("Dialog killer failed: ${e.message}", e)
            return DialogKillerResult(
                dialogsClosed = totalClosed,
                screenshotPath = screenshotPath,
                screenshotError = e.message,
            )
        }
    }

    /**
     * Close a single dialog and verify closure.
     * Runs on EDT with ModalityState.any() to work even when dialogs are present.
     *
     * @return true if dialog was successfully closed
     */
    private suspend fun closeDialog(
        dialog: DialogWrapper,
        index: Int,
        total: Int,
        executionId: ExecutionId
    ): Boolean {
        return withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                val window = dialog.window
                val title = (window as? java.awt.Frame)?.title
                    ?: (window as? java.awt.Dialog)?.title
                    ?: "Unknown"

                log.warn("Closing dialog $index/$total: '$title' (execution: $executionId)")

                // Check if dialog is still showing
                val wasShowing = window?.isShowing == true
                if (!wasShowing) {
                    log.info("Dialog already hidden: '$title'")
                    return@withContext false
                }

                // Close the dialog
                dialog.doCancelAction()

                // Give UI time to process the close event
                // (Not using delay here to avoid blocking EDT)

                // Verify closure
                val stillShowing = window?.isShowing == true
                if (!stillShowing) {
                    log.info("✓ Closed dialog: '$title'")
                    true
                } else {
                    log.warn("Dialog still showing after doCancelAction(): '$title'")
                    false
                }
            } catch (e: Exception) {
                log.error("Failed to close dialog: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Find all DialogWrapper instances that are owned (directly or transitively) by the given window.
     * Only returns dialogs that are currently showing and modal.
     *
     * This runs on EDT but is kept small and fast.
     */
    private fun findDialogsOwnedBy(ownerWindow: Window): List<DialogWrapper> {
        val result = mutableListOf<DialogWrapper>()

        // Get all windows in the JVM
        val allWindows = Window.getWindows()

        for (window in allWindows) {
            // Check if this window is a DialogWrapperDialog
            if (window !is DialogWrapperDialog) continue

            // Only consider dialogs that are currently showing
            if (!window.isShowing) continue

            // Check if this window is owned by the project frame (directly or transitively)
            if (!isOwnedBy(window, ownerWindow)) continue

            // Get the DialogWrapper - handle nullable case
            val dialogWrapper = window.dialogWrapper ?: continue

            // Only include modal dialogs
            if (!dialogWrapper.isModal) continue

            result.add(dialogWrapper)
        }

        return result
    }

    /**
     * Sort dialogs by their depth in the window hierarchy.
     * Deeper dialogs (children) come first, so we close child dialogs before parent dialogs.
     *
     * This prevents issues where closing a parent dialog might cascade-close children,
     * or where a child dialog is actually a confirmation for closing the parent.
     */
    private fun sortDialogsByDepth(dialogs: List<DialogWrapper>): List<DialogWrapper> {
        return dialogs.sortedByDescending { dialog ->
            // Calculate depth: count how many owner windows we need to traverse to reach root
            var depth = 0
            var current: Window? = dialog.window
            while (current?.owner != null) {
                depth++
                current = current.owner
            }
            depth
        }
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
            // Use executionId.executionId to get the string value, not toString()
            val dialogExecutionId = ExecutionId("${executionId.executionId}-dialog-killer")

            // Capture screenshot using VisionService
            val artifacts = VisionService.capture(project, dialogExecutionId)

            // Return the screenshot path
            Pair(artifacts.imagePath.toAbsolutePath().toString(), null)
        } catch (e: Exception) {
            log.warn("Failed to capture dialog screenshot: ${e.message}", e)
            Pair(null, e.message ?: "Unknown error")
        }
    }

    /**
     * Identity of a dialog for tracking purposes.
     * Uses window hashCode and title to identify the same dialog across iterations.
     */
    private data class DialogIdentity(
        val windowHashCode: Int,
        val title: String,
    ) {
        companion object {
            fun from(dialog: DialogWrapper): DialogIdentity {
                val window = dialog.window
                val title = (window as? java.awt.Frame)?.title
                    ?: (window as? java.awt.Dialog)?.title
                    ?: ""
                return DialogIdentity(
                    windowHashCode = System.identityHashCode(window),
                    title = title,
                )
            }
        }
    }
}
