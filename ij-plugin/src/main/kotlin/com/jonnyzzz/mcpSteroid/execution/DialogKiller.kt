/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapperDialog
import com.intellij.openapi.ui.ExitActionType
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.awt.Window
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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

suspend inline fun dialogKiller() = serviceAsync<DialogKiller>()

@Service(Service.Level.APP)
class DialogKiller {
    private val log = Logger.getInstance(DialogKiller::class.java)

    //Allow only 1 process at a time
    private val mutex = Semaphore(1)

    private suspend fun <T> withEDTAnyModalityContext(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement() + context, block)

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
        logMessage: (String) -> Unit,
    ) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return
        }

        if (!Registry.`is`("mcp.steroid.dialog.killer.enabled")) {
            return
        }

        return mutex.withPermit {
            withContext(Dispatchers.IO + CoroutineName("DialogKiller")) {
                coroutineScope {

                    val canPumpEdtNonModel = canPumpEdtNonModel()

                    if (canPumpEdtNonModel) return@coroutineScope

                    try {
                        doLookupDialogs(executionId, project, logMessage)
                    } catch (e: Throwable) {
                        if (e is ProcessCanceledException) throw e
                        log.warn("Failed to kill dialogs. ${e.message}", e)
                    }
                }
            }
        }
    }

    suspend fun canPumpEdtNonModel(): Boolean = this.runCatching {
        withTimeout(100) {
            async(Dispatchers.EDT) {
                true
            }.await()
        }
    }.getOrNull() == true

    private suspend fun doLookupDialogs(
        executionId: ExecutionId,
        project: Project,
        logMessage: (String) -> Unit,
        iteration: Int = 0,
    ) {
        if (iteration > 5) return

        log.info("Modal state detected, starting dialog killer (execution: $executionId)")

        // Get project frame (quick EDT task with ModalityState.any())
        val projectFrame = withEDTAnyModalityContext {
            WindowManager.getInstance().getFrame(project)
        } ?: return

        // Yield to allow other coroutines to run
        yield()

        // Find and pick ONE dialog to close in a single EDT task with ModalityState.any()
        // IMPORTANT: All dialog inspection must be on EDT!
        val dialogToClose = withEDTAnyModalityContext {
            findDialogsOwnedBy(projectFrame)
                .map { it to dialogDepth(it) }
                .maxByOrNull { it.second }?.first
        }

        if (dialogToClose == null) {
            log.info("No more new dialogs found (iteration $iteration)")
            return
        }

        runCatching {
            withTimeout(5_000) {
                VisionService.capture(project, executionId).logMessages().forEach { logMessage(it) }
            }
        }

        // Close the dialog in a dedicated EDT launch with ModalityState.any()
        closeDialog(dialogToClose, 1, 1, executionId)

        yield()
        doLookupDialogs(executionId, project, logMessage, iteration + 1)
    }

    /**
     * Close a single dialog and verify closure.
     * Runs on EDT with ModalityState.any() to work even when dialogs are present.
     */
    private suspend fun closeDialog(
        dialog: DialogWrapper,
        index: Int,
        total: Int,
        executionId: ExecutionId
    ) {
        withEDTAnyModalityContext {
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
                    return@withEDTAnyModalityContext false
                }

                // Close the dialog
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE, ExitActionType.CANCEL)

                //let it pump events!
                delay(10)
            } catch (e: Exception) {
                log.warn("Failed to close dialog: ${e.message}", e)
            }
        }
    }

    /**
     * Find all DialogWrapper instances that are owned (directly or transitively) by the given window.
     * Only returns dialogs that are currently showing and modal.
     *
     * This runs on EDT but is kept small and fast.
     */
    private suspend fun findDialogsOwnedBy(ownerWindow: Window): List<DialogWrapper> {
        return withEDTAnyModalityContext {
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

            result
        }
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
            dialogDepth(dialog)
        }
    }

    private fun dialogDepth(dialog: DialogWrapper): Int {
        var depth = 0
        var current: Window? = dialog.window
        while (current?.owner != null) {
            depth++
            current = current.owner
        }
        return depth
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
}
