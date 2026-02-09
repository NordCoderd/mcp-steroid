/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import javax.swing.JLabel
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for DialogKiller functionality.
 *
 * Verifies that modal dialogs can be detected and closed before code execution.
 */
class DialogKillerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Disable review mode for tests
        setSystemPropertyForTest("mcp.steroid.review.mode", "NEVER")
        // Ensure dialog killer is enabled
        setSystemPropertyForTest("mcp.steroid.dialog.killer.enabled", "true")
    }

    fun testDialogKillerIsEnabled(): Unit = timeoutRunBlocking(10.seconds) {
        assertTrue("Dialog killer should be enabled", DialogKiller.isEnabled())
    }

    fun testDialogKillerWithNoDialogs(): Unit = timeoutRunBlocking(10.seconds) {
        val result = DialogKiller.killProjectDialogs(
            project = project,
            executionId = ExecutionId("test-no-dialogs"),
        )

        // Should find no dialogs
        assertEquals("Should find no dialogs", 0, result.dialogsClosed)
        assertNull("Should have no screenshot", result.screenshotPath)
    }

    fun testDialogKillerWithModalDialog(): Unit = timeoutRunBlocking(30.seconds) {
        // Create a test modal dialog
        val dialog = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            object : DialogWrapper(project, true) {
                init {
                    title = "Test Modal Dialog"
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    return JLabel("This is a test modal dialog")
                }
            }
        }

        try {
            // Show the dialog in the background (non-blocking)
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                dialog.show()
            }

            // Give the dialog time to appear
            delay(500)

            // Now kill dialogs
            val result = DialogKiller.killProjectDialogs(
                project = project,
                executionId = ExecutionId("test-kill-dialog"),
            )

            // Should find and close the dialog
            assertTrue("Should find at least one dialog", result.dialogsClosed > 0)

            // Screenshot should be captured
            assertNotNull("Should have screenshot path", result.screenshotPath)
            assertTrue("Screenshot path should not be empty", result.screenshotPath!!.isNotEmpty())

            // Verify dialog is closed
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                assertFalse("Dialog should be closed", dialog.isShowing)
            }
        } finally {
            // Clean up: close dialog if still open
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                if (dialog.isShowing) {
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
                dialog.disposeIfNeeded()
            }
        }
    }

    fun testDialogKillerWhenDisabled(): Unit = timeoutRunBlocking(10.seconds) {
        // Disable dialog killer
        setSystemPropertyForTest("mcp.steroid.dialog.killer.enabled", "false")

        // Create a test modal dialog
        val dialog = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            object : DialogWrapper(project, true) {
                init {
                    title = "Test Dialog (Should Not Be Killed)"
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    return JLabel("This dialog should not be killed when dialog killer is disabled")
                }
            }
        }

        try {
            // Show the dialog
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                dialog.show()
            }

            delay(500)

            // Try to kill dialogs (should be disabled)
            val result = DialogKiller.killProjectDialogs(
                project = project,
                executionId = ExecutionId("test-disabled"),
            )

            // Should not kill any dialogs
            assertEquals("Should not kill dialogs when disabled", 0, result.dialogsClosed)

            // Dialog should still be showing
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                assertTrue("Dialog should still be showing", dialog.isShowing)
            }
        } finally {
            // Clean up
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                if (dialog.isShowing) {
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
                dialog.disposeIfNeeded()
            }
            // Re-enable dialog killer for other tests
            setSystemPropertyForTest("mcp.steroid.dialog.killer.enabled", "true")
        }
    }
}
