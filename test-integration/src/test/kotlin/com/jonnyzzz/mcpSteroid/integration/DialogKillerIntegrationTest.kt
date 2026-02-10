/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test for DialogKiller functionality.
 *
 * This test runs the IDE in a Docker container with full GUI (via Xvfb) and verifies
 * that the dialog killer can detect and close modal dialogs before code execution.
 *
 * Uses direct MCP HTTP calls (bypassing AI agents) for reliable testing.
 */
class DialogKillerIntegrationTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `dialog killer closes Settings dialog`() {
        val session = IdeContainer.create(lifetime, "ide-agent")

        // Find the IDE process PID from the project window
        val idePid = session.listWindows()
            .first { it.title == "project-home" && it.pid.isNotBlank() }
            .pid
        println("[TEST] IDE PID: $idePid")

        // Step 1: Open Settings dialog and leave it open
        session.intellijDriver.mcpExecuteCode(
            projectName = "project-home",
            code = """
                // Disable modal cancellation so the dialog stays open after this execution
                doNotCancelOnModalityStateChange()

                val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                val showSettingsAction = actionManager.getAction("ShowSettings")
                    ?: error("ShowSettings action not found")

                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build()

                // Open Settings dialog asynchronously so it doesn't block this execution
                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        showSettingsAction.actionPerformed(
                            com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                                "test", showSettingsAction.templatePresentation.clone(), dataContext
                            )
                        )
                    }
                }

                kotlinx.coroutines.delay(1000)
                println("Settings dialog opened")
            """.trimIndent(),
            taskId = "open-settings",
            reason = "Open Settings dialog",
        ).assertExitCode(0)

        // Verify Settings dialog appeared via xcvb window list
        val windowsWithDialog = session.listWindows()
        val settingsWindow = windowsWithDialog.find { it.title == "Settings" && it.pid == idePid }
        println("[TEST] Windows after opening Settings:")
        windowsWithDialog.filter { it.pid == idePid }.forEach { println("  $it") }
        Assertions.assertNotNull(settingsWindow, "Settings dialog should be visible")

        // Step 2: Run another execute_code — the dialog killer should automatically close Settings
        session.intellijDriver.mcpExecuteCode(
            projectName = "project-home",
            code = """
                println("Dialog killer should have closed the Settings dialog before this runs")
            """.trimIndent(),
            taskId = "after-dialog-killer",
            reason = "Trigger automatic dialog killer",
        ).assertExitCode(0)

        // Verify Settings dialog is gone via xcvb window list
        val windowsAfterKiller = session.listWindows()
        val settingsAfterKiller = windowsAfterKiller.find { it.title == "Settings" && it.pid == idePid }
        println("[TEST] Windows after dialog killer:")
        windowsAfterKiller.filter { it.pid == idePid }.forEach { println("  $it") }
        Assertions.assertNull(settingsAfterKiller, "Settings dialog should have been closed by dialog killer")
    }
}
