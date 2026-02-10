/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // Start IDE container with full GUI
        val session = IdeContainer.create(lifetime, "ide-agent")

        // Wait for MCP server to be ready
        session.aiAgentDriver.waitForMcpReady()

        val run = session.intellijDriver.mcpExecuteCode(
            projectName = "project-home",  // Use the actual project name from IdeContainer
            code = """
                import kotlinx.coroutines.*
                import com.jonnyzzz.mcpSteroid.execution.*
                
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
                
                suspend fun isModal() = dialogKiller().canPumpEdtNonModel()

                require(isModal()) { "We must be modal now with settings" }

                kotlinx.coroutines.delay(1000)
                
                // Now kill it!
                dialogKiller().killProjectDialogs(
                  project, 
                  com.jonnyzzz.mcpSteroid.storage.ExecutionId("mock-id"), 
                  ::println
                )

                kotlinx.coroutines.delay(1000)

                require(!isModal()) { "We must NOT be modal now with settings" }
            """.trimIndent(),
            taskId = "test-open-dialog",
            reason = "Open Settings dialog to test dialog killer"
        )

        val windows = session.listWindows()

        windows.forEach {
            println("[WINDOW] $it")
        }

        println("\n[TEST] Step 1 Result:")
        val data = Json { }.parseToJsonElement(run)

        data.jsonObject["result"]?.jsonObject["content"]?.jsonArray?.forEach {
            it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { println("[MCP LOG]: $it ") }
        }

        Assertions.assertEquals(
            data.jsonObject["result"]?.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull,
            false
        )
    }
}
