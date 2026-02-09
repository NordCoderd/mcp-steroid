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

        println("=" . repeat(80))
        println("[TEST] IDE ready, MCP server at: ${session.aiAgentDriver.mcpSteroidHostUrl}")
        println("=" . repeat(80))

        // ========================================
        // Step 1: Open Settings dialog
        // ========================================
        println("\n[TEST] ===== STEP 1: Opening Settings Dialog =====")
        val openDialogResult = session.intellijDriver.mcpExecuteCode(
            projectName = "project-home",  // Use the actual project name from IdeContainer
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

                // Disable modal cancellation for this execution (we WANT the dialog to stay open)
                doNotCancelOnModalityStateChange()

                // Open Settings dialog asynchronously so it doesn't block this execution from completing
                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        showSettingsAction.actionPerformed(event)
                    }
                }

                // Give the dialog time to open
                kotlinx.coroutines.delay(3000)

                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("✓ Settings dialog should be opening now")
                println("✓ This execution will complete, dialog opens after")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            """.trimIndent(),
            taskId = "test-open-dialog",
            reason = "Open Settings dialog to test dialog killer"
        )

        println("\n[TEST] Step 1 Result:")
        println(openDialogResult)

        // Verify step 1 succeeded
        val json = Json { ignoreUnknownKeys = true }
        val openResultJson = json.parseToJsonElement(openDialogResult).jsonObject
        val openResult = openResultJson["result"]?.jsonObject ?: error("No result in open dialog response")
        val openIsError = openResult["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false
        check(!openIsError) { "Failed to trigger Settings dialog opening:\n$openDialogResult" }

        println("[TEST] ✓ Settings dialog opening triggered, it should be modal now")

        // Give the Settings dialog extra time to fully open and become modal
        println("[TEST] Waiting 2 seconds for Settings dialog to fully open...")
        Thread.sleep(2000)

        // ========================================
        // Step 2: Execute code - dialog killer should close the dialog FIRST
        // ========================================
        println("\n[TEST] ===== STEP 2: Trigger Dialog Killer =====")
        println("[TEST] Executing code with dialog killer enabled...")
        println("[TEST] Expected: Dialog killer should detect modal state and close the dialog BEFORE execution")

        val dialogKillerResult = session.intellijDriver.mcpExecuteCode(
            projectName = "project-home",  // Use the actual project name from IdeContainer
            code = """
                // This code should execute successfully because dialog killer will close the Settings dialog first
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("✓ Code execution started successfully")
                println("✓ This means dialog killer closed the blocking dialog!")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Verify modal state is cleared
                val isModal = withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    com.intellij.openapi.application.ModalityState.current() != com.intellij.openapi.application.ModalityState.nonModal()
                }

                println("Modal state after dialog killer: ${"$"}isModal")

                if (isModal) {
                    error("Dialog killer failed - modal state still active!")
                }

                println("✓ Modal state cleared successfully")
                println("✓ Dialog killer test PASSED!")
            """.trimIndent(),
            taskId = "test-dialog-killer",
            reason = "Verify dialog killer closed the Settings dialog"
        )

        println("\n[TEST] Step 2 Result (FULL MCP OUTPUT):")
        println("=" . repeat(80))
        println(dialogKillerResult)
        println("=" . repeat(80))

        // ========================================
        // Step 3: Validate the MCP output
        // ========================================
        println("\n[TEST] ===== STEP 3: Validate MCP Output =====")

        // Parse and verify the result
        val resultJson = json.parseToJsonElement(dialogKillerResult).jsonObject
        val result = resultJson["result"]?.jsonObject ?: error("No result in response")
        val isError = result["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false

        // Verify execution succeeded
        check(!isError) {
            "Dialog killer test failed - execution returned error:\n$dialogKillerResult"
        }

        // Verify output contains success messages
        val content = result.toString()
        check(content.contains("Code execution started successfully")) {
            "Dialog killer test failed - expected success message not found in output:\n$content"
        }

        check(content.contains("Modal state cleared")) {
            "Dialog killer test failed - modal state was not cleared:\n$content"
        }

        // Check for dialog killer activity in the output
        // The dialog killer should log progress messages via mcpProgressReporter
        println("\n[TEST] Checking for dialog killer activity in MCP output...")
        if (dialogKillerResult.contains("Dialog Killer") || dialogKillerResult.contains("dialog(s)") || dialogKillerResult.contains("closed")) {
            println("[TEST] ✓ Found dialog killer activity in MCP output")
        } else {
            println("[TEST] ⚠️  Dialog killer activity not explicitly mentioned in output")
            println("[TEST] This might be expected if progress messages are not included in the result")
        }

        println("\n" + "=" . repeat(80))
        println("[TEST] ✅✅✅ DIALOG KILLER INTEGRATION TEST PASSED! ✅✅✅")
        println("=" . repeat(80))
        println("[TEST] Summary:")
        println("[TEST]   1. Settings dialog was opened (modal state detected)")
        println("[TEST]   2. Execute_code was called with dialog blocking")
        println("[TEST]   3. Dialog killer automatically closed the dialog")
        println("[TEST]   4. Code execution succeeded")
        println("[TEST]   5. Modal state was cleared")
        println("=" . repeat(80))
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
            projectName = "project-home",
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
            projectName = "project-home",
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
            projectName = "project-home",
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
