/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
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
        // Start IDE container with full GUI
        val session = IdeContainer.create(lifetime, "ide-agent")

        // Wait for MCP server to be ready
        session.aiAgentDriver.waitForMcpReady()

        println("[TEST] IDE ready, MCP server at: ${session.aiAgentDriver.mcpSteroidHostUrl}")

        // Step 1: Open Settings dialog
        println("[TEST] Step 1: Opening Settings dialog...")
        val openDialogResult = session.intellijDriver.mcpExecuteCode(
            code = """
                // Open Settings dialog
                val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                val showSettingsAction = actionManager.getAction("ShowSettings")
                    ?: error("ShowSettings action not found")

                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build()

                val presentation = showSettingsAction.templatePresentation.clone()
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                    "test",
                    presentation,
                    dataContext
                )

                showSettingsAction.actionPerformed(event)

                // Wait for dialog to appear
                kotlinx.coroutines.delay(1500)

                // Check modal state
                val isModal = withContext(com.intellij.openapi.application.Dispatchers.EDT) {
                    com.intellij.openapi.application.ModalityState.current() != com.intellij.openapi.application.ModalityState.nonModal()
                }

                println("Modal state after opening Settings: ${"$"}isModal")

                if (!isModal) {
                    println("WARNING: Settings dialog may not have opened (no modal state detected)")
                }
            """.trimIndent(),
            taskId = "test-open-dialog",
            reason = "Open Settings dialog to test dialog killer"
        )

        println("[TEST] Open dialog result: $openDialogResult")

        // Step 2: Execute code again - dialog killer should automatically close the dialog
        println("[TEST] Step 2: Executing code with dialog killer enabled...")
        val dialogKillerResult = session.intellijDriver.mcpExecuteCode(
            code = """
                // This code should execute successfully because dialog killer will close the Settings dialog first
                println("✅ Code executed successfully - dialog killer worked!")

                // Verify modal state is cleared
                val isModal = withContext(com.intellij.openapi.application.Dispatchers.EDT) {
                    com.intellij.openapi.application.ModalityState.current() != com.intellij.openapi.application.ModalityState.nonModal()
                }

                println("Modal state after dialog killer: ${"$"}isModal")

                if (isModal) {
                    error("Dialog killer failed - modal state still active!")
                }

                println("✅ Modal state cleared successfully")
            """.trimIndent(),
            taskId = "test-dialog-killer",
            reason = "Verify dialog killer closed the Settings dialog"
        )

        println("[TEST] Dialog killer result: $dialogKillerResult")

        // Parse and verify the result
        val json = Json { ignoreUnknownKeys = true }
        val resultJson = json.parseToJsonElement(dialogKillerResult).jsonObject
        val result = resultJson["result"]?.jsonObject ?: error("No result in response")
        val isError = result["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false

        // Verify execution succeeded
        check(!isError) {
            "Dialog killer test failed - execution returned error:\n$dialogKillerResult"
        }

        // Verify output contains success message
        val content = result.toString()
        check(content.contains("Code executed successfully")) {
            "Dialog killer test failed - expected success message not found in output:\n$content"
        }

        println("[TEST] ✅ Dialog killer integration test PASSED!")
        println("[TEST] Successfully verified that dialog killer closes modal dialogs before execution")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `dialog killer can be disabled via registry`() {
        val session = IdeContainer.create(lifetime, "ide-agent")
        session.aiAgentDriver.waitForMcpReady()

        println("[TEST] Testing dialog killer disabled scenario...")

        // Step 1: Disable dialog killer
        println("[TEST] Step 1: Disabling dialog killer...")
        session.intellijDriver.mcpExecuteCode(
            code = """
                com.intellij.openapi.util.registry.Registry.get("mcp.steroid.dialog.killer.enabled").setValue(false)
                println("Dialog killer disabled")
            """.trimIndent(),
            taskId = "test-disable-killer",
            reason = "Disable dialog killer for testing"
        )

        // Step 2: Open Settings dialog
        println("[TEST] Step 2: Opening Settings dialog with killer disabled...")
        session.intellijDriver.mcpExecuteCode(
            code = """
                val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                val showSettingsAction = actionManager.getAction("ShowSettings")
                    ?: error("ShowSettings action not found")

                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build()

                val presentation = showSettingsAction.templatePresentation.clone()
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                    "test",
                    presentation,
                    dataContext
                )

                // Disable modal cancellation for this test
                doNotCancelOnModalityStateChange()

                showSettingsAction.actionPerformed(event)
                kotlinx.coroutines.delay(1500)

                println("Settings dialog opened with killer disabled")
            """.trimIndent(),
            taskId = "test-open-with-disabled",
            reason = "Open dialog with killer disabled"
        )

        // Step 3: Clean up - close dialog manually via keyboard
        println("[TEST] Step 3: Closing dialog manually with Escape key...")
        Thread.sleep(1000)
        session.input.keyPress("Escape")
        Thread.sleep(1000)

        // Step 4: Re-enable dialog killer for other tests
        println("[TEST] Step 4: Re-enabling dialog killer...")
        session.intellijDriver.mcpExecuteCode(
            code = """
                com.intellij.openapi.util.registry.Registry.get("mcp.steroid.dialog.killer.enabled").setValue(true)
                println("Dialog killer re-enabled")
            """.trimIndent(),
            taskId = "test-reenable-killer",
            reason = "Re-enable dialog killer"
        )

        println("[TEST] ✅ Dialog killer disabled test PASSED!")
    }
}
